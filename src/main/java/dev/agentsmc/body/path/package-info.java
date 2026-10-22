/**
 * Standalone A* pathfinder for player-sized agent bodies.
 *
 * <p>Conventions:
 * <ul>
 *   <li>A node position is the block the agent's <em>feet</em> occupy. The body is treated as a
 *       1x2 column, so the feet block and the block above must both be passable.</li>
 *   <li>{@link dev.agentsmc.body.path.PathNode#how()} says how the agent gets <em>into</em> that
 *       node from the previous one. The first node of every path is the start and has
 *       {@link dev.agentsmc.body.path.MoveType#WALK}.</li>
 *   <li>{@link dev.agentsmc.body.path.PathFinder#find} reads the world and must run on the server
 *       thread. It never mutates the world.</li>
 *   <li>If the goal is out of reach within the node budget, the returned path leads to the
 *       explored node closest to the goal and {@code reachesGoal} is false.</li>
 * </ul>
 */
package dev.agentsmc.body.path;
