package dev.agentsmc.body.path;

/** How the agent moves from one node to the next. */
public enum MoveType {
	/** Horizontal move at the same height, including diagonals. */
	WALK,
	/** Jump up one block onto an adjacent higher block. */
	STEP_UP,
	/** Walk off an edge and fall one or more blocks. */
	DROP,
	/** Running jump across a one-block gap, landing level or one lower. */
	JUMP_GAP,
	/** Move up or down a ladder, vine or other climbable block. */
	CLIMB,
	/** Move through water. */
	SWIM,
	/** Walk through a closed door or fence gate that must be opened first. */
	DOOR
}
