package dev.agentsmc.brain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.agentsmc.AgentsMc;
import dev.agentsmc.Config;
import dev.agentsmc.Session;
import dev.agentsmc.agent.AgentManager;
import dev.agentsmc.agent.AgentPlayer;
import dev.agentsmc.mcp.ToolException;
import dev.agentsmc.mixin.CommandSourceStackAccessor;
import dev.agentsmc.tools.Targets;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The agents the mod runs itself: one {@link AgentBrain} per body, a roster saved with the world so
 * they come back when it reopens, chat routing, and who may use them. Methods are for the server
 * thread unless noted.
 */
public final class Brains {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9_]{3,16})\\b");
	/** An idle Claude Code process is closed after this long; the next message resumes the conversation. */
	private static final long IDLE_CLOSE_MS = 10 * 60_000;

	/** One saved agent. The position is where the body was when the world last saved. */
	static final class Saved {
		String name;
		String sessionId;
		String dimension = "minecraft:overworld";
		double x, y, z;
		float yaw;
		String gamemode = "creative";
	}

	private final Session s;
	private final Path dir;
	private final Map<String, AgentBrain> brains = new LinkedHashMap<>();
	private final Map<String, Saved> saved = new LinkedHashMap<>();
	/** Bound MCP endpoint key to agent name. Read from HTTP threads. */
	private final Map<String, String> keys = new ConcurrentHashMap<>();

	public Brains(Session s) {
		this.s = s;
		this.dir = s.server.getWorldPath(LevelResource.ROOT).resolve(AgentsMc.MOD_ID);
	}

	// ---- lifecycle ----

	/** Brings back the agents saved with this world, as idle bodies where they last stood. */
	public void restore() {
		deleteStaleMcpConfigs();
		Path file = dir.resolve("agents.json");
		if (!Files.exists(file)) return;
		List<Saved> list;
		try {
			list = GSON.fromJson(Files.readString(file), new TypeToken<List<Saved>>() {
			}.getType());
		} catch (IOException | RuntimeException e) {
			AgentsMc.LOGGER.warn("Agents MC: could not read {}", file, e);
			return;
		}
		if (list == null) return;
		for (Saved a : list) {
			if (a == null || a.name == null) continue;
			saved.put(key(a.name), a);
			AgentBrain brain = new AgentBrain(a.name, this, a.sessionId);
			brains.put(key(a.name), brain);
			loadLog(brain);
			try {
				ServerLevel level = s.server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(a.dimension)));
				if (level == null) level = s.server.overworld();
				spawnBody(a.name, level, new Vec3(a.x, a.y, a.z), a.yaw, GameType.byName(a.gamemode, GameType.CREATIVE));
			} catch (RuntimeException e) {
				AgentsMc.LOGGER.warn("Agents MC: could not bring back agent {}", a.name, e);
			}
		}
		AgentsMc.LOGGER.info("Agents MC: brought back {} agent(s)", list.size());
	}

	/**
	 * MCP configs left behind when the game was killed: each launch deletes its own on exit, but a
	 * crash skips that, and they can hold the bearer token. Runs before any agent launches.
	 */
	private void deleteStaleMcpConfigs() {
		Path agentsDir = dir.resolve("agents");
		if (!Files.isDirectory(agentsDir)) return;
		try (var dirs = Files.newDirectoryStream(agentsDir)) {
			for (Path agentDir : dirs) {
				if (!Files.isDirectory(agentDir)) continue;
				try (var files = Files.newDirectoryStream(agentDir, "mcp-*.json")) {
					for (Path f : files) Files.deleteIfExists(f);
				}
			}
		} catch (IOException e) {
			AgentsMc.LOGGER.warn("Agents MC: could not clean up old MCP configs in {}", agentsDir, e);
		}
	}

	/** World closing: remember where everyone is, then close every Claude Code process. */
	public void shutdown() {
		save();
		for (AgentBrain b : brains.values()) b.shutdown();
		for (AgentBrain b : brains.values()) {
			try {
				if (!b.awaitShutdown(6000)) AgentsMc.LOGGER.warn("Agents MC: {}'s Claude Code didn't close in time", b.name);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	public void tick() {
		int tick = s.server.getTickCount();
		if (tick % 200 == 0) for (AgentBrain b : brains.values()) b.closeIfIdle(IDLE_CLOSE_MS);
		// Keep positions and menu history reasonably fresh in case the game crashes.
		if (tick % 6000 == 0 && !brains.isEmpty()) save();
	}

	// ---- agents ----

	/**
	 * Creates an agent run by the mod, or gives an existing body (spawned over MCP, or after a kick) its
	 * brain back. The body appears in front of {@code near}, or at world spawn.
	 */
	public AgentBrain create(String name, ServerPlayer near) {
		AgentBrain brain = brains.get(key(name));
		AgentPlayer body = body(name);
		if (brain != null && body != null) throw new ToolException(name + " is already here");
		if (body == null) {
			if (near != null) {
				Vec3 pos = Targets.inFrontOf(near, 2);
				body = spawnBody(name, near.level(), pos, Targets.yawToward(pos, near.position()), GameType.CREATIVE);
			} else {
				ServerLevel level = s.server.overworld();
				body = spawnBody(name, level, Vec3.atBottomCenterOf(level.getRespawnData().pos()), 0, GameType.CREATIVE);
			}
		}
		if (brain == null) {
			brain = new AgentBrain(body.getGameProfile().name(), this, null);
			brains.put(key(name), brain);
		}
		save();
		return brain;
	}

	/** Closes the agent's Claude Code, removes its body and forgets it. */
	public void remove(String name) {
		AgentBrain brain = brains.remove(key(name));
		if (brain == null) throw new ToolException("No agent named " + name);
		saved.remove(key(name));
		// Cut the old process off now, not when it exits, so it can't act on a new body with this name.
		// shutdown() marks it closed first; a launch in progress re-checks that after issuing its key.
		brain.shutdown();
		keys.values().removeIf(n -> n.equalsIgnoreCase(brain.name));
		if (body(name) != null) s.agents.despawn(name, "Removed");
		save();
		// The prompt and MCP config are rewritten on every launch, so a new agent with this name only
		// needs the old history gone.
		try {
			Files.deleteIfExists(agentDir(brain.name).resolve("log.json"));
		} catch (IOException e) {
			AgentsMc.LOGGER.debug("Agents MC: could not delete {}'s history", brain.name, e);
		}
	}

	public AgentBrain get(String name) {
		AgentBrain brain = brains.get(key(name));
		if (brain == null) throw new ToolException("No agent named " + name + ". Create one with /agent spawn " + name);
		return brain;
	}

	public AgentBrain find(String name) {
		return name == null ? null : brains.get(key(name));
	}

	public Collection<AgentBrain> all() {
		return brains.values();
	}

	public AgentPlayer body(String name) {
		for (AgentPlayer a : s.agents.all()) if (a.getGameProfile().name().equalsIgnoreCase(name)) return a;
		return null;
	}

	/**
	 * Sends a player's message to an agent. If its body is gone (kicked, or despawned over MCP), it
	 * comes back first.
	 */
	public void send(String from, ServerPlayer near, String via, AgentBrain brain, String text) {
		if (body(brain.name) == null) {
			try {
				create(brain.name, near);
			} catch (ToolException e) {
				brain.log.add(ActivityLog.Kind.ERROR, null, "Couldn't bring the body back: " + e.getMessage());
			}
		}
		brain.send(from, via, text);
	}

	/** Chat from a player: every agent it @mentions gets the message. */
	public void onChat(ServerPlayer sender, String text) {
		if (AgentManager.isAgent(sender)) return;
		Matcher m = MENTION.matcher(text);
		List<AgentBrain> addressed = new ArrayList<>();
		while (m.find()) {
			AgentBrain b = find(m.group(1));
			if (b != null && !addressed.contains(b)) addressed.add(b);
		}
		if (addressed.isEmpty()) return;
		if (!canUse(sender)) {
			sender.sendSystemMessage(Component.literal("Only operators can talk to agents on this server.").withStyle(ChatFormatting.GRAY));
			return;
		}
		for (AgentBrain b : addressed) send(sender.getName().getString(), sender, "in chat", b, text);
	}

	// ---- permissions ----

	/** Agents run on the host's Claude account, so by default only the host and operators may use them. */
	public boolean canUse(ServerPlayer player) {
		// Agents never direct agents: that would let one spawn more of them, each a new Claude process.
		if (AgentManager.isAgent(player)) return false;
		if (everyone()) return true;
		return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || s.server.isSingleplayerOwner(player.nameAndId());
	}

	public boolean everyone() {
		return "everyone".equalsIgnoreCase(s.config.whoCanUseAgents);
	}

	/**
	 * Judged by where the command came from, not the entity it runs as: only a player who passes
	 * {@link #canUse(ServerPlayer)}, or the server console. Command blocks, functions and commands
	 * agents run (even through "execute as someone") can't, so agents can't start more agents.
	 */
	public boolean canUse(CommandSourceStack source) {
		CommandSource origin = ((CommandSourceStackAccessor) source).agentsmc$source();
		if (origin == s.server) return true;
		// A player's own commands carry that player's command source; "execute as" keeps the original one.
		for (ServerPlayer player : s.server.getPlayerList().getPlayers()) {
			if (player.commandSource() == origin) return canUse(player);
		}
		return false;
	}

	// ---- menu ----

	/** Everything the agents menu shows, for one player, with the log of the selected agent. */
	public JsonObject snapshot(ServerPlayer viewer, String selected) {
		JsonObject root = new JsonObject();
		root.addProperty("now", System.currentTimeMillis());
		boolean full = canUse(viewer);
		root.addProperty("canUse", full);
		root.addProperty("model", blankToDefault(s.config.model));
		root.addProperty("effort", blankToDefault(s.config.effort));
		JsonArray agents = new JsonArray();
		List<AgentBrain> sorted = new ArrayList<>(brains.values());
		sorted.sort(Comparator.comparingLong(AgentBrain::lastActive).reversed());
		for (AgentBrain b : sorted) {
			JsonObject a = new JsonObject();
			a.addProperty("name", b.name);
			a.addProperty("status", b.status().name().toLowerCase(Locale.ROOT));
			a.addProperty("activity", b.activity());
			a.addProperty("last", b.lastActive());
			a.addProperty("body", body(b.name) != null);
			ActivityLog.Entry last = b.log.last();
			a.addProperty("preview", last == null || !full ? "" : preview(last));
			agents.add(a);
		}
		root.add("agents", agents);
		AgentBrain sel = find(selected);
		if (sel != null) {
			root.addProperty("selected", sel.name);
			JsonArray log = new JsonArray();
			int chars = 0;
			List<ActivityLog.Entry> tail = sel.log.tail(150);
			// Newest entries matter most: drop old ones if the packet would get large.
			for (int i = tail.size() - 1; i >= 0 && chars < 200_000; i--) {
				ActivityLog.Entry e = tail.get(i);
				JsonObject o = new JsonObject();
				o.addProperty("id", e.id());
				o.addProperty("t", e.time());
				o.addProperty("k", e.kind().name().toLowerCase(Locale.ROOT));
				if (e.who() != null) o.addProperty("who", e.who());
				String text = e.text().length() > 4000 ? e.text().substring(0, 3999) + "…" : e.text();
				o.addProperty("text", text);
				chars += text.length() + 64;
				log.add(o);
			}
			JsonArray ordered = new JsonArray();
			for (int i = log.size() - 1; i >= 0; i--) ordered.add(log.get(i));
			root.add("log", ordered);
		}
		return root;
	}

	private static String preview(ActivityLog.Entry e) {
		String text = e.text().strip().lines().findFirst().orElse("");
		return switch (e.kind()) {
			case USER -> e.who() + ": " + text;
			case SAY -> "Said: " + text;
			default -> text;
		};
	}

	private static String blankToDefault(String v) {
		return v == null || v.isBlank() ? "default" : v.trim();
	}

	// ---- for AgentBrain (any thread) ----

	Config config() {
		return s.config;
	}

	Path agentDir(String name) {
		return dir.resolve("agents").resolve(name.toLowerCase(Locale.ROOT));
	}

	/** The bound MCP endpoint URL with a {@code {key}} placeholder, or null if the server isn't running. */
	String mcpUrl() {
		if (s.mcp == null) return null;
		String host = s.config.host;
		if (host.equals("0.0.0.0") || host.equals("::") || host.isBlank()) host = "127.0.0.1";
		if (host.contains(":")) host = "[" + host + "]";
		return "http://" + host + ":" + s.mcp.port() + "/agent/{key}/mcp";
	}

	String issueKey(String name) {
		String key = UUID.randomUUID().toString().replace("-", "");
		keys.put(key, name);
		return key;
	}

	void revokeKey(String key) {
		keys.remove(key);
	}

	/** The agent a bound MCP endpoint acts as. Called on HTTP threads. */
	public String agentForKey(String key) {
		return keys.get(key);
	}

	void sessionChanged() {
		s.server.execute(this::save);
	}

	/** Tells a player (if online) something went wrong with an agent they talked to. */
	void notify(String player, String message) {
		if (player == null) return;
		s.server.execute(() -> {
			ServerPlayer p = s.server.getPlayerList().getPlayerByName(player);
			if (p != null) p.sendSystemMessage(Component.literal("[Agents] " + message).withStyle(ChatFormatting.RED));
		});
	}

	// ---- persistence ----

	private AgentPlayer spawnBody(String name, ServerLevel level, Vec3 pos, float yaw, GameType mode) {
		AgentPlayer body = s.agents.spawn(name, level, pos, yaw, mode);
		s.agents.state(name).eventCursor = s.events.lastSeq();
		return body;
	}

	/** Writes the roster: each agent's conversation and where its body is (or last was). */
	public void save() {
		List<Saved> list = new ArrayList<>();
		for (AgentBrain b : brains.values()) {
			Saved a = saved.computeIfAbsent(key(b.name), k -> new Saved());
			a.name = b.name;
			a.sessionId = b.sessionId();
			AgentPlayer body = body(b.name);
			if (body != null) {
				a.dimension = body.level().dimension().identifier().toString();
				a.x = body.getX();
				a.y = body.getY();
				a.z = body.getZ();
				a.yaw = body.getYRot();
				a.gamemode = body.gameMode().getName();
			} else if (a.x == 0 && a.y == 0 && a.z == 0) {
				BlockPos spawn = s.server.overworld().getRespawnData().pos();
				a.x = spawn.getX() + 0.5;
				a.y = spawn.getY();
				a.z = spawn.getZ() + 0.5;
			}
			list.add(a);
		}
		try {
			Files.createDirectories(dir);
			writeAtomically(dir.resolve("agents.json"), GSON.toJson(list));
			for (AgentBrain b : brains.values()) {
				Path agentDir = agentDir(b.name);
				Files.createDirectories(agentDir);
				writeAtomically(agentDir.resolve("log.json"), GSON.toJson(b.log.tail(150)));
			}
		} catch (IOException | RuntimeException e) {
			AgentsMc.LOGGER.warn("Agents MC: could not save the agent roster", e);
		}
	}

	private static void writeAtomically(Path file, String text) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, text);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	/** The menu's history for an agent, as saved with the world. */
	private void loadLog(AgentBrain brain) {
		Path file = agentDir(brain.name).resolve("log.json");
		if (!Files.exists(file)) return;
		try {
			List<ActivityLog.Entry> entries = GSON.fromJson(Files.readString(file), new TypeToken<List<ActivityLog.Entry>>() {
			}.getType());
			if (entries != null) brain.log.restore(entries);
		} catch (IOException | RuntimeException e) {
			AgentsMc.LOGGER.warn("Agents MC: could not read {}", file, e);
		}
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	/** Names for command suggestions. */
	public Set<String> names() {
		Set<String> out = new java.util.LinkedHashSet<>();
		for (AgentBrain b : brains.values()) out.add(b.name);
		return out;
	}
}
