package dev.agentsmc.agent;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/** Per-agent bookkeeping that is not part of the player entity. */
public final class AgentState {
	private static final int UNDO_DEPTH = 32;

	/** One build call's previous block states, so it can be reverted. */
	public record Edit(String description, Map<BlockPos, BlockState> before) {
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
