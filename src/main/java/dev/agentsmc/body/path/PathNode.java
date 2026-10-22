package dev.agentsmc.body.path;

import net.minecraft.core.BlockPos;

/**
 * One step of a path.
 *
 * @param pos the block the agent's feet stand in (normally air above a solid block)
 * @param how how the agent gets into this node from the previous one
 */
public record PathNode(BlockPos pos, MoveType how) {
}
