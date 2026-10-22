package dev.agentsmc.tools;

import dev.agentsmc.Session;
import dev.agentsmc.agent.AgentPlayer;
import dev.agentsmc.agent.AgentState;
import dev.agentsmc.body.AttackIntent;
import dev.agentsmc.body.Body;
import dev.agentsmc.body.CollectIntent;
import dev.agentsmc.body.FollowIntent;
import dev.agentsmc.body.GotoIntent;
import dev.agentsmc.body.Intent;
import dev.agentsmc.body.MineIntent;
import dev.agentsmc.body.Navigator;
import dev.agentsmc.mcp.Schema;
import dev.agentsmc.mcp.ToolException;
import dev.agentsmc.perception.BlockCodec;
import dev.agentsmc.perception.EventLog;
import dev.agentsmc.perception.Senses;
import dev.agentsmc.perception.Views;
import dev.agentsmc.util.OnServer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Playing in real time: long-running intents (walk, follow, mine, fight, collect) that the body
 * carries out tick by tick, a {@code wait} that blocks until something happens, and the "meanwhile"
 * delta appended to every tool result so the model always acts on fresh state.
 */
public final class BodyTools {
	/** Tools whose result already is the current state, so no delta is appended. */
	private static final Set<String> NO_DELTA = Set.of("wait", "observe", "get_events", "get_status", "spawn_agent", "despawn_agent", "list_agents");
	private static final int MAX_WAIT_S = 240;

	private BodyTools() {
	}

	public static void register(Session s) {
		s.tools.register("goto",
				"Walk to a point or to a player, with pathfinding over the terrain (jumps, drops, swimming, doors). Returns at once with an intent id; "
						+ "the body keeps walking while you do other things. You get a '<id> done/failed' event when it ends, so call wait to let it happen.",
				Schema.of().agent(true)
						.number("x", "Target x", false).number("y", "Target y (the block your feet should end up in)", false).number("z", "Target z", false)
						.str("near_player", "Walk to this player instead of coordinates", false)
						.number("distance", "With near_player: stop this many blocks away (default 2)", false)
						.number("tolerance", "How close counts as arrived, in blocks (default 1.5)", false),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					Vec3 target;
					double tolerance = args.number("tolerance", 1.5);
					if (args.has("near_player")) {
						ServerPlayer p = Targets.player(s, args.str("near_player"));
						if (p.level() != a.level()) throw new ToolException(p.getName().getString() + " is in another dimension");
						target = p.position();
						tolerance = Math.max(tolerance, args.number("distance", 2));
					} else if (args.has("x") && args.has("y") && args.has("z")) {
						target = new Vec3(args.number("x"), args.number("y"), args.number("z"));
					} else {
						throw new ToolException("Pass x, y and z, or near_player");
					}
					if (a.getAbilities().flying) {
						a.getAbilities().flying = false;
						a.onUpdateAbilities();
					}
					GotoIntent intent = a.body().start(new GotoIntent(a.body(), target, tolerance));
					Navigator.State first = intent.planNow(a.body());
					String plan = switch (first) {
						case ARRIVED -> "already there";
						case UNREACHABLE -> "no path found from here; the body will try to get closer, then report";
						default -> intent.plan();
					};
					return intent.tag() + " started: " + plan + ".";
				}));

		s.tools.register("follow",
				"Follow a player around, keeping a few blocks behind them, until cancelled. Returns at once with an intent id. "
						+ "You'll get events if they get far ahead or can't be reached.",
				Schema.of().agent(true).str("player", "Player to follow", true)
						.number("distance", "How close to stay, in blocks (default 3)", false),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					ServerPlayer p = Targets.player(s, args.str("player"));
					if (p == a) throw new ToolException("You can't follow yourself");
					if (a.getAbilities().flying) {
						a.getAbilities().flying = false;
						a.onUpdateAbilities();
					}
					FollowIntent intent = a.body().start(new FollowIntent(a.body(), p.getName().getString(), args.number("distance", 3)));
					return intent.tag() + " started: following " + p.getName().getString() + ", currently " + Senses.relative(a, p.position()) + ".";
				}));

		s.tools.register("mine",
				"Break blocks the way a survival player does: walk within reach, pick the best tool in the inventory, hold the button for the block's "
						+ "break time, pick up the drops. Either name a block type to mine the nearest ones (block + count), or give one position (x, y, z). "
						+ "Returns at once with an intent id; a 'mine#N done' event reports what was mined and picked up.",
				Schema.of().agent(true)
						.str("block", "Block id or #tag to look for, e.g. oak_log, #logs, iron_ore", false)
						.integer("count", "With block: how many to mine (default 1, max 64)", false)
						.integer("radius", "With block: search radius (default 16, max 32)", false)
						.integer("x", "A single block to mine", false).integer("y", "", false).integer("z", "", false),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					List<BlockPos> targets;
					String what;
					if (args.has("x") && args.has("y") && args.has("z")) {
						BlockPos p = new BlockPos(args.integer("x"), args.integer("y"), args.integer("z"));
						BlockState st = a.level().getBlockState(p);
						if (st.isAir()) throw new ToolException("There's only air at " + p.toShortString());
						targets = List.of(p);
						what = BlockCodec.id(st, BlockCodec.Detail.NONE);
					} else if (args.has("block")) {
						int count = Math.max(1, Math.min(64, args.integer("count", 1)));
						int radius = Math.max(1, Math.min(32, args.integer("radius", 16)));
						what = args.str("block");
						targets = Views.findBlockPositions(a.level(), a.blockPosition(), radius, what, count);
						if (targets.isEmpty()) throw new ToolException("No " + what + " within " + radius + " blocks. Try find_blocks with a bigger radius, or walk somewhere else.");
						what = BlockCodec.shortId(what.startsWith("#") ? what : what);
					} else {
						throw new ToolException("Pass block (and count), or x, y, z");
					}
					MineIntent intent = a.body().start(new MineIntent(a.body(), targets, what));
					BlockPos first = targets.getFirst();
					return String.format(Locale.ROOT, "%s started: %d block%s of %s, nearest at %d %d %d (%s).", intent.tag(), targets.size(),
							targets.size() == 1 ? "" : "s", what, first.getX(), first.getY(), first.getZ(), Senses.relative(a, Vec3.atCenterOf(first)));
				}));

		s.tools.register("attack",
				"Fight an entity: chase it, hit it with the best weapon in the inventory whenever the attack cooldown allows, until it dies or gets away. "
						+ "Returns at once with an intent id.",
				Schema.of().agent(true).str("target", "A player name, an entity type (e.g. zombie: the nearest one), or an entity id from get_entities", true),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					Entity target = Targets.entity(s, a, args.str("target"));
					if (target == a) throw new ToolException("You can't attack yourself");
					AttackIntent intent = a.body().start(new AttackIntent(a.body(), target, false));
					return intent.tag() + " started: " + Senses.name(target) + " is " + Senses.relative(a, target.position()) + ".";
				}));

		s.tools.register("collect",
				"Walk over to dropped items nearby and pick them up. Returns at once with an intent id.",
				Schema.of().agent(true).str("item", "Only items whose id contains this, e.g. oak_log (default: anything)", false)
						.number("radius", "How far to look, in blocks (default 8, max 32)", false),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					String item = args.str("item", "").trim().toLowerCase(Locale.ROOT);
					double radius = Math.max(1, Math.min(32, args.number("radius", 8)));
					java.util.function.Predicate<ItemEntity> match = item.isEmpty() ? e -> true : e -> Views.stack(e.getItem().copyWithCount(1)).contains(item);
					List<ItemEntity> items = a.level().getEntitiesOfClass(ItemEntity.class, a.getBoundingBox().inflate(radius), match);
					if (items.isEmpty()) throw new ToolException("No " + (item.isEmpty() ? "dropped items" : item + " items") + " within " + (int) radius + " blocks.");
					CollectIntent intent = a.body().start(new CollectIntent(a.body(), match, item.isEmpty() ? "items" : item, radius));
					return intent.tag() + " started: " + items.size() + " item stack" + (items.size() == 1 ? "" : "s") + " nearby.";
				}));

		s.tools.register("cancel", "Stop the body's current intent (goto, follow, mine, attack, collect). Without an id, stops everything.",
				Schema.of().agent(true).integer("intent", "Intent id to cancel (the number after # in goto#3)", false),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					if (args.has("intent")) {
						int id = args.integer("intent");
						return a.body().cancel(id, "cancelled") ? "Cancelled #" + id + "." : "No running intent #" + id + ". Doing: " + a.body().doing();
					}
					List<Intent> cancelled = a.body().cancelAll("cancelled");
					if (cancelled.isEmpty()) return "Nothing was running.";
					List<String> tags = new ArrayList<>();
					for (Intent i : cancelled) tags.add(i.tag());
					return "Cancelled " + String.join(", ", tags) + ".";
				}));

		s.tools.register("wait",
				"Let time pass while your body carries out an intent or while you have nothing to do. Blocks until something happens to you "
						+ "(an intent finishes or fails, you take damage, a mob closes in, a player talks to you) or the time runs out, then returns "
						+ "your status and what happened. Costs nothing while waiting. Prefer this over polling observe.",
				Schema.of().agent(true)
						.integer("seconds", "Max seconds to wait (default 30, max " + MAX_WAIT_S + ")", false)
						.enumStr("until", "any: return on anything notable (default). intent: only when an intent finishes or fails.", false, "any", "intent")
						.integer("min_salience", "Ignore events below this: 0 everything, 1 notable (default), 2 important, 3 urgent", false),
				args -> {
					String agent = args.str("agent");
					int seconds = Math.max(1, Math.min(MAX_WAIT_S, args.integer("seconds", 30)));
					boolean intentsOnly = "intent".equals(args.str("until", "any"));
					int min = Math.max(0, Math.min(3, args.integer("min_salience", 1)));
					boolean managed = managed(s, agent);
					// Already have something unread that qualifies? Don't wait at all.
					EventLog.Event pending = OnServer.call(s.server, () -> {
						AgentState st = s.agents.state(agent);
						for (EventLog.Event e : s.events.since(st.eventCursor, agent, 100, !managed).events()) {
							if (e.salience() >= min && (!intentsOnly || e.type().equals("intent"))) return e;
						}
						return null;
					});
					long start = System.currentTimeMillis();
					EventLog.Event woke = pending;
					if (woke == null) {
						try {
							woke = s.waiters.await(agent, min, intentsOnly, !managed, seconds * 1000L);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							throw new ToolException("Interrupted while waiting");
						}
					}
					double waited = (System.currentTimeMillis() - start) / 1000.0;
					String reason = woke == null ? String.format(Locale.ROOT, "Nothing notable in %.0fs.", waited)
							: String.format(Locale.ROOT, "Waited %.1fs. Woke for: %s", waited, woke.text());
					EventLog.Event finalWoke = woke;
					return OnServer.call(s.server, () -> reason + "\n" + delta(s, agent, finalWoke == null ? 0 : finalWoke.seq()));
				});

		s.tools.register("set_reflexes",
				"Turn the body's reflexes on or off: auto_eat (eats from the inventory when food is low), fight_back (hits back at a mob that hit you), "
						+ "flee_creepers (backs away from a creeper about to explode). All on by default. Reflexes report what they did as events.",
				Schema.of().agent(true).bool("auto_eat", "", false).bool("fight_back", "", false).bool("flee_creepers", "", false),
				args -> OnServer.call(s.server, () -> {
					Body b = s.agents.get(args.str("agent")).body();
					if (args.has("auto_eat")) b.reflexes.autoEat = args.bool("auto_eat", true);
					if (args.has("fight_back")) b.reflexes.fightBack = args.bool("fight_back", true);
					if (args.has("flee_creepers")) b.reflexes.fleeCreepers = args.bool("flee_creepers", true);
					return "Reflexes: " + b.reflexes.describe();
				}));

		s.tools.register("hold",
				"Put an item from the inventory in the main hand, e.g. a sword before a fight or a torch to place. "
						+ "Matches by item id (oak_planks) or part of it (sword). 'nothing' empties the hand.",
				Schema.of().agent(true).str("item", "Item id or part of one, or 'nothing'", true),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					String item = args.str("item").trim().toLowerCase(Locale.ROOT);
					if (item.equals("nothing") || item.equals("hand") || item.equals("empty")) {
						var inv = a.getInventory();
						for (int i = 0; i < 9; i++) {
							if (inv.getItem(i).isEmpty()) {
								a.body().hold(i);
								return "Hands empty.";
							}
						}
						throw new ToolException("Every hotbar slot is full; drop something first.");
					}
					if (!a.body().hold(stack -> matches(stack, item))) throw new ToolException("No " + item + " in the inventory. " + inventory(a));
					return "Holding " + Views.stack(a.getMainHandItem()) + ".";
				}));

		s.tools.register("eat", "Eat something from the inventory now (auto_eat does this on its own when food is low).",
				Schema.of().agent(true).str("item", "Which food (default: whatever fills the gap best)", false),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					if (a.getFoodData().getFoodLevel() >= 20) return "Not hungry (food 20/20).";
					String item = args.str("item", "").trim().toLowerCase(Locale.ROOT);
					boolean held = item.isEmpty()
							? a.body().hold(st -> st.has(net.minecraft.core.component.DataComponents.FOOD))
							: a.body().hold(st -> st.has(net.minecraft.core.component.DataComponents.FOOD) && matches(st, item));
					if (!held) throw new ToolException("No food in the inventory. " + inventory(a));
					ItemStack stack = a.getMainHandItem();
					InteractionResult r = a.gameMode.useItem(a, a.level(), stack, net.minecraft.world.InteractionHand.MAIN_HAND);
					if (!a.isUsingItem()) throw new ToolException("Couldn't start eating " + Views.stack(stack) + " (" + r + ")");
					return "Eating " + Views.stack(stack.copyWithCount(1)) + "; takes about 2 seconds.";
				}));

		s.tools.register("drop", "Drop items from the inventory on the ground in front of you.",
				Schema.of().agent(true).str("item", "Item id or part of one", true).bool("all", "Drop the whole stack (default: one item)", false),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					String item = args.str("item").trim().toLowerCase(Locale.ROOT);
					if (!a.body().hold(stack -> matches(stack, item))) throw new ToolException("No " + item + " in the inventory. " + inventory(a));
					String what = Views.stack(a.getMainHandItem());
					a.drop(args.bool("all", false));
					return "Dropped " + (args.bool("all", false) ? what : Views.stack(a.getMainHandItem().copyWithCount(1))) + ".";
				}));

		s.tools.register("place_block",
				"Place one block from the inventory at a position, like a survival player: you must be within reach (about 4.5 blocks) and the spot must "
						+ "touch an existing block. Use goto first if it's far. For big creative builds use set_blocks or build_layers instead.",
				Schema.of().agent(true).str("item", "Block item to place, e.g. oak_planks, torch", true).xyz("Target", true),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					Body body = a.body();
					ServerLevel level = a.level();
					BlockPos pos = new BlockPos(args.integer("x"), args.integer("y"), args.integer("z"));
					BlockState there = level.getBlockState(pos);
					if (!there.canBeReplaced()) throw new ToolException(pos.toShortString() + " already has " + BlockCodec.id(there));
					if (!a.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos)) || a.isCreative()) {
						// fine
					} else {
						throw new ToolException("You're standing there. Step aside first.");
					}
					BlockPos against = null;
					double best = Double.MAX_VALUE;
					for (Direction d : Direction.values()) {
						BlockPos n = pos.relative(d);
						if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) continue;
						double dist = Vec3.atCenterOf(n).distanceToSqr(a.getEyePosition()) - (d == Direction.DOWN ? 0.5 : 0);
						if (dist < best) {
							best = dist;
							against = n;
						}
					}
					if (against == null) throw new ToolException("Nothing to place against: no solid block touches " + pos.toShortString());
					if (!body.inReach(against)) {
						throw new ToolException(String.format(Locale.ROOT, "Out of reach: %s is %s. goto a spot within 4 blocks first.", pos.toShortString(), Senses.relative(a, Vec3.atCenterOf(pos))));
					}
					String item = args.str("item").trim().toLowerCase(Locale.ROOT);
					if (!body.hold(stack -> matches(stack, item))) throw new ToolException("No " + item + " in the inventory. " + inventory(a));
					BlockHitResult hit = body.placementHit(pos, against);
					a.lookAt(EntityAnchorArgument.Anchor.EYES, hit.getLocation());
					InteractionResult r = body.useOn(hit);
					BlockState now = level.getBlockState(pos);
					if (now.isAir() || now == there) throw new ToolException("Couldn't place " + item + " at " + pos.toShortString() + " (" + r + ").");
					return "Placed " + BlockCodec.id(now) + " at " + AgentsMcXyz(pos) + ".";
				}));

		s.tools.register("use_block",
				"Right-click a block within reach with what you're holding: open or close a door, press a button, flip a lever, ring a bell, use a crafting table...",
				Schema.of().agent(true).xyz("Target", true),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					Body body = a.body();
					BlockPos pos = new BlockPos(args.integer("x"), args.integer("y"), args.integer("z"));
					if (!body.inReach(pos)) throw new ToolException("Out of reach: " + pos.toShortString() + " is " + Senses.relative(a, Vec3.atCenterOf(pos)) + ". goto first.");
					BlockState before = a.level().getBlockState(pos);
					if (before.isAir()) throw new ToolException("Nothing at " + pos.toShortString());
					a.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
					BlockHitResult hit = body.sight(pos);
					if (hit == null) {
						Vec3 eye = a.getEyePosition(), c = Vec3.atCenterOf(pos);
						Direction face = Direction.getApproximateNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
						hit = body.placementHit(pos.relative(face), pos);
					}
					InteractionResult r = body.useOn(hit);
					BlockState after = a.level().getBlockState(pos);
					return "Used " + BlockCodec.id(before) + " (" + r + ")" + (after != before ? "; it's now " + BlockCodec.id(after) : "") + ".";
				}));

		s.tools.register("sweep",
				"A quick look around without a picture: for each direction relative to where you face, what the nearest thing is and how far, "
						+ "which entities are that way, and whether there's a drop ahead. Cheaper than screenshot or get_blocks for deciding where to go.",
				Schema.of().agent(true),
				args -> OnServer.call(s.server, () -> s.agents.get(args.str("agent")).body().senses.sweep()));
	}

	// ---- the meanwhile delta --------------------------------------------------------------------

	/**
	 * What to append to a tool's result: the agent's status line and the events since its last look.
	 * Null when the tool already returned that, or the agent has no body.
	 */
	public static String meanwhile(Session s, String tool, String agent) {
		if (NO_DELTA.contains(tool)) return null;
		return OnServer.call(s.server, () -> {
			if (s.agents.find(agent) == null) return null;
			return "--- meanwhile ---\n" + delta(s, agent, 0);
		});
	}

	/** Status line plus unread events, draining the agent's cursor. Server thread. */
	static String delta(Session s, String agent, long skipSeq) {
		AgentPlayer a = s.agents.get(agent);
		AgentState st = s.agents.state(agent);
		EventLog.Page page = s.events.since(st.eventCursor, agent, 15, !managed(s, agent));
		st.eventCursor = page.cursor();
		StringBuilder sb = new StringBuilder(a.body().senses.statusLine()).append('\n');
		sb.append(Views.events(page, s.server.getTickCount(), skipSeq));
		return sb.toString();
	}

	/** Agents the mod runs only read chat from players allowed to direct them. */
	static boolean managed(Session s, String agent) {
		return s.brains != null && s.brains.find(agent) != null;
	}

	// ---- helpers --------------------------------------------------------------------------------

	private static boolean matches(ItemStack stack, String query) {
		String id = Views.stack(stack.copyWithCount(1));
		return id.equals(query) || id.contains(query);
	}

	private static String inventory(AgentPlayer a) {
		List<String> items = new ArrayList<>();
		var inv = a.getInventory().getNonEquipmentItems();
		for (ItemStack st : inv) if (!st.isEmpty()) items.add(Views.stack(st));
		return items.isEmpty() ? "The inventory is empty." : "Inventory: " + String.join(", ", items) + ".";
	}

	private static String AgentsMcXyz(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}
}
