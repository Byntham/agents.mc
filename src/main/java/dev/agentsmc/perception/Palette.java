package dev.agentsmc.perception;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Assigns one printable character per distinct block string, preferring a mnemonic letter
 * (s for stone, g for grass_block). '.' is always air and '~' is always water.
 */
public final class Palette {
	public static final char AIR = '.';
	public static final char WATER = '~';
	public static final char SELF = '@';
	public static final char OTHER = '*';
	public static final char OVERFLOW = '?';
	private static final String POOL =
			"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#$%&+=^!<>/|:;,()[]{}-";

	private final Map<String, Character> symbols = new LinkedHashMap<>();
	private final Map<Character, String> fixed = new LinkedHashMap<>();
	private final Set<Character> used = new HashSet<>(Set.of(AIR, WATER, SELF, OTHER, OVERFLOW));
	private boolean overflowed;

	/** Reserves a character the caller chose (e.g. from the agent's own build palette). */
	public void fix(char c, String label) {
		fixed.put(c, label);
		used.add(c);
	}

	public char symbol(String block) {
		Character existing = symbols.get(block);
		if (existing != null) return existing;
		char c = choose(block);
		if (c == OVERFLOW) {
			overflowed = true;
			return c;
		}
		symbols.put(block, c);
		used.add(c);
		return c;
	}

	private char choose(String block) {
		String base = block.contains("[") ? block.substring(0, block.indexOf('[')) : block;
		if (base.equals("air") || base.equals("cave_air") || base.equals("void_air")) return AIR;
		if (block.equals("water")) return WATER;
		String name = base.contains(":") ? base.substring(base.indexOf(':') + 1) : base;
		for (char ch : name.toCharArray()) {
			if (!Character.isLetterOrDigit(ch)) continue;
			char lower = Character.toLowerCase(ch);
			char upper = Character.toUpperCase(ch);
			if (!used.contains(lower)) return lower;
			if (!used.contains(upper)) return upper;
		}
		for (char ch : POOL.toCharArray()) if (!used.contains(ch)) return ch;
		return OVERFLOW;
	}

	public boolean overflowed() {
		return overflowed;
	}

	public void appendLegend(StringBuilder sb) {
		sb.append("Palette:\n");
		if (!fixed.isEmpty()) {
			sb.append("  (your characters; each matches that block type with at least the properties you gave)\n");
			fixed.forEach((c, label) -> sb.append("  ").append(c).append(" = ").append(label).append('\n'));
			if (!symbols.isEmpty()) sb.append("  (other blocks)\n");
		}
		symbols.forEach((block, c) -> {
			if (c != AIR) sb.append("  ").append(c).append(" = ").append(block).append('\n');
		});
		sb.append("  . = air\n");
		if (overflowed) sb.append("  ? = (too many distinct blocks; query a smaller region or use properties=none)\n");
	}
}
