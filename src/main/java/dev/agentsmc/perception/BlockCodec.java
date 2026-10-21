package dev.agentsmc.perception;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.agentsmc.mcp.ToolException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Converts block states to and from the strings agents read and write, e.g.
 * {@code oak_stairs[facing=east,half=top]}. The "minecraft:" namespace is omitted.
 */
public final class BlockCodec {
	/**
	 * Properties that say which way a block is placed. AUTO detail always prints them, even at their
	 * default value, because "oak_door" reads as "no direction" rather than "facing north".
	 */
	private static final Set<String> ORIENTATION = Set.of("facing", "axis", "half", "rotation", "type", "hinge", "face", "orientation");

	public enum Detail {
		/** Every property. Exact, but verbose (leaves alone have dozens of variants). */
		FULL,
		/**
		 * Orientation properties, plus any other property that differs from the block's default state.
		 * Drops leaf distance. Round-trips through {@link #parse}.
		 */
		AUTO,
		/** Block id only. */
		NONE;

		public static Detail of(String s) {
			try {
				return valueOf(s.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw new ToolException("properties must be one of: auto, full, none");
			}
		}
	}

	/**
	 * A block an agent asked for: the full state to place, plus which properties it actually wrote.
	 * Properties it left out (fence connections, stair shape) may legitimately change once placed.
	 */
	public record Spec(String text, BlockState state, Set<Property<?>> specified) {
		public boolean matches(BlockState actual) {
			if (actual.getBlock() != state.getBlock()) return false;
			for (Property<?> p : specified) {
				if (!actual.getValue(p).equals(state.getValue(p))) return false;
			}
			return true;
		}
	}

	private BlockCodec() {
	}

	public static String id(BlockState state) {
		return id(state, Detail.AUTO);
	}

	public static String id(BlockState state, Detail detail) {
		String id = blockId(state);
		if (detail == Detail.NONE || state.getProperties().isEmpty()) return id;
		BlockState defaults = state.getBlock().defaultBlockState();
		StringBuilder props = new StringBuilder();
		for (Property<?> p : state.getProperties()) {
			if (detail == Detail.AUTO) {
				if (p == BlockStateProperties.DISTANCE) continue;
				if (!ORIENTATION.contains(p.getName()) && state.getValue(p).equals(defaults.getValue(p))) continue;
			}
			if (!props.isEmpty()) props.append(',');
			props.append(p.getName()).append('=').append(valueName(state, p));
		}
		return props.isEmpty() ? id : id + "[" + props + "]";
	}

	public static String blockId(BlockState state) {
		return shortId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
	}

	public static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}

	/** A spec that asks for exactly this state (used when restoring blocks). */
	public static Spec exact(BlockState state) {
		return new Spec(id(state, Detail.FULL), state, new HashSet<>(state.getProperties()));
	}

	/** Parses "stone", "minecraft:oak_log[axis=x]", etc. */
	public static BlockState parse(String text) {
		return parseSpec(text).state();
	}

	/**
	 * Parses a block and remembers which properties were given. Leaves default to persistent=true so
	 * placed leaves don't decay, unless the agent says otherwise.
	 */
	public static Spec parseSpec(String text) {
		String s = text.trim();
		try {
			BlockStateParser.BlockResult result = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false);
			BlockState state = result.blockState();
			Set<Property<?>> specified = new HashSet<>(result.properties().keySet());
			if (state.hasProperty(BlockStateProperties.PERSISTENT) && !specified.contains(BlockStateProperties.PERSISTENT)) {
				state = state.setValue(BlockStateProperties.PERSISTENT, true);
			}
			return new Spec(shortId(s), state, specified);
		} catch (CommandSyntaxException e) {
			throw new ToolException("Invalid block '" + text + "': " + e.getMessage());
		}
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> p) {
		return p.getName(state.getValue(p));
	}
}
