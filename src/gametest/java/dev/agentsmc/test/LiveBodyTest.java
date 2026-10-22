package dev.agentsmc.test;

import com.google.gson.JsonObject;
import dev.agentsmc.AgentsMc;
import dev.agentsmc.Session;
import dev.agentsmc.agent.AgentPlayer;
import dev.agentsmc.brain.ActivityLog;
import dev.agentsmc.brain.AgentBrain;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * A real Claude Code agent playing with the host in survival: it is asked to follow, to chop logs,
 * it gets attacked, and it is asked what happened. Uses the host's Claude login, so it costs usage.
 * Run with AGENTSMC_ONLY=live. AGENTSMC_LIVE_MODEL and AGENTSMC_LIVE_EFFORT pick the model (default
 * sonnet, low).
 */
public class LiveBodyTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("agentsmc-test");
	private static final String AGENT = "Scout";

	private ClientGameTestContext ctx;
	private TestSingleplayerContext world;
	private Narrator narrator;
	private int watched;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip("live")) return;
		this.ctx = ctx;
		ctx.getInput().resizeWindow(1280, 720);
		LOG.info("recording can start");
		writeConfig(System.getenv().getOrDefault("AGENTSMC_LIVE_MODEL", "sonnet"), System.getenv().getOrDefault("AGENTSMC_LIVE_EFFORT", "low"));
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			this.world = world;
			world.getConnection().waitForChunksRender();
			run("time set noon");
			run("weather clear");
			run("gamerule send_command_feedback false");
			run("gamerule spawn_monsters false");
			run("gamerule advance_time false");
			run("gamerule advance_weather false");
			ctx.runOnClient(mc -> mc.gui.hud.vignetteBrightness = 0);
			BlockPos h = world.getServer().computeOnServer(server -> host(server).blockPosition());
			int x = h.getX(), y = h.getY(), z = h.getZ();
			run(String.format(Locale.ROOT, "tp Player0 %d %d %d 0 5", x, y, z));
			// Three oak trunks to the south, and a one-block step between.
			for (int t = -1; t <= 1; t++) for (int i = 0; i < 4; i++) run(String.format(Locale.ROOT, "setblock %d %d %d oak_log", x + t * 4, y + i, z + 18));
			run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d stone", x - 10, y, z + 9, x + 10, y, z + 9));
			ctx.waitTicks(20);

			// The agent, from inside the game, like a player would do it.
			chatCommand("agent spawn " + AGENT);
			world.getServer().waitFor(server -> AgentsMc.session().brains.body(AGENT) != null, 200);
			narrator = new Narrator(world, AGENT);
			run("gamemode survival " + AGENT);
			run("give " + AGENT + " iron_axe");
			run("give " + AGENT + " iron_sword");
			run("give " + AGENT + " bread 4");
			ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
			watch(40);
			ctx.takeScreenshot("live-00-spawned");

			// 1. Follow me.
			chat("@" + AGENT + " hi! follow me around for a bit");
			waitForBrain(b -> toolCalled(b, "follow"), 20 * 120);
			ctx.takeScreenshot("live-01-following");
			run(String.format(Locale.ROOT, "tp Player0 %d %d %d 0 0", x, y, z));
			ctx.waitTicks(10);
			ctx.getInput().holdKey(o -> o.keyUp);
			lookFor(50, 0, 0);     // south
			lookFor(60, 90, 0);    // west
			lookFor(60, 180, 0);   // north
			lookFor(40, -90, 0);   // east
			ctx.getInput().releaseKey(o -> o.keyUp);
			watch(60);
			LOG.info("after the walk: agent is {} blocks from the host", agentPos().distanceTo(hostPos()));
			ctx.takeScreenshot("live-02-followed");

			// 2. Chop those logs.
			run(String.format(Locale.ROOT, "tp Player0 %d %d %d 0 10", x, y, z + 6));
			chat("@" + AGENT + " see the three oak trunks south of me? chop them all down and come back to me when you're done");
			int logsBefore = logsStanding(x, y, z);
			waitForBrain(b -> b.status() == AgentBrain.Status.IDLE && logsStanding(x, y, z) <= 2, 20 * 240);
			LOG.info("logs standing: {} before, {} after", logsBefore, logsStanding(x, y, z));
			Vec3 back = agentPos();
			run(String.format(Locale.ROOT, "tp Player0 %.1f %.1f %.1f", back.x - 6, back.y, back.z - 3));
			watch(40);
			ctx.takeScreenshot("live-03-chopped");

			// 3. A zombie attacks the agent while it's idle; reflexes fight, senses wake the brain.
			Vec3 a = agentPos();
			int says = world.getServer().computeOnServer(server -> count(AgentsMc.session().brains.get(AGENT), ActivityLog.Kind.SAY));
			run(String.format(Locale.ROOT, "summon zombie %.1f %.1f %.1f {PersistenceRequired:1b}", a.x + 5, a.y, a.z + 5));
			world.getServer().waitFor(server -> server.overworld().getEntitiesOfClass(Monster.class, new AABB(h).inflate(80), Entity::isAlive).isEmpty(), 20 * 60);
			LOG.info("zombie dead");
			waitForBrain(b -> count(b, ActivityLog.Kind.SAY) > says || b.status() == AgentBrain.Status.IDLE && b.log.last().kind() == ActivityLog.Kind.INFO, 20 * 90);
			watch(40);
			ctx.takeScreenshot("live-04-fought");

			// 4. Ask what happened, from a few blocks back.
			Vec3 ap = agentPos();
			run(String.format(Locale.ROOT, "tp Player0 %.1f %.1f %.1f", ap.x - 5, ap.y, ap.z - 4));
			watch(20);
			int says2 = world.getServer().computeOnServer(server -> count(AgentsMc.session().brains.get(AGENT), ActivityLog.Kind.SAY));
			chat("@" + AGENT + " what just happened? and how are you doing?");
			waitForBrain(b -> count(b, ActivityLog.Kind.SAY) > says2, 20 * 120);
			watch(80);
			ctx.takeScreenshot("live-05-asked");
			dump();
		}
	}

	// ---- helpers ----

	private boolean toolCalled(AgentBrain b, String tool) {
		for (ActivityLog.Entry e : b.log.tail(100)) if (e.kind() == ActivityLog.Kind.TOOL && e.text().contains(tool)) return true;
		return false;
	}

	private int logsStanding(int x, int y, int z) {
		return world.getServer().computeOnServer(server -> {
			int n = 0;
			for (int t = -1; t <= 1; t++) for (int i = 0; i < 4; i++) {
				if (server.overworld().getBlockState(new BlockPos(x + t * 4, y + i, z + 18)).is(Blocks.OAK_LOG)) n++;
			}
			return n;
		});
	}

	private void chat(String text) {
		LOG.info("chat: {}", text);
		ctx.runOnClient(mc -> mc.player.connection.sendChat(text));
	}

	private void chatCommand(String command) {
		ctx.runOnClient(mc -> mc.player.connection.sendCommand(command));
	}

	private void run(String command) {
		world.getServer().runCommand(command);
	}

	/** Waits for a brain condition while the host keeps looking at the agent. */
	private void waitForBrain(Predicate<AgentBrain> done, int ticks) {
		for (int t = 0; t < ticks; t += 2) {
			boolean ok = world.getServer().computeOnServer(server -> {
				Session s = AgentsMc.session();
				AgentBrain b = s == null ? null : s.brains.find(AGENT);
				return b != null && done.test(b);
			});
			if (ok) return;
			watch(2);
		}
		dump();
		throw new AssertionError("Timed out waiting for " + AGENT);
	}

	private void watch(int ticks) {
		for (int i = 0; i < ticks; i += 2) {
			Vec3 at = world.getServer().computeOnServer(server -> {
				AgentPlayer a = AgentsMc.session().agents.find(AGENT);
				return a == null ? null : a.getEyePosition();
			});
			if (at != null) ctx.runOnClient(mc -> {
				if (mc.player != null) mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, at);
			});
			if (narrator != null && (watched += 2) % 6 == 0) narrator.tick();
			ctx.waitTicks(2);
		}
	}

	private void lookFor(int ticks, float yaw, float pitch) {
		for (int i = 0; i < ticks; i += 2) {
			ctx.runOnClient(mc -> {
				if (mc.player != null) {
					mc.player.setYRot(yaw);
					mc.player.setXRot(pitch);
				}
			});
			if (narrator != null && (watched += 2) % 6 == 0) narrator.tick();
			ctx.waitTicks(2);
		}
	}

	private Vec3 agentPos() {
		return world.getServer().computeOnServer(server -> AgentsMc.session().agents.get(AGENT).position());
	}

	private Vec3 hostPos() {
		return world.getServer().computeOnServer(server -> host(server).position());
	}

	private static ServerPlayer host(net.minecraft.server.MinecraftServer server) {
		return server.getPlayerList().getPlayers().stream().filter(p -> p.getName().getString().equals("Player0")).findFirst().orElseThrow();
	}

	private static int count(AgentBrain b, ActivityLog.Kind kind) {
		int n = 0;
		for (ActivityLog.Entry e : b.log.tail(300)) if (e.kind() == kind) n++;
		return n;
	}

	private void dump() {
		String text = world.getServer().computeOnServer(server -> {
			Session s = AgentsMc.session();
			AgentBrain b = s == null ? null : s.brains.find(AGENT);
			if (b == null) return "(no agent)";
			StringBuilder sb = new StringBuilder(AGENT + " is " + b.status() + ", log:\n");
			for (ActivityLog.Entry e : b.log.tail(120)) sb.append("  ").append(e.kind()).append(' ').append(e.who() == null ? "" : e.who() + ": ").append(e.text()).append('\n');
			return sb.toString();
		});
		LOG.info("{}", text);
	}

	private static void writeConfig(String model, String effort) {
		JsonObject config = new JsonObject();
		config.addProperty("port", 25591);
		config.addProperty("model", model);
		config.addProperty("effort", effort);
		try {
			Path file = FabricLoader.getInstance().getConfigDir().resolve("agentsmc.json");
			Files.createDirectories(file.getParent());
			Files.writeString(file, config.toString());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
