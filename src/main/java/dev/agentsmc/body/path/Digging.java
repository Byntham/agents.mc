package dev.agentsmc.body.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lets the planner pass through solid blocks by breaking them. The body supplies the break time,
 * because it depends on the tools in the inventory.
 */
@FunctionalInterface
public interface Digging {
	/** Seconds to break {@code state} at {@code pos} with the best tool at hand, or a negative number if it can't be broken. */
	double seconds(BlockPos pos, BlockState state);
}
