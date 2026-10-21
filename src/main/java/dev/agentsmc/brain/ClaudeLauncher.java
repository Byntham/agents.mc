package dev.agentsmc.brain;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Finds the user's Claude Code install and builds the command line that runs one agent. */
final class ClaudeLauncher {
	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

	private ClaudeLauncher() {
	}

	/**
	 * The command prefix that runs {@code claude}: the configured path, else the first match on PATH,
	 * else the native installer's location. Null if none exists.
	 */
	static List<String> executable(String configured) {
		if (configured != null && !configured.isBlank()) return wrap(configured.trim());
		List<String> names = WINDOWS ? List.of("claude.exe", "claude.cmd", "claude.bat") : List.of("claude");
		List<Path> dirs = new ArrayList<>();
		String path = System.getenv("PATH");
		if (path != null) {
			for (String dir : path.split(File.pathSeparator)) {
				try {
					if (!dir.isBlank()) dirs.add(Path.of(dir.trim().replace("\"", "")));
				} catch (InvalidPathException ignored) {
				}
			}
		}
		// Launchers don't always pass the user's shell PATH on; check where the installers put it too.
		String home = System.getProperty("user.home");
		dirs.add(Path.of(home, ".local", "bin"));
		dirs.add(Path.of(home, ".claude", "local"));
		for (Path dir : dirs) {
			for (String name : names) {
				Path p = dir.resolve(name);
				if (Files.isRegularFile(p) && (WINDOWS || Files.isExecutable(p))) return wrap(p.toString());
			}
		}
		return null;
	}

	/** npm installs a .cmd shim on Windows, which only cmd.exe can run. */
	private static List<String> wrap(String exe) {
		String lower = exe.toLowerCase(Locale.ROOT);
		return lower.endsWith(".cmd") || lower.endsWith(".bat") ? List.of("cmd.exe", "/c", exe) : List.of(exe);
	}

	/**
	 * @param resume session id to continue, or null for a new conversation
	 */
	static List<String> command(List<String> exe, Path systemPrompt, Path mcpConfig, String model, String effort, String resume) {
		List<String> cmd = new ArrayList<>(exe);
		cmd.addAll(List.of(
				"-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
				// Keep the user's own CLAUDE.md, settings, hooks, plugins, skills and MCP servers out of agent sessions.
				"--restricted", "--strict-mcp-config", "--disable-slash-commands",
				"--mcp-config", mcpConfig.toString(),
				"--system-prompt-file", systemPrompt.toString(),
				// Anyone who can talk to an agent can try to talk it into things, so it gets no tool that
				// touches this computer: only its body (the minecraft MCP server) and web research.
				"--tools", "WebSearch,WebFetch",
				"--allowedTools", "mcp__minecraft,WebSearch,WebFetch",
				"--permission-prompts", "none"));
		if (model != null && !model.isBlank()) cmd.addAll(List.of("--model", model.trim()));
		if (effort != null && !effort.isBlank()) cmd.addAll(List.of("--effort", effort.trim()));
		if (resume != null) cmd.addAll(List.of("--resume", resume));
		return cmd;
	}
}
