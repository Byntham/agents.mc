package dev.agentsmc.brain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Map;

/** One-line descriptions of tool calls and results for the activity log. */
final class Summaries {
	private static final int MAX = 120;

	private Summaries() {
	}

	/** E.g. {@code fill x1=10 y1=64 z1=3 x2=15 ... block=stone_bricks}. Big arguments show as their size. */
	static String tool(String name, JsonObject input) {
		switch (name) {
			case "WebSearch" -> {
				return "Searched the web: " + text(input, "query");
			}
			case "WebFetch" -> {
				return "Read " + text(input, "url");
			}
			default -> {
			}
		}
		StringBuilder sb = new StringBuilder(name);
		for (Map.Entry<String, JsonElement> e : input.entrySet()) {
			if (e.getKey().equals("agent")) continue;
			JsonElement v = e.getValue();
			String value;
			if (v.isJsonArray()) value = v.getAsJsonArray().size() + " items";
			else if (v.isJsonObject()) value = v.getAsJsonObject().size() + " entries";
			else if (v.isJsonNull()) continue;
			else value = v.getAsString();
			if (value.length() > 40) value = value.substring(0, 39) + "…";
			sb.append(' ').append(e.getKey()).append('=').append(value);
			if (sb.length() > MAX) {
				sb.setLength(MAX - 1);
				sb.append('…');
				break;
			}
		}
		return sb.toString();
	}

	/** The text of a tool_result's content, which is a string or a list of content blocks. */
	static String resultText(JsonElement content) {
		if (content == null || content.isJsonNull()) return "";
		if (content.isJsonPrimitive()) return content.getAsString();
		StringBuilder sb = new StringBuilder();
		if (content.isJsonArray()) {
			for (JsonElement el : content.getAsJsonArray()) {
				if (el.isJsonObject() && el.getAsJsonObject().has("text")) sb.append(el.getAsJsonObject().get("text").getAsString()).append('\n');
			}
		}
		return sb.toString().strip();
	}

	static String firstLine(String text, int max) {
		String line = text.strip().lines().findFirst().orElse("");
		return line.length() > max ? line.substring(0, max - 1) + "…" : line;
	}

	static String duration(long ms) {
		long s = Math.max(1, Math.round(ms / 1000.0));
		return s < 60 ? s + "s" : String.format(Locale.ROOT, "%dm %ds", s / 60, s % 60);
	}

	private static String text(JsonObject input, String key) {
		JsonElement e = input.get(key);
		String s = e != null && e.isJsonPrimitive() ? e.getAsString() : "";
		return s.length() > 90 ? s.substring(0, 89) + "…" : s;
	}
}
