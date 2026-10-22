package dev.agentsmc.body.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Fallable;
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
 * <p>With {@link Digging}, moves may also pass through solid blocks the way a survival player digs:
 * 1x2 tunnels, and staircases up and down. The planner never digs the block under the body's own
 * feet, so it never plans a vertical shaft.
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
	/**
	 * Cost of one second spent breaking blocks, in block units. Walking covers about four blocks a
	 * second, so a weight above that makes a short walk around preferable to digging through.
	 */
	public static final double DIG_WEIGHT = 6.0;

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
		return find(level, start, goal, tolerance, settings, null);
	}

	/**
	 * Like {@link #find(Level, BlockPos, BlockPos, double, Settings)}, but moves may break blocks when
	 * {@code digging} is not null. A solid goal block (something to mine) is never dug through or stood on.
	 */
	public static Path find(Level level, BlockPos start, BlockPos goal, double tolerance, Settings settings, Digging digging) {
		Search s = new Search(level, goal, tolerance, settings, digging);
		return s.run(start);
	}

	/**
	 * Whether breaking this one block is safe by the planner's rules: it can be broken, holds no block
	 * entity, has no water or lava beside it, and nothing above it would fall into the gap. The
	 * executor checks this again just before breaking, in case the world changed.
	 */
	public static boolean safeToDig(Level level, BlockPos pos) {
		return digBlocker(level, pos) == null && !fallable(level, pos.above());
	}

	/** Why a block must not be dug, or null if it may be. Ignores what is above it. */
	private static String digBlocker(Level level, BlockPos pos) {
		if (!level.isInWorldBounds(pos) || !level.isLoaded(pos)) return "unloaded";
		BlockState state = level.getBlockState(pos);
		if (state.getDestroySpeed(level, pos) < 0) return "unbreakable";
		if (state.hasBlockEntity()) return "a chest, furnace or other block entity";
		if (!natural(state)) return "something built or placed (" + state.getBlock().getDescriptionId() + ")";
		if (!state.getFluidState().isEmpty()) return "fluid";
		for (Direction d : Direction.values()) {
			if (!level.getFluidState(pos.relative(d)).isEmpty()) return "water or lava next to it";
		}
		return null;
	}

	/**
	 * Whether a block is part of the landscape rather than something someone built. The planner only
	 * digs through these: a crafting table, a cobblestone wall or a door in the way is somebody's work,
	 * and a player walks around it (or asks).
	 */
	public static boolean natural(BlockState state) {
		if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
				|| state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.SNOW) || state.is(BlockTags.ICE) || state.is(BlockTags.TERRACOTTA)
				|| state.is(BlockTags.NYLIUM) || state.is(BlockTags.STONE_ORE_REPLACEABLES) || state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
				|| state.is(BlockTags.MOSS_REPLACEABLE) || state.is(BlockTags.REPLACEABLE)) return true;
		Block b = state.getBlock();
		if (b == Blocks.GRAVEL || b == Blocks.CLAY || b == Blocks.MUD || b == Blocks.MOSS_BLOCK || b == Blocks.BLACKSTONE || b == Blocks.BASALT
				|| b == Blocks.SOUL_SAND || b == Blocks.SOUL_SOIL || b == Blocks.END_STONE || b == Blocks.CALCITE || b == Blocks.DRIPSTONE_BLOCK
				|| b == Blocks.POINTED_DRIPSTONE || b == Blocks.SANDSTONE || b == Blocks.RED_SANDSTONE || b == Blocks.MUSHROOM_STEM
				|| b == Blocks.BROWN_MUSHROOM_BLOCK || b == Blocks.RED_MUSHROOM_BLOCK || b == Blocks.BAMBOO || b == Blocks.SUGAR_CANE
				|| b == Blocks.PACKED_MUD || b == Blocks.ROOTED_DIRT || b == Blocks.SNOW_BLOCK) return true;
		String id = b.getDescriptionId();
		return id.endsWith("_ore") || id.contains("raw_") && id.endsWith("_block");
	}

	/** Gravel, sand, concrete powder, anvils, dripstone: things that fall when what holds them is gone. */
	private static boolean fallable(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getBlock() instanceof Fallable || state.is(Blocks.POWDER_SNOW);
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
		int dig = 0;
		for (PathNode n : nodes) dig += n.dig().size();
		if (dig > 0) sb.append(", ").append(dig).append(dig == 1 ? " block to dig" : " blocks to dig");
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
		List<BlockPos> dig = List.of();
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
		/** Null when only walking is allowed. */
		private final Digging digging;
		/** The goal block when it is solid, i.e. the thing being mined: never dug through or stood on. */
		private final BlockPos protect;
		/** Heuristic weight; higher when digging, where every step costs several block units. */
		private final double hWeight;
		private final Map<Long, Node> nodes = new HashMap<>();
		private final Map<Long, Cell> cells = new HashMap<>();
		/** Seconds to break each block, or -1 where the rules forbid digging. */
		private final Map<Long, Double> digTimes = new HashMap<>();
		private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> {
			int c = Double.compare(a.f(), b.f());
			return c != 0 ? c : Double.compare(a.h, b.h);
		});

		Search(Level level, BlockPos goal, double tolerance, Settings cfg, Digging digging) {
			this.level = level;
			this.goal = goal;
			this.toleranceSq = tolerance * tolerance;
			this.cfg = cfg;
			this.digging = digging;
			this.protect = digging != null && cell(goal) == Cell.SOLID ? goal : null;
			// Weighted toward the goal by what digging through stone costs, so a search through solid
			// rock heads for the goal instead of flooding outward in every direction.
			double stone = digging == null ? -1 : digging.seconds(goal, Blocks.STONE.defaultBlockState());
			this.hWeight = stone > 0 ? HEURISTIC_WEIGHT + stone * DIG_WEIGHT : HEURISTIC_WEIGHT;
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
				if (isGoal(cur.pos)) return build(cur, true);
				expand(cur);
			}
			return build(best, false);
		}

		/**
		 * Within tolerance. When digging, never standing on the block being mined, and never below an
		 * open goal: that would end in a hole dug under the spot instead of on it.
		 */
		private boolean isGoal(BlockPos pos) {
			if (pos.distSqr(goal) > toleranceSq) return false;
			if (digging == null) return true;
			return protect != null ? !pos.below().equals(protect) : pos.getY() >= goal.getY();
		}

		private Path build(Node end, boolean reached) {
			List<PathNode> out = new ArrayList<>();
			for (Node n = end; n != null; n = n.parent) out.add(new PathNode(n.pos, n.how, n.dig));
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
			return (flat + dy) * hWeight;
		}

		private void relax(Node from, BlockPos to, double stepCost, MoveType how) {
			relax(from, to, stepCost, how, List.of());
		}

		private void relax(Node from, BlockPos to, double stepCost, MoveType how, List<BlockPos> dig) {
			Node n = node(to);
			if (n.closed) return;
			double g = from.g + stepCost;
			if (n.parent != null && g >= n.g) return;
			n.g = g;
			n.parent = from;
			n.how = how;
			n.dig = dig;
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
				if (digging != null && !inWater && !onLadder) tryDig(cur, side);
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
				relax(cur, to, dist * swimCost(to) * current(cur.pos, to), MoveType.SWIM);
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
					// A soft landing, but now the body is in the water and has to get out again.
					relax(cur, at, 1.0 + fall * 0.5 + 4.0, MoveType.DROP);
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

		/**
		 * The moves a player makes with a pickaxe, toward one horizontal neighbour. Each lists the
		 * blocks to break top-down, so nothing is left hanging over a gap and each block is in view
		 * once the one above it is gone. None of them digs the block under the body's own feet.
		 */
		private void tryDig(Node cur, BlockPos side) {
			// A 1x2 tunnel: head, then feet.
			digMove(cur, side, 1.0, MoveType.WALK, side.above(), side);
			// A staircase step down: the column ahead from head height down to the new feet.
			digMove(cur, side.below(), 1.5, MoveType.DROP, side.above(), side, side.below());
			// A staircase step up: the block above our head (to jump), then the new head and feet.
			BlockPos up = side.above();
			digMove(cur, up, 1.5, MoveType.STEP_UP, cur.pos.above(2), up.above(), up);
		}

		/**
		 * Relaxes a move into {@code to} that breaks whichever of {@code open} are solid. The new
		 * floor must already be standable. Skipped when nothing needs breaking, since the walking
		 * moves cover that.
		 */
		private void digMove(Node cur, BlockPos to, double base, MoveType how, BlockPos... open) {
			BlockPos floor = to.below();
			if (floor.equals(protect) || !standable(floor)) return;
			List<BlockPos> dig = new ArrayList<>(open.length);
			double seconds = 0;
			for (BlockPos b : open) {
				if (cell(b) == Cell.AIR) continue;
				double t = digTime(b);
				if (t < 0) return;
				dig.add(b);
				seconds += t;
			}
			if (dig.isEmpty()) return;
			// Gravel or sand over a block we open would come down on us, unless we dig it first.
			for (BlockPos b : dig) {
				if (!dig.contains(b.above()) && fallable(level, b.above())) return;
			}
			relax(cur, to, base + seconds * DIG_WEIGHT, how, dig);
		}

		/** Seconds to break a solid block, or -1 if it must not be dug. Cached per search. */
		private double digTime(BlockPos pos) {
			return digTimes.computeIfAbsent(pos.asLong(), k -> {
				if (cell(pos) != Cell.SOLID || pos.equals(protect) || digBlocker(level, pos) != null) return -1.0;
				BlockState state = level.getBlockState(pos);
				if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock) return -1.0;
				return digging.seconds(pos, state);
			});
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

		/**
		 * Flowing water pushes the body. Going with the current is a little cheaper; against it is far
		 * dearer, so the planner goes round where it can, instead of wading in place.
		 */
		private double current(BlockPos from, BlockPos to) {
			var fluid = level.getFluidState(to);
			if (fluid.isEmpty() || fluid.isSource()) return 1.0;
			var flow = fluid.getFlow(level, to);
			if (flow.lengthSqr() < 1e-4) return 1.0;
			double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
			double len = Math.sqrt(dx * dx + dz * dz);
			if (len < 1e-6) return 1.0;
			double dot = (flow.x * dx + flow.z * dz) / (len * flow.length());
			return dot < -0.3 ? 6.0 : dot > 0.3 ? 0.8 : 1.5;
		}

		/**
		 * Water is dear enough that the body walks round a pond unless the detour is long. A player
		 * wades through ankle-deep water, swims across only when there is no shore to follow, and dives
		 * only for a few blocks: under water it drowns, can't sprint and can't fight.
		 */
		private double swimCost(BlockPos feet) {
			if (cell(feet.above()) == Cell.WATER) return 12.0; // submerged
			return standable(feet.below()) ? 3.0 : 5.0; // wading on the bottom vs swimming at the surface
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
				return cfg.canOpenDoors() && ((DoorBlock) block).type().canOpenByHand() ? Cell.DOOR : Cell.SOLID;
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
