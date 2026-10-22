package dev.agentsmc.body;

import dev.agentsmc.body.path.Digging;
import dev.agentsmc.body.path.MoveType;
import dev.agentsmc.body.path.Path;
import dev.agentsmc.body.path.PathFinder;
import dev.agentsmc.body.path.PathNode;
import dev.agentsmc.body.path.Settings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Walks the body to a goal: plans a path over the block grid, follows it node by node with jumps,
 * swims and door opening, and re-plans when stuck or when the goal moves. With digging allowed it
 * also breaks through solid blocks, by tunnel and staircase, when walking can't get there. Intents
 * call {@link #toward} every tick and read the result.
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

	/** When the planner may break blocks. */
	public enum Dig {
		NEVER,
		/** Plan a walk first; dig only if walking can't get there. */
		WHEN_NEEDED,
		/** Plan with digging straight away. */
		ALWAYS
	}

	/** Re-plan when a moving goal drifts this far from the goal the path was planned for. */
	private static final double REPLAN_DRIFT = 2.0;
	private static final int STUCK_TICKS = 40;
	private static final int MAX_REPLANS = 4;
	private static final int REPLAN_COOLDOWN = 10;
	/** Seconds per block of walking, for time estimates (a player walks about 4.3 blocks a second). */
	private static final double WALK_SECONDS = 0.25;

	private final Body body;
	private final Settings settings;
	private final Digger digger;
	private Dig dig = Dig.NEVER;
	/** Arrival tolerance while following a path that digs; see {@link #digReach}. */
	private double digReach = Double.MAX_VALUE;
	/** Set when the last plan needed a drop deeper than the usual limit. */
	private boolean deepDrop;
	/** Set when the current path breaks blocks. */
	private boolean digPath;
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
		this.digger = new Digger(body);
	}

	/** Allows or forbids breaking blocks on the way. Takes effect at the next plan. */
	public Navigator dig(Dig mode) {
		this.dig = mode;
		return this;
	}

	/**
	 * A tighter arrival tolerance for paths that dig. Mining uses it: in open air, standing within
	 * three blocks of a block is enough, but underground it means stone in between.
	 */
	public Navigator digReach(double tolerance) {
		this.digReach = tolerance;
		return this;
	}

	/** Forget the current path, e.g. when the goal changes to something unrelated. */
	public void reset() {
		digger.abort();
		path = null;
		plannedGoal = null;
		digPath = false;
		replans = 0;
		noProgress = 0;
	}

	/** Lets go of any half-broken block. Call from the intent's stop. */
	public void stop() {
		digger.abort();
	}

	/** One line on the plan, for tool output. */
	public String describe() {
		return path == null ? "no path yet" : PathFinder.describe(path);
	}

	/** A remark for tool output, e.g. that the route includes a long drop. Empty if nothing to say. */
	public String note() {
		return deepDrop ? " (includes a drop of more than 3 blocks)" : "";
	}

	/** Whether the current path breaks blocks. */
	public boolean digging() {
		return digPath;
	}

	/** Blocks broken on the way so far. */
	public int dug() {
		return digger.broken();
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
		double tol = digPath ? Math.min(tolerance, digReach) : tolerance;
		if (arrived(goal, tol)) {
			digger.abort();
			return State.ARRIVED;
		}

		if (cooldown > 0) cooldown--;
		boolean needPlan = path == null || plannedGoal == null || plannedGoal.distanceTo(goal) > REPLAN_DRIFT;
		if (needPlan && cooldown == 0) {
			if (!plan(goal, tolerance)) return State.UNREACHABLE;
		}
		if (path == null) return State.MOVING; // waiting for the cooldown
		tol = digPath ? Math.min(tolerance, digReach) : tolerance;

		if (index >= path.nodes().size()) {
			// Walked the whole path. Either it was a partial path (goal out of planning range) or the goal moved.
			if (!path.reachesGoal() && cooldown == 0) {
				if (!plan(goal, tolerance)) return State.UNREACHABLE;
				if (index >= path.nodes().size()) return State.UNREACHABLE;
			} else if (path.reachesGoal()) {
				// Planner thought the last node was within tolerance; close the last half block directly.
				body.moveToward(goal, 0.6);
				return arrived(goal, tol + 0.5) ? State.ARRIVED : State.MOVING;
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

		// Digging: clear the way into the next node before stepping.
		if (!node.dig().isEmpty()) {
			State s = digStep(node);
			if (s != null) return s;
		}

		// Movement.
		boolean inWater = agent.isInWater();
		boolean sprint = remaining() > 6 && !inWater && node.how() == MoveType.WALK && node.dig().isEmpty();
		body.sprint(sprint || node.how() == MoveType.JUMP_GAP);
		body.moveToward(target, 1.0);
		openDoors(node);

		// Jumping.
		if (inWater) {
			// Feet wet but standing on the bottom: walk. Jumping from the floor of shallow water just
			// bounces the body in and out of it. Hold jump only to stay afloat in deep water, to surface,
			// or to climb to a higher node.
			boolean deep = !agent.onGround();
			if (agent.isUnderWater() || deep && dy > -0.5 || dy > 0.3) body.jump();
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

	/**
	 * Within tolerance of the goal. On a path that digs to an open spot, not while still below it:
	 * one block under a surface goal is within 1.5 blocks, but it's a hole, not the surface.
	 */
	private boolean arrived(Vec3 goal, double tol) {
		var agent = body.agent;
		if (agent.position().distanceTo(goal) > tol) return false;
		if (!digPath || agent.getY() > goal.y - 0.6) return true;
		BlockPos g = BlockPos.containing(goal);
		return !agent.level().getBlockState(g).getCollisionShape(agent.level(), g).isEmpty(); // mining: next to the block is enough
	}

	/**
	 * Breaks the next block standing between the body and {@code node}, top block first, from where
	 * the body stands. Returns null once the way is clear, so the caller steps into the node.
	 */
	private State digStep(PathNode node) {
		var level = body.level();
		BlockPos block = null;
		for (BlockPos b : node.dig()) {
			if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()) {
				block = b;
				break;
			}
		}
		if (block == null) return null;
		noProgress = 0;
		bestDist = Double.MAX_VALUE;
		// The world may have changed since the plan: water flowed in, gravel landed, we got knocked away.
		if (!digger.breaking() && (!PathFinder.safeToDig(level, block) || !body.inReach(block))) return replan();
		// Stay in the middle of the block we dig from, so the next step lines up.
		Vec3 stand = Vec3.atBottomCenterOf(path.nodes().get(Math.max(0, index - 1)).pos());
		if (horizontal(body.agent.position(), stand) > 0.25) body.moveToward(stand, 0.3);
		BlockHitResult hit = Digger.hitOn(body, block);
		Digger.Result dug = digger.tick(block, hit);
		if (dug == Digger.Result.BROKEN) body.maybeLight();
		return dug == Digger.Result.FAILED ? replan() : State.MOVING;
	}

	private State replan() {
		digger.abort();
		path = null;
		cooldown = REPLAN_COOLDOWN;
		return ++replans > MAX_REPLANS ? State.STUCK : State.MOVING;
	}

	private boolean plan(Vec3 goal, double tolerance) {
		var agent = body.agent;
		var level = agent.level();
		BlockPos start = agent.blockPosition();
		BlockPos to = BlockPos.containing(goal);
		Path p = null;
		if (dig != Dig.ALWAYS) {
			// In water the feet block is the water block itself; on land it's the air block above the floor.
			p = PathFinder.find(level, start, to, Math.max(tolerance, 0.5), settings);
			planned++;
			// A hole or a cliff: players drop further than three blocks when they have to. A six-block
			// fall costs a heart and a half, which beats not getting there.
			if (!p.reachesGoal() && goal.y < agent.getY() - 3) {
				Settings deeper = new Settings(6, settings.canSwim(), settings.canClimb(), settings.canJumpGaps(), settings.canOpenDoors(), settings.maxNodes(), settings.allowDangerous());
				Path q = PathFinder.find(level, start, to, Math.max(tolerance, 0.5), deeper);
				if (q.reachesGoal()) {
					p = q;
					deepDrop = true;
				}
			}
		}
		digPath = false;
		if (dig != Dig.NEVER && (p == null || !p.reachesGoal())) {
			// Walking can't get there: dig, like a player with a pickaxe would.
			Path d = PathFinder.find(level, start, to, Math.max(Math.min(tolerance, digReach), 0.5), settings, breakTimes());
			planned++;
			if (p == null || d.reachesGoal() || distance(d, goal) < distance(p, goal) - 2) {
				p = d;
				digPath = d.nodes().stream().anyMatch(n -> !n.dig().isEmpty());
			}
		}
		if (p.nodes().isEmpty()) {
			path = null;
			return false;
		}
		path = p;
		plannedGoal = goal;
		index = p.nodes().size() > 1 ? 1 : 0; // node 0 is where we stand
		bestDist = Double.MAX_VALUE;
		noProgress = 0;
		return p.reachesGoal() || p.nodes().size() > 1;
	}

	/** Break times as this body would see them, with the tools it carries. */
	private Digging breakTimes() {
		return (pos, state) -> Digger.seconds(body, pos, state, null);
	}

	private static double distance(Path p, Vec3 goal) {
		return Vec3.atBottomCenterOf(p.end().pos()).distanceTo(goal);
	}

	// ---- telling the model ----------------------------------------------------------------------

	/**
	 * The plan in words, e.g. "walking 12 blocks, then digging a staircase 9 blocks down (27 blocks
	 * to break), about 40 s". When the digging would go much faster with a pickaxe, it says so, so
	 * the model can decide to make one first.
	 */
	public String summary() {
		if (path == null) return "no path yet";
		if (!digPath) return describe() + note();
		var level = body.level();
		ItemStack pick = new ItemStack(Items.STONE_PICKAXE);
		List<String> legs = new ArrayList<>();
		double walkSeconds = 0, digSeconds = 0, pickSeconds = 0;
		List<PathNode> nodes = path.nodes();
		int i = 1;
		while (i < nodes.size()) {
			boolean digs = !nodes.get(i).dig().isEmpty();
			int start = i, blocks = 0;
			// A leg is a run of digging or walking moves; a short walk between digs stays in the dig leg.
			while (i < nodes.size() && (!nodes.get(i).dig().isEmpty() == digs || digs && i + 1 < nodes.size() && !nodes.get(i + 1).dig().isEmpty())) {
				for (BlockPos b : nodes.get(i).dig()) {
					BlockState s = level.getBlockState(b);
					digSeconds += Math.max(0, Digger.seconds(body, b, s, null));
					pickSeconds += Math.max(0, Digger.seconds(body, b, s, pick));
					blocks++;
				}
				walkSeconds += WALK_SECONDS;
				i++;
			}
			int steps = i - start;
			int dy = nodes.get(i - 1).pos().getY() - nodes.get(start - 1).pos().getY();
			if (!digs) {
				legs.add("walking " + steps + (steps == 1 ? " block" : " blocks"));
			} else {
				String shape = dy <= -2 ? "a staircase " + -dy + " blocks down" : dy >= 2 ? "a staircase " + dy + " blocks up" : "a tunnel " + steps + (steps == 1 ? " block" : " blocks") + " long";
				legs.add("digging " + shape + " (" + blocks + (blocks == 1 ? " block" : " blocks") + " to break)");
			}
		}
		StringBuilder sb = new StringBuilder(String.join(", then ", legs));
		sb.append(", about ").append(duration(walkSeconds + digSeconds));
		if (!path.reachesGoal()) sb.append(" for this first part; the rest gets planned on the way");
		if (digSeconds > 2 * pickSeconds && digSeconds - pickSeconds > 10) {
			boolean hasPick = Body.toolScore(Digger.bestTool(body, Blocks.STONE.defaultBlockState(), null), Blocks.STONE.defaultBlockState()) > 0;
			sb.append(hasPick ? "; with the tools you have the digging takes about " : "; by hand the digging takes about ").append(duration(digSeconds))
					.append(", a stone pickaxe would make it about ").append(duration(pickSeconds));
		}
		return sb.toString();
	}

	static String duration(double seconds) {
		if (seconds < 90) return String.format(Locale.ROOT, "%.0f s", Math.max(1, seconds));
		return String.format(Locale.ROOT, "%.0f minutes", seconds / 60);
	}

	// ---- following the path ---------------------------------------------------------------------

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
