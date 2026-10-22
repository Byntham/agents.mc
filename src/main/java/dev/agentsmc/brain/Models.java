package dev.agentsmc.brain;

import dev.agentsmc.AgentsMc;
import dev.agentsmc.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The models and reasoning efforts the agents menu offers. Players pick from these lists, and only
 * these values (or the host's own config) reach the claude command line: Claude Code accepts any
 * model name without complaint, and on Windows the command can run through cmd.exe.
 */
public final class Models {
	/** @param id the value passed to claude, or "" for Claude Code's default */
	public record Option(String id, String label) {
	}

	/** What a value from the config may look like, e.g. claude-opus-5-5 or opus[1m]. Nothing cmd.exe treats as syntax. */
	private static final Set<String> warned = ConcurrentHashMap.newKeySet();
	private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._:\\[\\]-]{1,64}");

	// Aliases, so each always means the newest model of that family.
	private static final List<Option> MODELS = List.of(
			new Option("", "Default"),
			new Option("fable", "Claude Fable"),
			new Option("opus", "Claude Opus"),
			new Option("sonnet", "Claude Sonnet"),
			new Option("haiku", "Claude Haiku"));

	private static final List<Option> EFFORTS = List.of(
			new Option("", "Default"),
			new Option("low", "Low"),
			new Option("medium", "Medium"),
			new Option("high", "High"),
			new Option("xhigh", "Extra high"),
			new Option("max", "Max"));

	private Models() {
	}

	/** The models on offer, plus the one config/agentsmc.json names if it isn't one of them. */
	public static List<Option> models(Config config) {
		return withCustom(MODELS, configModel(config));
	}

	public static List<Option> efforts(Config config) {
		return withCustom(EFFORTS, configEffort(config));
	}

	/** The model a new agent starts with: the config's, or "" for Claude Code's default. */
	public static String configModel(Config config) {
		return token(config.model, "model");
	}

	public static String configEffort(Config config) {
		return token(config.effort == null ? null : config.effort.toLowerCase(Locale.ROOT), "effort");
	}

	private static String token(String value, String key) {
		String v = value == null ? "" : value.trim();
		if (v.isEmpty() || TOKEN.matcher(v).matches()) return v;
		if (warned.add(v)) AgentsMc.LOGGER.warn("Agents MC: ignoring {} \"{}\" in config/agentsmc.json, it isn't a model or effort name", key, v);
		return "";
	}

	public static boolean isModel(Config config, String id) {
		return find(models(config), id) != null;
	}

	public static boolean isEffort(Config config, String id) {
		return find(efforts(config), id) != null;
	}

	/** How the agent's log names a choice, e.g. "Claude Haiku" or "high effort". */
	static String describe(Config config, String model, String effort) {
		Option m = find(models(config), model);
		Option e = find(efforts(config), effort);
		String modelText = m == null || m.id().isEmpty() ? "the default model" : m.label();
		String effortText = e == null || e.id().isEmpty() ? "default effort" : e.label().toLowerCase(Locale.ROOT) + " effort";
		return modelText + ", " + effortText;
	}

	private static List<Option> withCustom(List<Option> options, String custom) {
		if (find(options, custom) != null) return options;
		List<Option> out = new ArrayList<>(options);
		out.add(new Option(custom, custom));
		return out;
	}

	private static Option find(List<Option> options, String id) {
		for (Option o : options) if (o.id().equals(id)) return o;
		return null;
	}
}
