package dev.agentsmc.agent;

import com.mojang.authlib.GameProfile;
import dev.agentsmc.AgentsMc;
import dev.agentsmc.mcp.ToolException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Spawns, tracks and removes agent bodies. All methods must be called on the server thread. */
public final class AgentManager {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

	private final MinecraftServer server;
	private final Map<String, AgentPlayer> agents = new LinkedHashMap<>();
	private final Map<String, AgentState> state = new LinkedHashMap<>();

	public AgentManager(MinecraftServer server) {
		this.server = server;
	}

	public static boolean isAgent(Player player) {
		return player instanceof AgentPlayer;
	}

	public AgentPlayer spawn(String name, ServerLevel level, Vec3 pos, float yaw, GameType mode) {
		return spawn(name, level, pos, yaw, mode, null);
	}

	/**
	 * @param diedAs the body that just died, when respawning: like vanilla, the new body copies only
	 *               what survives death (per keepInventory) instead of reloading the dead body's save
	 */
	private AgentPlayer spawn(String name, ServerLevel level, Vec3 pos, float yaw, GameType mode, AgentPlayer diedAs) {
		if (!NAME.matcher(name).matches()) {
			throw new ToolException("Agent names must be 3-16 characters of letters, digits and underscores");
		}
		if (agents.containsKey(key(name))) throw new ToolException("Agent " + name + " is already spawned");
		if (server.getPlayerList().getPlayerByName(name) != null) {
			throw new ToolException("A player named " + name + " is already online");
		}

		GameProfile profile = UUIDUtil.createOfflineProfile(name);
		AgentPlayer agent = new AgentPlayer(server, level, profile, this);
		if (diedAs != null) {
			agent.restoreFrom(diedAs, false);
		} else {
			// Restore inventory, health, XP etc. from a previous session, as vanilla's PrepareSpawnTask does.
			try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(agent.problemPath(), AgentsMc.LOGGER)) {
				server.getPlayerList().loadPlayerData(new NameAndId(profile))
						.ifPresent(tag -> agent.load(TagValueInput.create(reporter, server.registryAccess(), tag)));
			}
		}
		// The save may be from a body that just died mid-fall or on fire; start clean.
		agent.resetFallDistance();
		agent.clearFire();
		agent.setDeltaMovement(Vec3.ZERO);
		if (agent.getHealth() <= 0) agent.setHealth(agent.getMaxHealth());
		// Leaving the End for the first time shows the credits and waits for the client to dismiss
		// them, with the player removed from the world meanwhile. An agent has no client, so skip them.
		agent.seenCredits = true;
		agent.snapTo(pos, yaw, 0);
		FakeConnection connection = new FakeConnection(reason -> server.execute(() -> onKicked(agent, reason.getString())));
		server.getPlayerList().placeNewPlayer(connection, agent, new CommonListenerCookie(profile, 0, agent.clientInformation(), false));
		// A real client sends this once it has rendered the world; until then the player is frozen and invulnerable.
		agent.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		agent.setGameMode(mode);
		agents.put(key(name), agent);
		state.computeIfAbsent(key(name), k -> new AgentState());
		return agent;
	}

	public void despawn(String name, String reason) {
		AgentPlayer agent = agents.remove(key(name));
		if (agent == null) throw new ToolException("No agent named " + name);
		disconnect(agent, reason);
	}

	/** A human with this name just joined: remove any agent using it. */
	public void yieldName(String name) {
		AgentPlayer agent = agents.remove(key(name));
		if (agent != null) disconnect(agent, "A player named " + name + " joined");
	}

	/** /kick, a ban, or a human logging in with the same name. Runs at most once per body. */
	private void onKicked(AgentPlayer agent, String reason) {
		String key = key(agent.getGameProfile().name());
		if (agents.get(key) != agent) return;
		agents.remove(key);
		disconnect(agent, reason);
	}

	private static void disconnect(AgentPlayer agent, String reason) {
		agent.connection.onDisconnect(new DisconnectionDetails(Component.literal(reason)));
		// Fabric's DISCONNECT event doesn't fire for fake connections, so log it here.
		AgentsMc.events().add("leave", agent.getName().getString(), agent.getName().getString() + " (agent) left: " + reason);
	}

	/** Called when the server stops; vanilla saves and removes the players itself. */
	public void clear() {
		agents.clear();
		state.clear();
	}

	void onDeath(AgentPlayer agent) {
		String name = agent.getGameProfile().name();
		GameType mode = agent.gameMode();
		// Dying within seconds of spawning means the spawn point itself is lethal; don't loop.
		boolean respawn = agent.tickCount > 100;
		// No actor, so the agent itself sees this in its own feed.
		AgentsMc.events().add("agent_died", null, name + " died" + (respawn ? " and respawned at world spawn" : " right after spawning and was despawned"));
		// Respawn as a fresh body at world spawn once the death has been fully processed.
		server.execute(() -> {
			if (agents.get(key(name)) != agent) return;
			agents.remove(key(name));
			disconnect(agent, "Died");
			if (!respawn) return;
			ServerLevel overworld = server.overworld();
			BlockPos spawn = overworld.getRespawnData().pos();
			spawn(name, overworld, Vec3.atBottomCenterOf(spawn), 0, mode, agent);
		});
	}

	/** Looks up a spawned agent, failing with a message the model can act on. */
	public AgentPlayer get(String name) {
		AgentPlayer agent = agents.get(key(name));
		if (agent == null) {
			throw new ToolException("No agent named '" + name + "' is spawned. Call spawn_agent first (or list_agents).");
		}
		return agent;
	}

	public AgentState state(String name) {
		get(name);
		return state.get(key(name));
	}

	public Collection<AgentPlayer> all() {
		return agents.values();
	}

	/** Human (non-agent) players currently online. */
	public List<ServerPlayer> humans() {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (!isAgent(p)) out.add(p);
		return out;
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
