package dev.agentsmc.brain;

import dev.agentsmc.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The models and reasoning efforts the agents menu offers. Players pick from these lists, and only
 * these values (or the host's own config) reach the claude command line: Claude Code accepts any
 * model name without complaint, and on Windows the command can run through cmd.exe.
 */
public final class Models {
	/** @param id the value passed to claude, or "" for Claude Code's default */
	public record Option(String id, String label) {
	}

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
		return withCustom(MODELS, config.model == null ? "" : config.model.trim());
	}

	public static List<Option> efforts(Config config) {
		return withCustom(EFFORTS, config.effort == null ? "" : config.effort.trim().toLowerCase(Locale.ROOT));
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
