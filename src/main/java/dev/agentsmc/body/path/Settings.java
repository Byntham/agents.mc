package dev.agentsmc.body.path;

/**
 * What the body is allowed to do while searching.
 *
 * @param maxFall deepest drop (in blocks) the planner will walk off, unless it lands in water. The
 *                body takes no fall damage, so this only keeps it from dropping somewhere it can't
 *                climb out of without digging
 * @param canSwim treat water as passable and allow swim moves
 * @param canClimb allow moving up and down ladders and vines
 * @param canJumpGaps allow running jumps over one-block gaps
 * @param canOpenDoors treat closed wooden doors and fence gates as passable
 * @param maxNodes expansion budget before giving up and returning a partial path
 * @param allowDangerous allow standing on magma and walking through sweet berry bushes
 */
public record Settings(int maxFall, boolean canSwim, boolean canClimb, boolean canJumpGaps,
		boolean canOpenDoors, int maxNodes, boolean allowDangerous) {

	public static final Settings DEFAULT = new Settings(8, true, true, true, true, 6000, false);
}
