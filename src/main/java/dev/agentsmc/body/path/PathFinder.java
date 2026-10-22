package dev.agentsmc.body.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over the block grid for a player-sized body (1 wide, 2 tall in block terms).
 *
 * <p>Call {@link #find} on the server thread only. The search reads block states but never
 * changes the world.
 */
public final class PathFinder {
	private static final double SQRT2 = Math.sqrt(2);
	/** Heuristic weight above 1 trades optimality for fewer expansions. */
	private static final double HEURISTIC_WEIGHT = 1.2;
	/** How far below maxFall we keep scanning for a water landing. */
	private static final int WATER_SCAN_EXTRA = 24;
	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	/** Diagonal offsets as (dx, dz) pairs. */
	private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private PathFinder() {
	}

	/**
	 * Finds a path from {@code start} to within {@code tolerance} blocks of {@code goal}.
	 *
	 * <p>If the goal cannot be reached within {@code settings.maxNodes()} expansions, the returned
	 * path leads to the explored node with the lowest heuristic (closest to the goal) and
	 * {@code reachesGoal} is false. The start is always accepted as a node even if the agent is
	 * mid-air or standing somewhere the rules would reject.
	 */
	public static Path find(Level level, BlockPos start, BlockPos goal, double tolerance, Settings settings) {
		Search s = new Search(level, goal, tolerance, settings);
		return s.run(start);
	}

	/** One-line summary such as {@code path: 23 nodes, 2 step-ups, 1 drop, reaches goal}. */
	public static String describe(Path path) {
		Map<MoveType, Integer> counts = new EnumMap<>(MoveType.class);
		List<PathNode> nodes = path.nodes();
		for (int i = 1; i < nodes.size(); i++) counts.merge(nodes.get(i).how(), 1, Integer::sum);

		StringBuilder sb = new StringBuilder("path: ").append(nodes.size()).append(" nodes");
		appendCount(sb, counts, MoveType.STEP_UP, "step-up");
		appendCount(sb, counts, MoveType.DROP, "drop");
		appendCount(sb, counts, MoveType.JUMP_GAP, "gap jump");
		appendCount(sb, counts, MoveType.CLIMB, "climb");
		appendCount(sb, counts, MoveType.SWIM, "swim");
		appendCount(sb, counts, MoveType.DOOR, "door");
		sb.append(String.format(Locale.ROOT, ", cost %.1f", path.cost()));
		sb.append(path.reachesGoal() ? ", reaches goal" : ", does not reach goal");
		return sb.toString();
	}

	private static void appendCount(StringBuilder sb, Map<MoveType, Integer> counts, MoveType type, String label) {
		int n = counts.getOrDefault(type, 0);
		if (n == 0) return;
		sb.append(", ").append(n).append(' ').append(label);
		if (n > 1) sb.append('s');
	}

	/** What one block cell means to the body. */
	private enum Cell {
		/** Air, or no collision shape. Walk through, cannot stand on. */
		AIR,
		/** Has a collision shape. Blocks the body; may be standable (checked separately). */
		SOLID,
		/** Water the body may swim in. */
		WATER,
		/** Ladder, vine, scaffolding. Passable; the body can hang in it. */
		LADDER,
		/** Closed door or fence gate the body may open. */
		DOOR,
		/** Impassable and never standable: lava, fire, cactus, magma, unloaded, etc. */
		HAZARD
	}

	/** Search node; shared between the open heap and the position map. */
	private static final class Node {
		final BlockPos pos;
		final double h;
		double g;
		Node parent;
		MoveType how;
		boolean closed;

		Node(BlockPos pos, double h) {
			this.pos = pos;
			this.h = h;
		}

		double f() {
			return g + h;
		}
	}

	private static final class Search {
		private final Level level;
		private final BlockPos goal;
		private final double toleranceSq;
		private final Settings cfg;
		private final Map<Long, Node> nodes = new HashMap<>();
		private final Map<Long, Cell> cells = new HashMap<>();
		private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> {
			int c = Double.compare(a.f(), b.f());
			return c != 0 ? c : Double.compare(a.h, b.h);
		});

		Search(Level level, BlockPos goal, double tolerance, Settings cfg) {
			this.level = level;
			this.goal = goal;
			this.toleranceSq = tolerance * tolerance;
			this.cfg = cfg;
		}

		Path run(BlockPos start) {
			Node startNode = node(start);
			startNode.g = 0;
			startNode.how = MoveType.WALK;
			open.add(startNode);
			Node best = startNode;
			int expanded = 0;

			while (!open.isEmpty() && expanded < cfg.maxNodes()) {
				Node cur = open.poll();
				if (cur.closed) continue; // stale heap entry; a cheaper copy was already expanded
				cur.closed = true;
				expanded++;
				if (cur.h < best.h) best = cur;
				if (cur.pos.distSqr(goal) <= toleranceSq) return build(cur, true);
				expand(cur);
			}
			return build(best, false);
		}

		private Path build(Node end, boolean reached) {
			List<PathNode> out = new ArrayList<>();
			for (Node n = end; n != null; n = n.parent) out.add(new PathNode(n.pos, n.how));
			return new Path(out.reversed(), reached, end.g);
		}

		private Node node(BlockPos pos) {
			return nodes.computeIfAbsent(pos.asLong(), k -> new Node(pos, heuristic(pos)));
		}

		/** Octile distance in the horizontal plane plus the vertical gap, weighted for speed. */
		private double heuristic(BlockPos p) {
			int dx = Math.abs(p.getX() - goal.getX());
			int dy = Math.abs(p.getY() - goal.getY());
			int dz = Math.abs(p.getZ() - goal.getZ());
			double flat = Math.max(dx, dz) + (SQRT2 - 1) * Math.min(dx, dz);
			return (flat + dy) * HEURISTIC_WEIGHT;
		}

		private void relax(Node from, BlockPos to, double stepCost, MoveType how) {
			Node n = node(to);
			if (n.closed) return;
			double g = from.g + stepCost;
			if (n.parent != null && g >= n.g) return;
			n.g = g;
			n.parent = from;
			n.how = how;
			open.add(n); // duplicates are skipped on poll via the closed flag
		}

		// ---- move generation ----------------------------------------------------------------

		private void expand(Node cur) {
			BlockPos p = cur.pos;
			Cell feet = cell(p);
			boolean inWater = feet == Cell.WATER;
			boolean onLadder = feet == Cell.LADDER;

			for (Direction d : HORIZONTAL) {
				BlockPos side = p.relative(d);
				tryWalk(cur, side, 1.0, null, null);
				tryStepUp(cur, side);
				if (!inWater) tryDrop(cur, side);
				if (cfg.canJumpGaps() && !inWater && !onLadder) tryJumpGap(cur, d);
			}
			for (int[] dg : DIAGONALS) {
				BlockPos side = p.offset(dg[0], 0, dg[1]);
				tryWalk(cur, side, SQRT2, p.offset(dg[0], 0, 0), p.offset(0, 0, dg[1]));
			}
			if (cfg.canClimb() && onLadder) {
				tryClimb(cur, p.above());
				tryClimb(cur, p.below());
			}
			if (cfg.canSwim() && inWater) {
				trySwimVertical(cur, p.above());
				trySwimVertical(cur, p.below());
			}
		}

		/**
		 * Horizontal move to a neighbour at the same height. For diagonals, both orthogonal
		 * intermediate columns must be clear so the body does not clip a corner.
		 */
		private void tryWalk(Node cur, BlockPos to, double dist, BlockPos viaA, BlockPos viaB) {
			if (!isNode(to)) return;
			boolean door = false;
			if (viaA != null) {
				// Diagonals never pass through doors: a door sits in a wall, not at a corner.
				if (!columnClear(viaA) || !columnClear(viaB)) return;
				if (hasDoor(viaA) || hasDoor(viaB) || hasDoor(to)) return;
			} else {
				door = hasDoor(to);
			}
			Cell feet = cell(to);
			if (feet == Cell.WATER) {
				relax(cur, to, dist * swimCost(to), MoveType.SWIM);
			} else if (feet == Cell.LADDER) {
				relax(cur, to, dist * 2.0, MoveType.CLIMB);
			} else if (door) {
				relax(cur, to, dist + 1.0, MoveType.DOOR);
			} else {
				relax(cur, to, dist, MoveType.WALK);
			}
		}

		/** Jump onto the block one higher in front. Needs headroom above the current head. */
		private void tryStepUp(Node cur, BlockPos side) {
			BlockPos to = side.above();
			if (!passable(cur.pos.above(2))) return;
			if (!isNode(to) || hasDoor(to)) return;
			// Must land on something solid; jumping into a water or ladder column is not a step-up.
			if (cell(to) != Cell.AIR || !standable(side)) return;
			relax(cur, to, 1.5, MoveType.STEP_UP);
		}

		/**
		 * Walk off the edge and fall. The column in front must be open at feet and head, then
		 * every block passed through must be passable until a standable block or water. Water is a
		 * safe landing at any depth; otherwise the fall is capped at maxFall.
		 */
		private void tryDrop(Node cur, BlockPos side) {
			if (!columnClear(side) || hasDoor(side)) return;
			if (standable(side.below())) return; // that is a WALK, not a drop
			int limit = cfg.canSwim() ? cfg.maxFall() + WATER_SCAN_EXTRA : cfg.maxFall();
			for (int fall = 1; fall <= limit; fall++) {
				BlockPos at = side.below(fall);
				Cell c = cell(at);
				if (c == Cell.WATER) {
					relax(cur, at, 1.0 + fall * 0.5, MoveType.DROP);
					return;
				}
				if (c != Cell.AIR && c != Cell.LADDER) return; // hit something we cannot pass
				if (c == Cell.LADDER || standable(at.below())) {
					if (fall > cfg.maxFall()) return;
					relax(cur, at, 1.0 + fall * 0.5, MoveType.DROP);
					return;
				}
			}
		}

		/**
		 * Running jump over a one-block gap, landing level or one block lower. The gap column must
		 * be open at feet, head and one above, and the current head needs one block of clearance.
		 */
		private void tryJumpGap(Node cur, Direction d) {
			BlockPos p = cur.pos;
			BlockPos gap = p.relative(d);
			if (!passable(p.above(2))) return;
			if (!columnClear(gap) || !passable(gap.above(2)) || hasDoor(gap)) return;
			if (standable(gap.below())) return; // not a gap, just a walk
			BlockPos far = gap.relative(d);
			if (!passable(far)) return; // the body crosses the far column at take-off height
			for (BlockPos land : new BlockPos[]{far, far.below()}) {
				if (isNode(land) && cell(land) == Cell.AIR && !hasDoor(land) && standable(land.below())) {
					relax(cur, land, 3.0, MoveType.JUMP_GAP);
					return;
				}
			}
		}

		/** Up or down inside a ladder column, or off the ladder onto a standable block. */
		private void tryClimb(Node cur, BlockPos to) {
			if (!isNode(to)) return;
			Cell feet = cell(to);
			if (feet != Cell.LADDER && !standable(to.below())) return;
			relax(cur, to, 2.0, MoveType.CLIMB);
		}

		/** Vertical swim. Up only into more water; down into water or onto the bottom. */
		private void trySwimVertical(Node cur, BlockPos to) {
			if (!isNode(to)) return;
			if (cell(to) != Cell.WATER && to.getY() > cur.pos.getY()) return;
			relax(cur, to, swimCost(to), MoveType.SWIM);
		}

		private double swimCost(BlockPos feet) {
			return cell(feet.above()) == Cell.WATER ? 3.0 : 2.0; // submerged is slower
		}

		// ---- terrain queries -----------------------------------------------------------------

		/**
		 * A valid node has a passable head block and either feet in water or a ladder, or passable
		 * feet above a standable block.
		 */
		private boolean isNode(BlockPos feet) {
			if (!passable(feet.above())) return false;
			Cell c = cell(feet);
			if (c == Cell.WATER || c == Cell.LADDER) return true;
			return passable(c) && standable(feet.below());
		}

		/** Feet and head both passable. */
		private boolean columnClear(BlockPos feet) {
			return passable(feet) && passable(feet.above());
		}

		private boolean hasDoor(BlockPos feet) {
			return cell(feet) == Cell.DOOR || cell(feet.above()) == Cell.DOOR;
		}

		private boolean passable(BlockPos pos) {
			return passable(cell(pos));
		}

		private static boolean passable(Cell c) {
			return c == Cell.AIR || c == Cell.WATER || c == Cell.LADDER || c == Cell.DOOR;
		}

		/**
		 * Can the body stand on this block? It must have a collision shape whose top is at or
		 * below the block's top (fences and walls are 1.5 tall and reject the body) and must not
		 * be a hazard such as magma or a campfire.
		 */
		private boolean standable(BlockPos pos) {
			if (cell(pos) != Cell.SOLID) return false;
			BlockState state = level.getBlockState(pos);
			if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES)) return false;
			VoxelShape shape = state.getCollisionShape(level, pos);
			return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0;
		}

		private Cell cell(BlockPos pos) {
			return cells.computeIfAbsent(pos.asLong(), k -> classify(pos));
		}

		private Cell classify(BlockPos pos) {
			if (!level.isInWorldBounds(pos) || !level.isLoaded(pos)) return Cell.HAZARD;
			BlockState state = level.getBlockState(pos);
			Block block = state.getBlock();

			if (isHazard(state, block)) return Cell.HAZARD;
			// Ladders have a thin collision shape, so check them before the generic shape test.
			if (state.is(BlockTags.CLIMBABLE)) return Cell.LADDER;
			if (block instanceof DoorBlock) {
				if (state.getValueOrElse(DoorBlock.OPEN, false)) return Cell.AIR;
				return cfg.canOpenDoors() && block != Blocks.IRON_DOOR ? Cell.DOOR : Cell.SOLID;
			}
			if (block instanceof FenceGateBlock) {
				if (state.getValueOrElse(FenceGateBlock.OPEN, false)) return Cell.AIR;
				return cfg.canOpenDoors() ? Cell.DOOR : Cell.SOLID;
			}
			if (!state.getCollisionShape(level, pos).isEmpty()) return Cell.SOLID;
			if (state.getFluidState().is(FluidTags.WATER)) return cfg.canSwim() ? Cell.WATER : Cell.HAZARD;
			return Cell.AIR;
		}

		/** Blocks the body must never enter or stand on. */
		private boolean isHazard(BlockState state, Block block) {
			if (block == Blocks.LAVA || state.getFluidState().is(FluidTags.LAVA)) return true;
			if (state.is(BlockTags.FIRE) || block == Blocks.CACTUS || block == Blocks.POWDER_SNOW) return true;
			if (state.is(BlockTags.CAMPFIRES)) return true;
			if (cfg.allowDangerous()) return false;
			return block == Blocks.MAGMA_BLOCK || block == Blocks.SWEET_BERRY_BUSH;
		}
	}
}
