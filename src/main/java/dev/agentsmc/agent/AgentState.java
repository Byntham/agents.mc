package dev.agentsmc.agent;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/** Per-agent bookkeeping that is not part of the player entity. */
public final class AgentState {
	private static final int UNDO_DEPTH = 32;

	/**
	 * One build call's previous blocks, so it can be reverted: the dimension it happened in, the old
	 * states, and the old block-entity data (sign text, chest contents...) where there was any.
	 */
	public record Edit(String description, ResourceKey<Level> dimension, Map<BlockPos, BlockState> before,
					   Map<BlockPos, CompoundTag> blockEntities) {
	}

	private final Deque<Edit> undo = new ArrayDeque<>();
	/** Event-log cursor: the last event seq this agent has been shown. */
	public long eventCursor;

	public void pushEdit(Edit edit) {
		undo.push(edit);
		while (undo.size() > UNDO_DEPTH) undo.removeLast();
	}

	public Edit popEdit() {
		return undo.poll();
	}
}
