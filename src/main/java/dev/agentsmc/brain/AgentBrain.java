package dev.agentsmc.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.agentsmc.AgentsMc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The Claude Code process that drives one agent body. It starts on the first message and stays up
 * between messages, so a message sent mid-turn steers the running turn. After a while idle it is
 * closed, and the next message resumes the same conversation with {@code --resume}.
 *
 * <p>Starting, writing to and closing the process happen on this brain's own I/O thread, so the
 * server thread never waits on Claude. Output is read on a second thread. The status and log the
 * menu shows are guarded by {@code this}.
 */
public final class AgentBrain {
	public enum Status {
		IDLE, STARTING, WORKING
	}

	private static final String TOOL_PREFIX = "mcp__minecraft__";
	private static final String INTERRUPTED_TOOL = "The user doesn't want to proceed";

	public final String name;
	public final ActivityLog log = new ActivityLog();
	private final Brains owner;
	private final ExecutorService io;

	// Process state: only touched on the io thread (process is also read by the reader threads).
	private volatile Process process;
	private Writer stdin;
	private String mcpKey;
	/** This launch's MCP config. Named per launch, so an old launch's cleanup can't delete a newer one's. */
	private Path mcpFile;
	private Thread reader;
	/** Set once the agent is removed or the world closes: no more launches. */
	private volatile boolean closed;
	/** Set when we close the process on purpose, so its exit isn't reported as a failure. */
	private boolean closing;
	/** User-message lines written since the last launch, to replay if a resume fails. */
	private final List<String> sentThisLaunch = new ArrayList<>();

	// Display and session state: guarded by this.
	private String sessionId;
	private Set<String> capabilities = Set.of();
	private Status status = Status.IDLE;
	private String activity = "";
	private long lastActive = System.currentTimeMillis();
	private long turnStart;
	private String lastSender;
	private boolean resumed, initSeen, resumeFailed, stopRequested;
	private final Map<String, String> toolNames = new HashMap<>();
	private final ArrayDeque<String> stderrTail = new ArrayDeque<>();

	AgentBrain(String name, Brains owner, String sessionId) {
		this.name = name;
		this.owner = owner;
		this.sessionId = sessionId;
		this.io = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "agentsmc-brain-" + name);
			t.setDaemon(true);
			return t;
		});
	}

	public synchronized Status status() {
		return status;
	}

	/** What the agent is doing right now, e.g. the tool it is running. Empty when idle. */
	public synchronized String activity() {
		return activity;
	}

	public synchronized long lastActive() {
		return lastActive;
	}

	public synchronized String sessionId() {
		return sessionId;
	}

	/**
	 * Delivers a player's message. If the agent is mid-turn, Claude reads it as soon as its current
	 * tool calls finish (priority "next"); otherwise it starts a turn.
	 *
	 * @param via     where it came from, as the agent should read it, e.g. "in chat"
	 * @param context where the sender was and what they were looking at as they sent it, or null
	 */
	public void send(String from, String via, String text, String context) {
		synchronized (this) {
			log.add(ActivityLog.Kind.USER, from, text);
			lastSender = from;
			lastActive = System.currentTimeMillis();
			if (status == Status.IDLE) {
				status = Status.STARTING;
				activity = "Reading your message";
				turnStart = lastActive;
			}
		}
		String content = from + " (" + via + "): " + text;
		if (context != null) content += "\n\n[Where " + from + " was when they sent this]\n" + context.strip();
		JsonObject msg = userMessage(content, "next");
		io.execute(() -> writeUserMessage(msg.toString()));
	}

	/**
	 * Stops the current turn. Returns false if there was nothing to stop.
	 */
	public boolean stop(String by) {
		synchronized (this) {
			if (status == Status.IDLE) return false;
			stopRequested = true;
			log.add(ActivityLog.Kind.INFO, null, "Stopped by " + by);
		}
		io.execute(() -> {
			if (process == null || !process.isAlive()) return;
			Set<String> caps;
			synchronized (this) {
				caps = capabilities;
			}
			JsonObject msg;
			if (caps.contains("interrupt_receipt_v1")) {
				// The control message the Agent SDK's interrupt() sends.
				msg = new JsonObject();
				msg.addProperty("type", "control_request");
				msg.addProperty("request_id", "stop-" + UUID.randomUUID());
				JsonObject request = new JsonObject();
				request.addProperty("subtype", "interrupt");
				if (caps.contains("interrupt_cancel_queued_v1")) request.addProperty("cancel_queued", true);
				msg.add("request", request);
			} else {
				// Older Claude Code, or not started far enough to say what it supports: a "now" message
				// interrupts the turn too.
				msg = userMessage(by + " asked you to stop. Stop what you are doing and wait for the next message.", "now");
			}
			try {
				writeLine(msg.toString());
			} catch (IOException e) {
				AgentsMc.LOGGER.warn("Agents MC: could not stop {}", name, e);
			}
		});
		return true;
	}

	/** Closes the process if it has been idle for {@code idleMs}. The conversation resumes on the next message. */
	void closeIfIdle(long idleMs) {
		synchronized (this) {
			if (status != Status.IDLE || System.currentTimeMillis() - lastActive < idleMs) return;
		}
		io.execute(() -> {
			synchronized (this) {
				if (status != Status.IDLE) return;
			}
			close();
		});
	}

	/** Closes the process and stops the I/O thread. Returns once that is queued. */
	void shutdown() {
		closed = true;
		io.execute(this::close);
		io.shutdown();
	}

	boolean awaitShutdown(long ms) throws InterruptedException {
		return io.awaitTermination(ms, TimeUnit.MILLISECONDS);
	}

	// ---- io thread ----

	private void writeUserMessage(String line) {
		if (closed) return;
		try {
			if (!ensureProcess()) return;
			sentThisLaunch.add(line);
			writeLine(line);
		} catch (LaunchException e) {
			fail(e.getMessage());
		} catch (IOException e) {
			fail("Couldn't send the message to Claude Code: " + e.getMessage());
		}
	}

	private void writeLine(String line) throws IOException {
		if (stdin == null) return;
		stdin.write(line);
		stdin.write('\n');
		stdin.flush();
	}

	/** Returns false if the agent was removed or the world is closing, so nothing should be sent. */
	private boolean ensureProcess() throws LaunchException {
		if (closed) return false;
		if (process != null && process.isAlive()) return true;
		// It died and the reader hasn't reported it yet: clean up first (revoke its key, replay or report).
		if (process != null) {
			// Let the reader handle its last lines first (e.g. a failed resume's result).
			try {
				if (reader != null) reader.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			onExit(process);
			if (process != null && process.isAlive()) return true; // onExit started a replacement
		}
		List<String> exe = ClaudeLauncher.executable(owner.config().claudePath);
		if (exe == null) {
			throw new LaunchException("Couldn't find Claude Code. Install it and run claude once to log in, "
					+ "or set claudePath in config/agentsmc.json.");
		}
		String url = owner.mcpUrl();
		if (url == null) throw new LaunchException("The Agents MC tool server isn't running (see the game log), so the agent has no body to control.");

		String resume;
		synchronized (this) {
			resume = sessionId;
			resumed = resume != null;
			initSeen = false;
			resumeFailed = false;
			stderrTail.clear();
		}
		Process p;
		try {
			Path dir = owner.agentDir(name);
			Files.createDirectories(dir);
			Path prompt = dir.resolve("system-prompt.md");
			Files.writeString(prompt, Prompts.system(name));
			mcpKey = owner.issueKey(name);
			Path mcp = dir.resolve("mcp-" + mcpKey.substring(0, 8) + ".json");
			mcpFile = mcp;
			Files.writeString(mcp, mcpConfig(url.replace("{key}", mcpKey)).toString());

			// Relative to the working directory, so cmd.exe never sees characters like & from the world's folder name.
			List<String> cmd = ClaudeLauncher.command(exe, prompt.getFileName(), mcp.getFileName(), owner.config().model, owner.config().effort, resume);
			ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
			// If Minecraft itself was started from a Claude Code session, don't let the agent think it is nested in it.
			pb.environment().remove("CLAUDECODE");
			pb.environment().remove("CLAUDE_CODE_ENTRYPOINT");
			// Removed while launching: Brains.remove may have revoked keys before this one existed.
			if (closed) {
				owner.revokeKey(mcpKey);
				mcpKey = null;
				Files.deleteIfExists(mcp);
				return false;
			}
			p = pb.start();
		} catch (IOException e) {
			if (mcpKey != null) owner.revokeKey(mcpKey);
			mcpKey = null;
			try {
				if (mcpFile != null) Files.deleteIfExists(mcpFile);
			} catch (IOException ignored) {
			}
			throw new LaunchException("Couldn't start Claude Code (" + e.getMessage() + "). Check claudePath in config/agentsmc.json.");
		}
		process = p;
		closing = false;
		sentThisLaunch.clear();
		stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
		Thread out = new Thread(() -> readOutput(p), "agentsmc-brain-" + name + "-out");
		reader = out;
		out.setDaemon(true);
		out.start();
		Thread err = new Thread(() -> readErrors(p), "agentsmc-brain-" + name + "-err");
		err.setDaemon(true);
		err.start();
		if (closed) {
			close();
			return false;
		}
		return true;
	}

	private JsonObject mcpConfig(String url) {
		JsonObject server = new JsonObject();
		server.addProperty("type", "http");
		server.addProperty("url", url);
		String token = owner.config().token;
		if (token != null && !token.isEmpty()) {
			JsonObject headers = new JsonObject();
			headers.addProperty("Authorization", "Bearer " + token);
			server.add("headers", headers);
		}
		JsonObject servers = new JsonObject();
		servers.add("minecraft", server);
		JsonObject root = new JsonObject();
		root.add("mcpServers", servers);
		return root;
	}

	private void close() {
		Process p = process;
		if (p == null) return;
		closing = true;
		try {
			if (stdin != null) stdin.close();
		} catch (IOException ignored) {
		}
		try {
			if (!p.waitFor(5, TimeUnit.SECONDS)) kill(p);
		} catch (InterruptedException e) {
			kill(p);
			Thread.currentThread().interrupt();
		}
		onExit(p);
	}

	/** Kills the process and its children: an npm install on Windows runs claude under cmd.exe. */
	private static void kill(Process p) {
		p.descendants().forEach(ProcessHandle::destroyForcibly);
		p.destroyForcibly();
	}

	/** The process ended, on purpose or not. */
	private void onExit(Process p) {
		if (p != process) return;
		process = null;
		stdin = null;
		if (mcpKey != null) owner.revokeKey(mcpKey);
		mcpKey = null;
		try {
			// It can hold the MCP bearer token, and world folders get shared.
			if (mcpFile != null) Files.deleteIfExists(mcpFile);
		} catch (IOException ignored) {
		}
		if (closing) {
			closing = false;
			return;
		}
		boolean retry;
		String errors;
		synchronized (this) {
			retry = resumeFailed;
			errors = String.join("\n", stderrTail);
			if (!retry && status != Status.IDLE) setIdle();
		}
		if (retry) {
			// The saved conversation is gone (e.g. Claude Code's history was cleared). Start a new one
			// and send it what the old one never read.
			synchronized (this) {
				sessionId = null;
				status = Status.STARTING;
				log.add(ActivityLog.Kind.INFO, null, "Couldn't find the previous conversation, so this is a new one.");
			}
			owner.sessionChanged();
			List<String> replay = List.copyOf(sentThisLaunch);
			for (String line : replay) writeUserMessage(line);
			return;
		}
		int code = p.exitValue();
		String last = errors.isBlank() ? "" : ": " + errors.lines().reduce((a, b) -> b).orElse("");
		fail("Claude Code stopped unexpectedly (exit code " + code + ")" + last);
	}

	private void fail(String message) {
		String notify;
		synchronized (this) {
			log.add(ActivityLog.Kind.ERROR, null, message);
			setIdle();
			notify = lastSender;
		}
		owner.notify(notify, name + ": " + message);
	}

	// ---- reader threads ----

	private void readOutput(Process p) {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.isBlank()) continue;
				JsonObject msg;
				try {
					JsonElement el = JsonParser.parseString(line);
					if (!el.isJsonObject()) continue;
					msg = el.getAsJsonObject();
				} catch (JsonParseException e) {
					continue;
				}
				if (p != process) return;
				try {
					handle(msg);
				} catch (RuntimeException e) {
					AgentsMc.LOGGER.warn("Agents MC: couldn't handle a message from {}'s Claude Code: {}", name, line, e);
				}
			}
		} catch (IOException ignored) {
		}
		try {
			p.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (!io.isShutdown()) io.execute(() -> onExit(p));
	}

	private void readErrors(Process p) {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.isBlank()) continue;
				AgentsMc.LOGGER.debug("Agents MC: {} (claude stderr): {}", name, line);
				synchronized (this) {
					stderrTail.addLast(line);
					while (stderrTail.size() > 20) stderrTail.removeFirst();
				}
			}
		} catch (IOException ignored) {
		}
	}

	private synchronized void handle(JsonObject msg) {
		String type = str(msg, "type");
		switch (type) {
			case "system" -> {
				if (!"init".equals(str(msg, "subtype"))) return;
				initSeen = true;
				String sid = str(msg, "session_id");
				if (!sid.isEmpty() && !sid.equals(sessionId)) {
					sessionId = sid;
					owner.sessionChanged();
				}
				Set<String> caps = new HashSet<>();
				if (msg.has("capabilities") && msg.get("capabilities").isJsonArray()) {
					for (JsonElement c : msg.getAsJsonArray("capabilities")) caps.add(c.getAsString());
				}
				capabilities = Set.copyOf(caps);
				working("Thinking");
			}
			case "assistant" -> {
				if (msg.has("parent_tool_use_id") && !msg.get("parent_tool_use_id").isJsonNull()) return;
				for (JsonElement el : content(msg)) {
					JsonObject block = el.getAsJsonObject();
					switch (str(block, "type")) {
						case "text" -> {
							String text = str(block, "text").strip();
							if (!text.isEmpty()) log.add(ActivityLog.Kind.TEXT, null, text);
							working("Writing");
						}
						case "thinking", "redacted_thinking" -> working("Thinking");
						case "tool_use" -> {
							String tool = str(block, "name");
							if (tool.startsWith(TOOL_PREFIX)) tool = tool.substring(TOOL_PREFIX.length());
							toolNames.put(str(block, "id"), tool);
							JsonObject input = block.has("input") && block.get("input").isJsonObject() ? block.getAsJsonObject("input") : new JsonObject();
							if (tool.equals("say")) {
								log.add(ActivityLog.Kind.SAY, name, str(input, "message"));
								working("Talking");
							} else {
								String summary = Summaries.tool(tool, input);
								log.add(ActivityLog.Kind.TOOL, null, summary);
								working(summary);
							}
						}
						default -> {
						}
					}
				}
			}
			case "user" -> {
				if (msg.has("parent_tool_use_id") && !msg.get("parent_tool_use_id").isJsonNull()) return;
				for (JsonElement el : content(msg)) {
					JsonObject block = el.getAsJsonObject();
					if (!"tool_result".equals(str(block, "type")) || !block.has("is_error") || !block.get("is_error").getAsBoolean()) continue;
					String text = Summaries.resultText(block.get("content"));
					if (text.startsWith(INTERRUPTED_TOOL)) continue;
					String tool = toolNames.getOrDefault(str(block, "tool_use_id"), "A tool");
					log.add(ActivityLog.Kind.ERROR, null, tool + " failed: " + Summaries.firstLine(text, 300));
				}
			}
			case "result" -> onResult(msg);
			case "rate_limit_event" -> {
				JsonObject info = msg.has("rate_limit_info") ? msg.getAsJsonObject("rate_limit_info") : new JsonObject();
				if ("rejected".equals(str(info, "status"))) {
					log.add(ActivityLog.Kind.ERROR, null, "Your Claude usage limit has been reached. The agent can continue once it resets.");
				}
			}
			default -> {
			}
		}
	}

	private void onResult(JsonObject msg) {
		boolean error = msg.has("is_error") && msg.get("is_error").getAsBoolean();
		String subtype = str(msg, "subtype");
		long ms = msg.has("duration_ms") ? msg.get("duration_ms").getAsLong() : System.currentTimeMillis() - turnStart;
		toolNames.clear();
		boolean stopped = stopRequested;
		setIdle();
		if (!error) {
			log.add(ActivityLog.Kind.INFO, null, "Finished in " + Summaries.duration(ms));
			return;
		}
		StringBuilder why = new StringBuilder();
		if (msg.has("errors") && msg.get("errors").isJsonArray()) {
			for (JsonElement e : msg.getAsJsonArray("errors")) why.append(why.isEmpty() ? "" : "; ").append(e.getAsString());
		}
		if (why.isEmpty() && msg.has("result") && msg.get("result").isJsonPrimitive()) why.append(msg.get("result").getAsString());
		if (why.isEmpty()) why.append(subtype);
		if (resumed && !initSeen && why.toString().contains("No conversation found")) {
			resumeFailed = true; // handled when the process exits
			return;
		}
		if (stopped && subtype.equals("error_during_execution")) return; // already logged "Stopped by"
		String message = Summaries.firstLine(why.toString(), 300);
		log.add(ActivityLog.Kind.ERROR, null, message);
		owner.notify(lastSender, name + ": " + message);
	}

	private void working(String what) {
		if (status != Status.WORKING) turnStart = System.currentTimeMillis();
		status = Status.WORKING;
		activity = what;
		lastActive = System.currentTimeMillis();
	}

	private void setIdle() {
		status = Status.IDLE;
		activity = "";
		stopRequested = false;
		lastActive = System.currentTimeMillis();
	}

	private static JsonObject userMessage(String text, String priority) {
		JsonObject message = new JsonObject();
		message.addProperty("role", "user");
		message.addProperty("content", text);
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "user");
		msg.add("message", message);
		msg.add("parent_tool_use_id", null);
		msg.addProperty("priority", priority);
		return msg;
	}

	private static JsonArray content(JsonObject msg) {
		if (!msg.has("message") || !msg.get("message").isJsonObject()) return new JsonArray();
		JsonElement c = msg.getAsJsonObject("message").get("content");
		return c != null && c.isJsonArray() ? c.getAsJsonArray() : new JsonArray();
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}

	private static final class LaunchException extends Exception {
		LaunchException(String message) {
			super(message);
		}
	}
}
