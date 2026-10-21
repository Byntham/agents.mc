package dev.agentsmc.tools;

import com.google.gson.JsonElement;
import dev.agentsmc.Session;
import dev.agentsmc.agent.AgentPlayer;
import dev.agentsmc.agent.AgentState;
import dev.agentsmc.mcp.Args;
import dev.agentsmc.mcp.Schema;
import dev.agentsmc.mcp.ToolException;
import dev.agentsmc.perception.BlockCodec;
import dev.agentsmc.perception.EventLog;
import dev.agentsmc.perception.Views;
import dev.agentsmc.util.OnServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only tools that describe the world as text. */
public final class PerceptionTools {
	private PerceptionTools() {
	}

	public static void register(Session s) {
		s.tools.register("observe",
				"One-call situational awareness for an agent: its status, a top-down surface map around it (you are '@', other players '*'), "
						+ "nearby entities, and chat/block events since its last observe or get_events. Call this first and whenever you lose track. "
						+ "Player positions are a snapshot; call find_player when you need where someone is now.",
				Schema.of().agent(true).integer("radius", "Map and entity radius in blocks (default 12, max 32)", false),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					int radius = Math.min(args.integer("radius", 12), 32);
					return "== Status ==\n" + Views.status(a)
							+ "\n== Surroundings ==\n" + Views.surface(a.level(), a.getBlockX(), a.getBlockZ(), radius, BlockCodec.Detail.NONE, a, true, false)
							+ "\n== Entities ==\n" + Views.entities(a.level(), a.position(), radius * 1.5, 20, a)
							+ "\n== Events ==\n" + drainEvents(s, args.str("agent"), 30);
				}));

		s.tools.register("get_status", "An agent's position, facing, health, gamemode, what it's looking at and standing on, time, weather and inventory.",
				Schema.of().agent(true),
				args -> OnServer.call(s.server, () -> Views.status(s.agents.get(args.str("agent")))));

		s.tools.register("get_blocks",
				"Exact 3D blocks in a box (max 32768 blocks, e.g. 32x32x32), as a palette plus one character grid per y level. "
						+ "Rows are z (north to south), columns are x (west to east). Identical consecutive layers and all-air layers are collapsed. "
						+ "The palette strings are valid input for build_layers/set_blocks/fill. Directional properties (facing, axis, half...) are always shown. "
						+ "To compare against a build, pass the palette you built with: matching blocks then print with your characters.",
				Schema.of().agent(false)
						.integer("x1", "Corner 1 x", true).integer("y1", "Corner 1 y", true).integer("z1", "Corner 1 z", true)
						.integer("x2", "Corner 2 x", true).integer("y2", "Corner 2 y", true).integer("z2", "Corner 2 z", true)
						.enumStr("properties", "Block state detail: auto (directional plus non-default properties; the default), full, or none (ids only)", false, "auto", "full", "none")
						.raw("palette", BuildTools.paletteSchema("Optional characters to use for known blocks, same format as build_layers. "
								+ "A block matches an entry if it is that block type and has every property the entry names."), false),
				args -> {
					Map<Character, BlockCodec.Spec> hints = new LinkedHashMap<>();
					if (args.has("palette")) {
						for (Map.Entry<String, JsonElement> e : args.object("palette").entrySet()) {
							if (e.getKey().length() != 1 || e.getKey().equals(" ")) throw new ToolException("Palette keys must be single non-space characters");
							if (e.getKey().equals(".")) continue; // always air
							hints.put(e.getKey().charAt(0), BlockCodec.parseSpec(e.getValue().getAsString()));
						}
					}
					return OnServer.call(s.server, () -> Views.blocks(level(s, args),
							new BlockPos(args.integer("x1"), args.integer("y1"), args.integer("z1")),
							new BlockPos(args.integer("x2"), args.integer("y2"), args.integer("z2")),
							BlockCodec.Detail.of(args.str("properties", "auto")), hints));
				});

		s.tools.register("get_surface",
				"Top-down map of an area: the topmost solid/liquid block of each column and its height. Leaves are skipped by default, so you see the ground under trees. "
						+ "Good for terrain, finding flat ground and planning builds. "
						+ "Centre: x/z if given, else around_player, else the agent.",
				Schema.of().agent(false)
						.integer("x", "Centre x", false).integer("z", "Centre z", false)
						.str("around_player", "Centre on this player's current position", false)
						.integer("radius", "Half-width in blocks (default 16, max 48)", false)
						.bool("heights", "Include the height grid (default true)", false)
						.bool("include_leaves", "Count leaves as the top block, showing tree canopies instead of the ground (default false)", false),
				args -> OnServer.call(s.server, () -> {
					Center c = center(s, args, args.has("x") && args.has("z")
							? new Vec3(args.integer("x"), 0, args.integer("z")) : null);
					return Views.surface(c.level(), (int) Math.floor(c.pos().x), (int) Math.floor(c.pos().z), args.integer("radius", 16),
							BlockCodec.Detail.NONE, c.agent(), args.bool("heights", true), args.bool("include_leaves", false));
				}));

		s.tools.register("get_entities",
				"Mobs, players, dropped items and other entities near a point, nearest first. Centre: x/y/z if given, else around_player, else the agent.",
				Schema.of().agent(false)
						.number("x", "Centre x", false).number("y", "Centre y", false).number("z", "Centre z", false)
						.str("around_player", "Centre on this player's current position", false)
						.number("radius", "Radius (default 24, max 64)", false).integer("limit", "Max results (default 40)", false),
				args -> OnServer.call(s.server, () -> {
					Center c = center(s, args, args.has("x") && args.has("y") && args.has("z")
							? new Vec3(args.number("x"), args.number("y"), args.number("z")) : null);
					return Views.entities(c.level(), c.pos(), args.number("radius", 24), args.integer("limit", 40), c.agent());
				}));

		s.tools.register("find_blocks",
				"Find the nearest blocks of a type within a radius (default 32, max 64), e.g. \"oak_log\", \"chest\", \"water\", "
						+ "a tag like \"#logs\" or \"#minecraft:base_stone_overworld\", or an exact state like \"oak_door[half=lower]\". "
						+ "Centre: x/y/z if given, else around_player, else the agent.",
				Schema.of().agent(false).str("block", "Block id, #tag or block state", true)
						.integer("x", "Centre x", false).integer("y", "Centre y", false).integer("z", "Centre z", false)
						.str("around_player", "Centre on this player's current position", false)
						.integer("radius", "Search radius (default 32)", false).integer("limit", "Max results (default 20)", false),
				args -> OnServer.call(s.server, () -> {
					Center c = center(s, args, args.has("x") && args.has("y") && args.has("z")
							? new Vec3(args.integer("x"), args.integer("y"), args.integer("z")) : null);
					return Views.findBlocks(c.level(), BlockPos.containing(c.pos()), args.integer("radius", 32), args.str("block"), args.integer("limit", 20));
				}));

		s.tools.register("inspect_block", "Full detail on one block: every state property, light level, and contents of chests/furnaces/etc. or other block-entity data such as sign text.",
				Schema.of().agent(false).xyz("Target", true),
				args -> OnServer.call(s.server, () -> Views.inspect(level(s, args), new BlockPos(args.integer("x"), args.integer("y"), args.integer("z")))));

		s.tools.register("get_events",
				"Chat messages, joins/leaves, builds and blocks placed or broken by others, oldest first. With an agent and no 'since', "
						+ "returns only events that agent hasn't seen yet, excluding its own actions (observe shares the same cursor).",
				Schema.of().agent(false)
						.integer("since", "Return events after this seq number instead of using the agent's cursor", false)
						.integer("limit", "Max events (default 50)", false),
				args -> OnServer.call(s.server, () -> {
					int limit = args.integer("limit", 50);
					if (args.has("since") || !args.has("agent")) {
						return format(s.events.since(args.integer("since", 0), null, limit));
					}
					return drainEvents(s, args.str("agent"), limit);
				}));
	}

	private static String drainEvents(Session s, String agent, int limit) {
		AgentState state = s.agents.state(agent);
		EventLog.Page page = s.events.since(state.eventCursor, agent, limit);
		state.eventCursor = page.cursor();
		return format(page);
	}

	private static String format(EventLog.Page page) {
		StringBuilder sb = new StringBuilder();
		if (page.lost()) sb.append("(Some older events were dropped from the buffer before you read them.)\n");
		if (page.events().isEmpty()) sb.append("No new events.\n");
		for (EventLog.Event e : page.events()) {
			sb.append(String.format(Locale.ROOT, "#%d [tick %d] %s: %s\n", e.seq(), e.tick(), e.type(), e.text()));
		}
		if (page.remaining() > 0) sb.append(page.remaining()).append(" more unread; call get_events to read them.\n");
		return sb.toString();
	}

	/** Where a query is centred and in which dimension; {@code agent} may be null. */
	private record Center(ServerLevel level, Vec3 pos, AgentPlayer agent) {
	}

	/** Explicit coordinates win, then around_player (resolved now, since players move), then the agent. */
	private static Center center(Session s, Args args, Vec3 explicit) {
		AgentPlayer a = args.has("agent") ? s.agents.get(args.str("agent")) : null;
		if (explicit != null) return new Center(level(s, args), explicit, a);
		if (args.has("around_player")) {
			ServerPlayer p = Targets.player(s, args.str("around_player"));
			return new Center(p.level(), p.position(), a);
		}
		if (a == null) throw new ToolException("Pass coordinates, around_player, or an agent to centre on");
		return new Center(a.level(), a.position(), a);
	}

	/** The agent's dimension, or the overworld when no agent is given. */
	static ServerLevel level(Session s, Args args) {
		return args.has("agent") ? s.agents.get(args.str("agent")).level() : s.server.overworld();
	}
}
