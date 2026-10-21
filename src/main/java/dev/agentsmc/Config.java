package dev.agentsmc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/agentsmc.json, written with defaults on first run. */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Bind address. Keep this on loopback unless you also set a token. */
	public String host = "127.0.0.1";
	public int port = 25590;
	/** If non-empty, clients must send "Authorization: Bearer <token>". */
	public String token = "";
	/** Allow agents to run arbitrary commands (as a gamemaster-level source). */
	public boolean allowCommands = true;
	/** Max blocks a single build call may change. */
	public int maxBuildBlocks = 32768;

	public static Config load(Path path) {
		Config config = new Config();
		try {
			if (Files.exists(path)) {
				Config read = GSON.fromJson(Files.readString(path), Config.class);
				if (read != null) config = read;
			}
			Files.writeString(path, GSON.toJson(config));
		} catch (IOException | RuntimeException e) {
			AgentsMc.LOGGER.warn("Agents MC: could not read/write {}, using defaults", path, e);
		}
		return config;
	}
}
