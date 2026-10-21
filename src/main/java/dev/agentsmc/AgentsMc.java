package dev.agentsmc;

import dev.agentsmc.agent.AgentManager;
import dev.agentsmc.mcp.McpServer;
import dev.agentsmc.mcp.ToolRegistry;
import dev.agentsmc.perception.BlockCodec;
import dev.agentsmc.perception.EventLog;
import dev.agentsmc.tools.AgentTools;
import dev.agentsmc.tools.BuildTools;
import dev.agentsmc.tools.PerceptionTools;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentsMc implements ModInitializer {
	public static final String MOD_ID = "agentsmc";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String INSTRUCTIONS = """
			Agents MC lets you play in a live Minecraft world as a player-shaped agent body, alongside humans.
			Start with spawn_agent (or list_agents to reuse a body), then observe to orient yourself.
			Perception is text: get_blocks returns a palette plus one character grid per y-level, get_surface \
			returns a top-down height/top-block map, find_blocks and get_entities answer targeted questions, and \
			get_events reports chat and block changes made by others since your last check.
			Coordinates are absolute world block coordinates: +x is east, +z is south, +y is up.
			Build with set_blocks, fill, or build_layers (which takes the same layered grid format get_blocks returns), \
			and use undo to revert your last build. Talk to humans with say; they talk to you in chat (see get_events).""";

	private static volatile Session session;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(AgentsMc::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());

		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
				events().add("chat", sender.getName().getString(), sender.getName().getString() + ": " + message.signedContent()));
		// /say, /me and the like, from players or the console.
		ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) ->
				events().add("chat", source.getTextName(), source.getTextName() + ": " + message.signedContent()));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				events().add("join", handler.player.getName().getString(), describe(handler.player) + " joined"));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			// Agents log their own leave in AgentManager.
			if (!AgentManager.isAgent(handler.player)) events().add("leave", handler.player.getName().getString(), describe(handler.player) + " left");
		});
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (!AgentManager.isAgent(player)) {
				events().add("block_broken", player.getName().getString(), player.getName().getString() + " broke " + BlockCodec.id(state) + " at " + xyz(pos));
			}
		});
	}

	private static void start(MinecraftServer server) {
		Config config = Config.load(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json"));
		Session s = new Session(server, config, new AgentManager(server), new EventLog(server), new ToolRegistry());
		AgentTools.register(s);
		PerceptionTools.register(s);
		BuildTools.register(s);
		try {
			s.mcp = new McpServer(config.host, config.port, config.token, s.tools, INSTRUCTIONS);
			s.mcp.start();
			LOGGER.info("Agents MC: MCP server listening on http://{}:{}/mcp ({} tools)", config.host, s.mcp.port(), s.tools.all().size());
		} catch (Exception e) {
			LOGGER.error("Agents MC: could not start MCP server on {}:{}", config.host, config.port, e);
		}
		session = s;
	}

	private static void stop() {
		Session s = session;
		session = null;
		if (s == null) return;
		if (s.mcp != null) s.mcp.stop();
		s.agents.clear();
	}

	/** The event log for the running server, or a no-op one if none is running. */
	public static EventLog events() {
		Session s = session;
		return s != null ? s.events : EventLog.DISCARD;
	}

	private static String describe(ServerPlayer player) {
		return player.getName().getString() + (AgentManager.isAgent(player) ? " (agent)" : "");
	}

	public static String xyz(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}
}
