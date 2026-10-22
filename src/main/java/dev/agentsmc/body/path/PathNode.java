package dev.agentsmc.body.path;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * One step of a path.
 *
 * @param pos the block the agent's feet stand in (normally air above a solid block)
 * @param how how the agent gets into this node from the previous one
 * @param dig blocks to break, in order, before the move can be made; empty for a plain move
 */
public record PathNode(BlockPos pos, MoveType how, List<BlockPos> dig) {
	public PathNode {
		dig = List.copyOf(dig);
	}

	public PathNode(BlockPos pos, MoveType how) {
		this(pos, how, List.of());
	}
}
