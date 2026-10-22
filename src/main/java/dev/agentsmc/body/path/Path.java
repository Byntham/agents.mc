package dev.agentsmc.body.path;

import java.util.List;

/**
 * Result of a search.
 *
 * @param nodes the start node first (with {@link MoveType#WALK}), then one node per step
 * @param reachesGoal true if the last node is within tolerance of the goal
 * @param cost total move cost of the path, in roughly block units
 */
public record Path(List<PathNode> nodes, boolean reachesGoal, double cost) {
	/** Last node of the path. Never null; a path always contains at least the start. */
	public PathNode end() {
		return nodes.getLast();
	}
}
