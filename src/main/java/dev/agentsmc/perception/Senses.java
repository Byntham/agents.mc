package dev.agentsmc.perception;

import dev.agentsmc.AgentsMc;
import dev.agentsmc.Session;
import dev.agentsmc.agent.AgentManager;
import dev.agentsmc.agent.AgentPlayer;
import dev.agentsmc.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a player would notice without looking for it: a mob closing in, a friend walking off, a
 * sound, damage, dusk. Each becomes a short second-person line in the agent's own event feed, with a
 * salience that decides whether it is worth waking an idle agent for. Also renders the one-line
 * status that goes on every message. Runs on the server thread.
 */
public final class Senses {
	private static final double HOSTILE_RANGE = 16, PLAYER_RANGE = 32;
	private static final Set<String> HOSTILE_AMBIENT = Set.of("zombie", "skeleton", "spider", "cave_spider", "enderman", "witch", "husk", "drowned",
			"stray", "phantom", "pillager", "zombie_villager", "zombified_piglin", "blaze", "ghast", "slime", "silverfish", "endermite", "guardian",
			"vindicator", "evoker", "ravager", "wither_skeleton", "piglin", "hoglin", "warden", "breeze", "bogged", "creaking", "magma_cube");

	private final Body body;
	private final AgentPlayer agent;
	private final Map<Integer, Track> tracks = new HashMap<>();
	private final Map<String, Integer> soundCooldown = new HashMap<>();
	private int scans;
	private boolean darkReported;
	private String lastPhase;
	private float pendingDamage;
	private String pendingDamageFrom;
	private int damageTick = -1000;
	private int lavaTick = -1000, airTick = -1000;

	/** One entity we're keeping an eye on. */
	private static final class Track {
		final String name;
		final boolean hostile;
		double reportedDist;
		int lastScan;
		boolean targeting, near;

		Track(String name, boolean hostile, double dist, int scan) {
			this.name = name;
			this.hostile = hostile;
			this.reportedDist = dist;
			this.lastScan = scan;
		}
	}

	public Senses(Body body) {
		this.body = body;
		this.agent = body.agent;
	}

	public void tick() {
		if (!agent.isAlive()) return;
		int t = agent.tickCount;
		if (t % 10 == 0) scanEntities();
		if (t % 20 == 0) {
			light();
			time();
			hazards();
		}
		if (pendingDamage > 0 && t - damageTick >= 10) flushDamage();
	}

	// ---- status ---------------------------------------------------------------------------------

	/** One line: where, facing, footing, speed, health, food, time, weather, light, hand, and what the body is doing. */
	public String statusLine() {
		ServerLevel level = agent.level();
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "You: %.0f %.0f %.0f facing %s, %s", agent.getX(), agent.getY(), agent.getZ(), facing(), footing()));
		if (body.speed() > 0.5) sb.append(String.format(Locale.ROOT, ", moving %.1f m/s", body.speed()));
		sb.append(String.format(Locale.ROOT, " | HP %.0f/%.0f food %d/20", agent.getHealth(), agent.getMaxHealth(), agent.getFoodData().getFoodLevel()));
		if (agent.isOnFire()) sb.append(" ON FIRE");
		long time = level.getDefaultClockTime();
		sb.append(" | ").append(clock(time)).append(" (").append(Views.phase(Math.floorMod(time, 24000L))).append("), ")
				.append(level.isThundering() ? "thunderstorm" : level.isRaining() ? "rain" : "clear")
				.append(", light ").append(level.getMaxLocalRawBrightness(agent.blockPosition()));
		sb.append(" | holding ").append(agent.getMainHandItem().isEmpty() ? "nothing" : Views.stack(agent.getMainHandItem()));
		sb.append(" | doing: ").append(body.doing());
		return sb.toString();
	}

	/** "day 3 14:20". Minecraft's day starts at 06:00. */
	public static String clock(long time) {
		long day = Math.floorDiv(time, 24000L) + 1;
		long tod = Math.floorMod(time, 24000L);
		long hour = (tod / 1000 + 6) % 24;
		long minute = (tod % 1000) * 60 / 1000;
		return String.format(Locale.ROOT, "day %d %02d:%02d", day, hour, minute);
	}

	private String facing() {
		double rad = Math.toRadians(agent.getYRot());
		return compassShort(-Math.sin(rad), Math.cos(rad));
	}

	private String footing() {
		if (agent.isInLava()) return "in lava";
		if (agent.isUnderWater()) return "underwater";
		if (agent.isInWater()) return "in water";
		if (agent.getAbilities().flying) return "flying";
		if (agent.onGround()) {
			BlockState on = agent.level().getBlockState(agent.getOnPos());
			return "on " + BlockCodec.id(on, BlockCodec.Detail.NONE);
		}
		if (agent.isPassenger()) return "riding " + name(agent.getVehicle());
		return agent.fallDistance > 1 ? String.format(Locale.ROOT, "falling (%.0fm so far)", agent.fallDistance) : "in the air";
	}

	// ---- directions -----------------------------------------------------------------------------

	/** "8m NE (ahead-right)": distance, compass direction, and where that is relative to the body's facing. */
	public static String relative(ServerPlayer from, Vec3 to) {
		Vec3 d = to.subtract(from.position());
		double dist = d.length();
		double h = Math.sqrt(d.x * d.x + d.z * d.z);
		String where;
		if (h < 0.75) {
			where = d.y > 1 ? "above you" : d.y < -1 ? "below you" : "right here";
		} else {
			double angle = Math.toDegrees(Math.atan2(-d.x, d.z)); // yaw of the offset
			double rel = Mth.wrapDegrees(angle - from.getYRot()); // positive = to the right
			where = compassShort(d.x, d.z) + " (" + sector(rel) + ")";
			if (d.y > 3) where += " above";
			else if (d.y < -3) where += " below";
		}
		return String.format(Locale.ROOT, "%.0fm %s", dist, where);
	}

	private static String sector(double rel) {
		double a = Math.abs(rel);
		String side = rel > 0 ? "right" : "left";
		if (a < 22.5) return "ahead";
		if (a < 67.5) return "ahead-" + side;
		if (a < 112.5) return side;
		if (a < 157.5) return "behind-" + side;
		return "behind";
	}

	public static String compassShort(double dx, double dz) {
		String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		double yaw = Math.toDegrees(Math.atan2(-dx, dz));
		return names[(int) Math.floorMod(Math.round(yaw / 45.0), 8L)];
	}

	/** A short name for an entity: a player's name, or its type (with a custom name if it has one). */
	public static String name(Entity e) {
		if (e == null) return "something";
		if (e instanceof Player p) return p.getName().getString();
		String type = BlockCodec.shortId(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
		return e.hasCustomName() ? type + " \"" + e.getCustomName().getString() + "\"" : type;
	}

	// ---- entities -------------------------------------------------------------------------------

	private void scanEntities() {
		scans++;
		ServerLevel level = agent.level();
		List<Entity> seen = level.getEntities(agent, agent.getBoundingBox().inflate(PLAYER_RANGE), e ->
				e instanceof Monster && e.distanceToSqr(agent) <= HOSTILE_RANGE * HOSTILE_RANGE
						|| e instanceof ServerPlayer && !e.isSpectator());
		for (Entity e : seen) {
			double dist = agent.distanceTo(e);
			boolean hostile = e instanceof Monster;
			Track t = tracks.get(e.getId());
			if (t == null) {
				t = new Track(name(e), hostile, dist, scans);
				tracks.put(e.getId(), t);
				if (hostile) {
					body.event("mob", String.format(Locale.ROOT, "A %s %s.", t.name, relative(agent, e.position())), dist < 8 ? EventLog.WAKE : EventLog.NOTABLE);
				} else if (e instanceof ServerPlayer p && !AgentManager.isAgent(p)) {
					body.event("player", String.format(Locale.ROOT, "%s is %s.", t.name, relative(agent, e.position())), EventLog.NOTABLE);
				}
				continue;
			}
			t.lastScan = scans;
			if (hostile) {
				boolean targeting = e instanceof Mob m && m.getTarget() == agent;
				if (targeting && !t.targeting) {
					t.targeting = true;
					t.reportedDist = dist;
					body.event("mob", String.format(Locale.ROOT, "The %s is coming for you, %s.", t.name, relative(agent, e.position())), dist < 6 ? EventLog.URGENT : EventLog.WAKE);
				} else if (dist < t.reportedDist - 4 && dist < 12) {
					t.reportedDist = dist;
					body.event("mob", String.format(Locale.ROOT, "%s closing in, %s.", cap(t.name), relative(agent, e.position())), dist < 5 ? EventLog.URGENT : EventLog.WAKE);
				} else if (dist > t.reportedDist + 6) {
					t.reportedDist = dist; // moved away; reset so a return gets reported
				}
			} else if (e instanceof ServerPlayer p && !AgentManager.isAgent(p)) {
				if (dist < 4 && !t.near) {
					t.near = true;
					body.event("player", String.format(Locale.ROOT, "%s is close now, %s.", t.name, relative(agent, e.position())), EventLog.NOTABLE);
				} else if (dist > 8) {
					t.near = false;
				}
			}
		}
		// Forget what we haven't seen for a while, and say so for players and nearby hostiles.
		for (Iterator<Map.Entry<Integer, Track>> it = tracks.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Integer, Track> en = it.next();
			Track t = en.getValue();
			if (t.lastScan >= scans - 2) continue;
			it.remove();
			Entity e = level.getEntity(en.getKey());
			String last = e == null || e.isRemoved() ? "" : ", last " + relative(agent, e.position());
			if (!t.hostile) {
				body.event("player", String.format(Locale.ROOT, "%s is out of range%s.", t.name, last), EventLog.NOTABLE);
			} else if (t.reportedDist < 8) {
				body.event("mob", String.format(Locale.ROOT, "The %s is gone%s.", t.name, last), EventLog.QUIET);
			}
		}
	}

	// ---- light, time, hazards -------------------------------------------------------------------

	private void light() {
		int light = agent.level().getMaxLocalRawBrightness(agent.blockPosition());
		if (light < 7 && !darkReported) {
			darkReported = true;
			body.event("light", String.format(Locale.ROOT, "It's dark here (light %d). Hostile mobs can spawn in the dark.", light), EventLog.NOTABLE);
		} else if (light >= 9 && darkReported) {
			darkReported = false;
		}
	}

	private void time() {
		String phase = Views.phase(Math.floorMod(agent.level().getDefaultClockTime(), 24000L));
		if (lastPhase == null) {
			lastPhase = phase;
			return;
		}
		if (phase.equals(lastPhase)) return;
		lastPhase = phase;
		switch (phase) {
			case "sunset" -> body.event("time", "The sun is setting. Night comes in about 2 minutes.", EventLog.WAKE);
			case "night" -> body.event("time", "It's night.", EventLog.NOTABLE);
			case "sunrise" -> body.event("time", "The sun is rising.", EventLog.NOTABLE);
			default -> body.event("time", "It's " + phase + ".", EventLog.QUIET);
		}
	}

	private void hazards() {
		int t = agent.tickCount;
		if (agent.isInLava() && t - lavaTick > 40) {
			lavaTick = t;
			body.event("hazard", "You are in lava!", EventLog.URGENT);
		}
		if (agent.isUnderWater() && agent.getAirSupply() < 100 && t - airTick > 60) {
			airTick = t;
			body.event("hazard", String.format(Locale.ROOT, "You're running out of air underwater (%ds left).", agent.getAirSupply() / 20), EventLog.URGENT);
		}
	}

	// ---- damage ---------------------------------------------------------------------------------

	/** Called after the body took damage. Several hits in quick succession are reported together. */
	public void onHurt(DamageSource source, float amount) {
		String from;
		Entity attacker = source.getEntity();
		if (attacker != null && attacker != agent) {
			from = "the " + name(attacker) + " " + relative(agent, attacker.position());
			if (attacker instanceof Player) from = name(attacker) + " " + relative(agent, attacker.position());
		} else {
			from = switch (source.getMsgId()) {
				case "fall" -> "falling";
				case "inFire", "onFire" -> "burning";
				case "lava" -> "lava";
				case "drown" -> "drowning";
				case "starve" -> "starving";
				case "cactus" -> "a cactus";
				case "explosion", "explosion.player" -> "an explosion";
				case "fallingBlock" -> "a falling block";
				case "inWall" -> "suffocating in a wall";
				case "lightningBolt" -> "lightning";
				case "hotFloor" -> "a magma block";
				case "sweetBerryBush" -> "a berry bush";
				case "freeze" -> "freezing";
				default -> source.getMsgId();
			};
		}
		pendingDamage += amount;
		pendingDamageFrom = from;
		if (damageTick < agent.tickCount - 10) {
			damageTick = agent.tickCount;
			flushDamage();
		}
	}

	private void flushDamage() {
		float hp = agent.getHealth();
		int salience = hp <= 8 ? EventLog.URGENT : EventLog.WAKE;
		body.event("damage", String.format(Locale.ROOT, "You took %.0f damage from %s. Health %.0f/%.0f.", pendingDamage, pendingDamageFrom, hp, agent.getMaxHealth()), salience);
		pendingDamage = 0;
		damageTick = agent.tickCount;
	}

	// ---- hearing --------------------------------------------------------------------------------

	/** Every sound the server plays passes through here (from a mixin on ServerLevel). */
	public static void onSound(ServerLevel level, double x, double y, double z, Holder<SoundEvent> sound, SoundSource category, float volume) {
		Session s = AgentsMc.session();
		if (s == null || s.agents.all().isEmpty()) return;
		String path = sound.value().location().getPath();
		if (!interesting(path)) return;
		Vec3 at = new Vec3(x, y, z);
		double range = sound.value().getRange(volume);
		for (AgentPlayer a : s.agents.all()) {
			if (a.level() != level || !a.isAlive()) continue;
			double dist = a.position().distanceTo(at);
			if (dist > range) continue;
			a.body().senses.heard(path, category, at, dist);
		}
	}

	private static boolean interesting(String path) {
		if (path.startsWith("ambient.") || path.startsWith("weather.") || path.startsWith("music") || path.startsWith("ui.") || path.startsWith("item.")) return false;
		if (path.endsWith(".step") || path.endsWith(".swim") || path.endsWith(".splash") || path.endsWith(".hurt") && !path.startsWith("entity.player")) return false;
		if (path.startsWith("block.") && !(path.contains("door") || path.contains("gate") || path.contains("chest") || path.contains("bell")
				|| path.contains("note_block") || path.contains("trapdoor") || path.contains("anvil") || path.contains("piston"))) return false;
		if (path.startsWith("entity.") && (path.endsWith(".eat") || path.endsWith(".drink") || path.contains("experience_orb") || path.contains("item_frame")
				|| path.contains(".pickup") || path.contains("entity.player.attack") || path.contains("entity.player.levelup") || path.contains("entity.player.breath")
				|| path.contains("entity.player.splash") || path.contains("entity.player.swim") || path.contains("entity.generic.") && !path.contains("explode")
				|| path.contains("entity.villager") || path.contains("entity.item"))) return false;
		return true;
	}

	private void heard(String path, SoundSource category, Vec3 at, double dist) {
		if (at.distanceToSqr(agent.position()) < 2.25 && !path.contains("explode") && !path.contains("primed")) return; // our own noises
		String[] parts = path.split("\\.");
		String kind = parts.length > 1 ? parts[1] : path;
		String what;
		int salience;
		int cooldown = 100;
		if (path.equals("entity.creeper.primed")) {
			what = "a creeper hissing";
			salience = EventLog.URGENT;
			cooldown = 30;
		} else if (path.contains("explode")) {
			what = "an explosion";
			salience = EventLog.WAKE;
			cooldown = 20;
		} else if (path.equals("entity.tnt.primed")) {
			what = "TNT fizzing";
			salience = EventLog.WAKE;
		} else if (path.startsWith("entity.player.hurt")) {
			what = (nearestPlayerName(at)) + " getting hurt";
			salience = EventLog.WAKE;
			cooldown = 40;
		} else if (path.equals("entity.lightning_bolt.thunder")) {
			what = "thunder";
			salience = EventLog.QUIET;
			cooldown = 200;
		} else if (path.startsWith("block.") && (path.contains("door") || path.contains("gate") || path.contains("trapdoor"))) {
			what = "a door " + (path.endsWith("open") ? "opening" : "closing");
			salience = EventLog.NOTABLE;
			cooldown = 60;
		} else if (path.startsWith("block.chest") || path.contains("barrel") || path.contains("shulker_box")) {
			what = "a chest " + (path.endsWith("open") ? "opening" : "closing");
			salience = EventLog.NOTABLE;
			cooldown = 60;
		} else if (path.contains("bell")) {
			what = "a bell";
			salience = EventLog.NOTABLE;
		} else if (path.contains("note_block")) {
			what = "a note block";
			salience = EventLog.QUIET;
		} else if (path.endsWith(".death")) {
			what = "a " + kind + " dying";
			salience = EventLog.NOTABLE;
			cooldown = 40;
		} else if (path.endsWith(".shoot") || path.contains("arrow")) {
			what = "an arrow being shot";
			salience = EventLog.NOTABLE;
			cooldown = 60;
		} else if (path.endsWith(".ambient") && HOSTILE_AMBIENT.contains(kind)) {
			// Hostiles we can already see don't need announcing by sound.
			if (isTracked(at)) return;
			what = "a " + kind + " nearby";
			salience = EventLog.NOTABLE;
			cooldown = 200;
		} else if (path.contains("warden") || path.contains("wither") || path.contains("ender_dragon")) {
			what = kind.replace('_', ' ');
			salience = EventLog.WAKE;
		} else {
			return;
		}
		int now = agent.tickCount;
		Integer last = soundCooldown.get(what);
		if (last != null && now - last < cooldown) return;
		soundCooldown.put(what, now);
		body.event("sound", String.format(Locale.ROOT, "You heard %s, %s.", what, relative(agent, at)), salience);
	}

	private boolean isTracked(Vec3 at) {
		for (Entity e : agent.level().getEntities(agent, new AABB(at, at).inflate(1.5), e -> e instanceof Monster)) {
			if (tracks.containsKey(e.getId())) return true;
		}
		return false;
	}

	private String nearestPlayerName(Vec3 at) {
		ServerPlayer best = null;
		double d = 4;
		for (ServerPlayer p : agent.level().getPlayers(p -> p != agent)) {
			double pd = p.position().distanceTo(at);
			if (pd < d) {
				d = pd;
				best = p;
			}
		}
		return best == null ? "someone" : best.getName().getString();
	}

	// ---- sweep ----------------------------------------------------------------------------------

	/**
	 * A sonar-like look around: for eight directions relative to facing, the nearest thing a ray hits
	 * at eye level and slightly down, plus any entity in that direction. About 200 tokens.
	 */
	public String sweep() {
		ServerLevel level = agent.level();
		Vec3 eye = agent.getEyePosition();
		String[] sectors = {"ahead", "ahead-right", "right", "behind-right", "behind", "behind-left", "left", "ahead-left"};
		List<Entity> around = level.getEntities(agent, agent.getBoundingBox().inflate(24), e -> e instanceof LivingEntity || e instanceof net.minecraft.world.entity.item.ItemEntity);
		StringBuilder sb = new StringBuilder();
		sb.append("Facing ").append(facing()).append(". Each direction: what a ray hits at eye level | slightly down | entities. Distances in blocks.\n");
		for (int i = 0; i < 8; i++) {
			float yaw = agent.getYRot() + i * 45f;
			double rad = Math.toRadians(yaw);
			Vec3 dir = new Vec3(-Math.sin(rad), 0, Math.cos(rad));
			String level0 = ray(level, eye, dir, 24);
			Vec3 down = new Vec3(dir.x * Math.cos(Math.toRadians(20)), -Math.sin(Math.toRadians(20)), dir.z * Math.cos(Math.toRadians(20)));
			String level1 = ray(level, eye, down, 12);
			List<String> ents = new ArrayList<>();
			for (Entity e : around) {
				Vec3 d = e.position().subtract(agent.position());
				double h = Math.sqrt(d.x * d.x + d.z * d.z);
				if (h < 0.75) continue;
				double rel = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-d.x, d.z)) - agent.getYRot());
				if (Math.floorMod(Math.round(rel / 45.0), 8L) != i) continue;
				if (ents.size() < 3) ents.add(String.format(Locale.ROOT, "%s %.0fm", name(e), d.length()));
			}
			sb.append(String.format(Locale.ROOT, " %-13s %s: %s | %s%s\n", sectors[i], compassShort(dir.x, dir.z), level0, level1,
					ents.isEmpty() ? "" : " | " + String.join(", ", ents)));
		}
		// Footing ahead: a drop or a wall within the next few blocks.
		double rad = Math.toRadians(agent.getYRot());
		Vec3 dir = new Vec3(-Math.sin(rad), 0, Math.cos(rad));
		for (int step = 1; step <= 3; step++) {
			BlockPos p = BlockPos.containing(agent.position().add(dir.scale(step)));
			int drop = 0;
			while (drop < 8 && level.getBlockState(p.below(drop + 1)).getCollisionShape(level, p.below(drop + 1)).isEmpty()) drop++;
			if (drop >= 3) {
				sb.append(String.format(Locale.ROOT, " ground: a %d+ block drop %d block%s ahead\n", drop, step, step == 1 ? "" : "s"));
				break;
			}
		}
		sb.append(" above: ").append(level.canSeeSky(agent.blockPosition().above(2)) ? "open sky" : "covered").append('\n');
		return sb.toString();
	}

	private String ray(ServerLevel level, Vec3 from, Vec3 dir, double max) {
		BlockHitResult hit = level.clip(new ClipContext(from, from.add(dir.scale(max)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, agent));
		if (hit.getType() != HitResult.Type.BLOCK) return String.format(Locale.ROOT, "open %.0f+", max);
		BlockState s = level.getBlockState(hit.getBlockPos());
		return String.format(Locale.ROOT, "%s %.0f", BlockCodec.id(s, BlockCodec.Detail.NONE), from.distanceTo(hit.getLocation()));
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
