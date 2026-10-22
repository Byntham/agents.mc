package dev.agentsmc.body;

import dev.agentsmc.body.path.MoveType;
import dev.agentsmc.body.path.Path;
import dev.agentsmc.body.path.PathFinder;
import dev.agentsmc.body.path.PathNode;
import dev.agentsmc.body.path.Settings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Walks the body to a goal: plans a path over the block grid, follows it node by node with jumps,
 * swims and door opening, and re-plans when stuck or when the goal moves. Intents call
 * {@link #toward} every tick and read the result.
 */
public final class Navigator {
	public enum State {
		/** Within tolerance of the goal. */
		ARRIVED,
		MOVING,
		/** The planner can't find a way, and we're as close as it could get. */
		UNREACHABLE,
		/** Re-planned several times without getting anywhere. */
		STUCK
	}

	/** Re-plan when a moving goal drifts this far from the goal the path was planned for. */
	private static final double REPLAN_DRIFT = 2.0;
	private static final int STUCK_TICKS = 40;
	private static final int MAX_REPLANS = 4;
	private static final int REPLAN_COOLDOWN = 10;

	private final Body body;
	private final Settings settings;
	private Path path;
	private int index;
	private Vec3 plannedGoal;
	private double bestDist;
	private int noProgress, replans, cooldown;
	private int planned;

	public Navigator(Body body) {
		this(body, Settings.DEFAULT);
	}

	public Navigator(Body body, Settings settings) {
		this.body = body;
		this.settings = settings;
	}

	/** Forget the current path, e.g. when the goal changes to something unrelated. */
	public void reset() {
		path = null;
		plannedGoal = null;
		replans = 0;
		noProgress = 0;
	}

	/** One line on the plan, for tool output. */
	public String describe() {
		return path == null ? "no path yet" : PathFinder.describe(path);
	}

	/** Nodes left to walk. */
	public int remaining() {
		return path == null ? 0 : Math.max(0, path.nodes().size() - index);
	}

	public int planned() {
		return planned;
	}

	/**
	 * Steers toward {@code goal} this tick. {@code tolerance} is how close (in blocks, 3D, from the feet)
	 * counts as arrived.
	 */
	public State toward(Vec3 goal, double tolerance) {
		var agent = body.agent;
		if (agent.position().distanceTo(goal) <= tolerance) return State.ARRIVED;

		if (cooldown > 0) cooldown--;
		boolean needPlan = path == null || plannedGoal == null || plannedGoal.distanceTo(goal) > REPLAN_DRIFT;
		if (needPlan && cooldown == 0) {
			if (!plan(goal, tolerance)) return State.UNREACHABLE;
		}
		if (path == null) return State.MOVING; // waiting for the cooldown

		if (index >= path.nodes().size()) {
			// Walked the whole path. Either it was a partial path (goal out of planning range) or the goal moved.
			if (!path.reachesGoal() && cooldown == 0) {
				if (!plan(goal, tolerance)) return State.UNREACHABLE;
				if (index >= path.nodes().size()) return State.UNREACHABLE;
			} else if (path.reachesGoal()) {
				// Planner thought the last node was within tolerance; close the last half block directly.
				body.moveToward(goal, 0.6);
				return agent.position().distanceTo(goal) <= tolerance + 0.5 ? State.ARRIVED : State.MOVING;
			} else {
				return State.MOVING;
			}
		}

		PathNode node = path.nodes().get(index);
		Vec3 target = Vec3.atBottomCenterOf(node.pos());
		double hd = horizontal(agent.position(), target);
		double dy = node.pos().getY() - agent.getY();

		// Advance when standing in the node's column. Water and ladders are forgiving vertically.
		double vTol = node.how() == MoveType.SWIM || node.how() == MoveType.CLIMB ? 1.2 : 0.8;
		if (hd < 0.35 && Math.abs(dy) < vTol || hd < 0.6 && Math.abs(dy) < 0.3 && index + 1 < path.nodes().size() && continuesStraight(index)) {
			index++;
			bestDist = Double.MAX_VALUE;
			noProgress = 0;
			if (index >= path.nodes().size()) return toward(goal, tolerance);
			node = path.nodes().get(index);
			target = Vec3.atBottomCenterOf(node.pos());
			hd = horizontal(agent.position(), target);
			dy = node.pos().getY() - agent.getY();
		}

		// Movement.
		boolean inWater = agent.isInWater();
		boolean sprint = remaining() > 6 && !inWater && node.how() == MoveType.WALK;
		body.sprint(sprint || node.how() == MoveType.JUMP_GAP);
		body.moveToward(target, 1.0);
		openDoors(node);

		// Jumping.
		if (inWater) {
			if (dy > -0.5 || agent.isUnderWater()) body.jump();
		} else if (node.how() == MoveType.STEP_UP && dy > 0.3 && hd < 1.3) {
			body.jump();
		} else if (node.how() == MoveType.JUMP_GAP && hd < 1.7 && agent.onGround()) {
			body.jump();
		} else if (node.how() == MoveType.CLIMB && dy > 0.2) {
			body.jump();
		} else if (agent.horizontalCollision && dy >= -0.1 && agent.onGround()) {
			body.jump(); // a lip the planner didn't see; a hop usually clears it
		}

		// Stuck detection: no progress toward the current node for a while means the world isn't what
		// the plan assumed (a mob in the way, a block changed, a misjudged jump).
		double d = hd + Math.abs(dy);
		if (d < bestDist - 0.05) {
			bestDist = d;
			noProgress = 0;
		} else if (++noProgress > STUCK_TICKS) {
			noProgress = 0;
			if (++replans > MAX_REPLANS) return State.STUCK;
			path = null;
			cooldown = REPLAN_COOLDOWN;
			body.jump(); // often enough to get off a ledge or out of a hole
		}
		return State.MOVING;
	}

	private boolean plan(Vec3 goal, double tolerance) {
		var agent = body.agent;
		BlockPos start = agent.blockPosition();
		// In water the feet block is the water block itself; on land it's the air block above the floor.
		Path p = PathFinder.find(agent.level(), start, BlockPos.containing(goal), Math.max(tolerance, 0.5), settings);
		planned++;
		if (p == null || p.nodes().isEmpty()) {
			path = null;
			return false;
		}
		path = p;
		plannedGoal = goal;
		index = p.nodes().size() > 1 ? 1 : 0; // node 0 is where we stand
		bestDist = Double.MAX_VALUE;
		noProgress = 0;
		if (!p.reachesGoal() && p.nodes().size() <= 1) return false;
		return true;
	}

	/** Whether the next node is in the same direction, so we can cut the corner at this one. */
	private boolean continuesStraight(int i) {
		PathNode a = path.nodes().get(i), b = path.nodes().get(i + 1);
		if (b.how() != MoveType.WALK || a.pos().getY() != b.pos().getY()) return false;
		if (i == 0) return false;
		PathNode prev = path.nodes().get(i - 1);
		int dx1 = a.pos().getX() - prev.pos().getX(), dz1 = a.pos().getZ() - prev.pos().getZ();
		int dx2 = b.pos().getX() - a.pos().getX(), dz2 = b.pos().getZ() - a.pos().getZ();
		return dx1 == dx2 && dz1 == dz2;
	}

	/** Opens a closed door or gate at or just before the node. Returns true if one was opened this tick. */
	private boolean openDoors(PathNode node) {
		var level = body.agent.level();
		for (BlockPos p : new BlockPos[]{node.pos(), node.pos().above()}) {
			if (body.agent.position().distanceTo(Vec3.atBottomCenterOf(p)) > 2.2) continue;
			BlockState s = level.getBlockState(p);
			if (s.getBlock() instanceof DoorBlock door) {
				if (!door.isOpen(s)) {
					door.setOpen(body.agent, level, s, p, true);
					body.swingArm();
					return true;
				}
			} else if (s.getBlock() instanceof FenceGateBlock && !s.getValue(FenceGateBlock.OPEN)) {
				level.setBlockAndUpdate(p, s.setValue(FenceGateBlock.OPEN, true));
				body.swingArm();
				return true;
			}
		}
		return false;
	}

	private static double horizontal(Vec3 a, Vec3 b) {
		double dx = a.x - b.x, dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}
}
