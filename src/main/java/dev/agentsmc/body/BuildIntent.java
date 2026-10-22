package dev.agentsmc.body;

import dev.agentsmc.perception.BlockCodec;
import dev.agentsmc.perception.EventLog;
import dev.agentsmc.perception.Views;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a plan the way a survival player does: walk within reach of each block, place it from the
 * inventory (sneaking, so a chest or table beside it doesn't open), break blocks that should become
 * air, bottom layer first so there is always something to place against. Blocks the inventory
 * doesn't have are skipped and reported.
 */
public final class BuildIntent extends Intent {
	/** Give up on a block after this long trying to get within reach of it. */
	private static final int APPROACH_TICKS = 20 * 30;

	private record Job(BlockPos pos, BlockCodec.Spec spec) {
	}

	private final List<Job> jobs;
	private final String description;
	private final Navigator nav;
	private final Digger digger;
	private final int total;
	private Job current;
	private int placed, broken, approachTicks, stuck;
	private final Map<String, Integer> missing = new LinkedHashMap<>();
	private final List<String> failed = new ArrayList<>();
	private int sinceProgress;

	public BuildIntent(Body body, String description, Map<BlockPos, BlockCodec.Spec> changes) {
		this.description = description;
		this.jobs = new ArrayList<>();
		changes.forEach((p, spec) -> jobs.add(new Job(p, spec)));
		// Bottom up, then nearest first within a layer: walls grow from the ground and nothing floats.
		Vec3 here = body.agent.position();
		jobs.sort(Comparator.comparingInt((Job j) -> j.pos().getY()).thenComparingDouble(j -> j.pos().distSqr(BlockPos.containing(here))));
		this.total = jobs.size();
		this.nav = new Navigator(body);
		this.nav.dig(Navigator.Dig.NEVER);
		this.digger = new Digger(body);
	}

	@Override
	public String kind() {
		return "build";
	}

	@Override
	public String describe() {
		return description;
	}

	@Override
	public String progress() {
		return (placed + broken) + "/" + total + " blocks" + (missing.isEmpty() ? "" : ", missing " + summary(missing));
	}

	@Override
	protected void tick(Body body) {
		if (current == null) {
			if (!pickNext(body)) {
				String done = String.format(Locale.ROOT, "%s: placed %d, removed %d of %d blocks", description, placed, broken, total);
				if (!missing.isEmpty()) done += "; no " + summary(missing) + " in the inventory";
				if (!failed.isEmpty()) done += "; couldn't do " + failed.size() + " (" + String.join("; ", failed.subList(0, Math.min(3, failed.size()))) + ")";
				done(done);
			}
			return;
		}
		BlockPos pos = current.pos();
		BlockState want = current.spec().state();
		BlockState have = body.level().getBlockState(pos);
		if (current.spec().matches(have)) {
			finish(body, false);
			return;
		}

		// Something is there that shouldn't be: break it first (also how "air" in a plan is done).
		boolean needBreak = !have.isAir() && !have.canBeReplaced();
		Vec3 centre = Vec3.atCenterOf(pos);
		if (!body.inReach(pos) || needBreak && body.sight(pos) == null) {
			body.face(centre);
			Navigator.State s = nav.toward(centre, 3.0);
			if (s == Navigator.State.UNREACHABLE || s == Navigator.State.STUCK || ++approachTicks > APPROACH_TICKS) {
				skip(body, s == Navigator.State.STUCK ? "got stuck getting there" : "couldn't get within reach");
			}
			return;
		}
		approachTicks = 0;

		if (needBreak) {
			BlockHitResult hit = body.sight(pos);
			Digger.Result r = digger.tick(pos, hit);
			if (r == Digger.Result.FAILED) skip(body, "can't break " + BlockCodec.id(have, BlockCodec.Detail.NONE));
			else if (r == Digger.Result.BROKEN && want.isAir()) {
				broken++;
				finish(body, true);
			}
			return;
		}
		if (want.isAir()) {
			finish(body, true); // already air or replaceable grass
			return;
		}

		// Placing: never from inside the target, and only against something solid.
		if (body.agent.getBoundingBox().intersects(new AABB(pos))) {
			stepAside(body, pos);
			if (++stuck > 60) skip(body, "kept standing in the way");
			return;
		}
		stuck = 0;
		BlockPos against = against(body, pos);
		if (against == null) {
			// Nothing to place against yet: its neighbours may come later in the plan. Push it to the end once.
			if (++sinceProgress > 2 * total + 10) {
				skip(body, "nothing to place it against");
			} else {
				jobs.add(current);
				current = null;
			}
			return;
		}
		Item item = want.getBlock().asItem();
		if (item == null || item == net.minecraft.world.item.Items.AIR || !body.hold(st -> st.is(item))) {
			missing.merge(Views.stack(new ItemStack(item == null ? net.minecraft.world.item.Items.AIR : item)), 1, Integer::sum);
			current = null;
			return;
		}
		BlockHitResult hit = body.placementHit(pos, against);
		body.face(hit.getLocation());
		if (!body.facing(hit.getLocation(), 20)) return;
		body.agent.setShiftKeyDown(true);
		InteractionResult r;
		try {
			r = body.useOn(hit);
		} finally {
			body.agent.setShiftKeyDown(false);
		}
		BlockState now = body.level().getBlockState(pos);
		if (now.isAir() || now == have) {
			if (++stuck > 3) skip(body, "placing failed (" + r + ")");
			return;
		}
		placed++;
		finish(body, true);
	}

	private void finish(Body body, boolean progressed) {
		current = null;
		stuck = 0;
		if (progressed) sinceProgress = 0;
	}

	private void skip(Body body, String why) {
		if (failed.size() < 10) failed.add(current.pos().toShortString() + ": " + why);
		digger.abort();
		nav.reset();
		current = null;
		approachTicks = 0;
		stuck = 0;
	}

	private boolean pickNext(Body body) {
		while (!jobs.isEmpty()) {
			// Nearest first among the lowest layer still pending, so the body works along a wall.
			int lowest = jobs.stream().mapToInt(j -> j.pos().getY()).min().orElse(0);
			Job best = null;
			double bestD = Double.MAX_VALUE;
			BlockPos feet = body.agent.blockPosition();
			for (Job j : jobs) {
				if (j.pos().getY() != lowest) continue;
				double d = j.pos().distSqr(feet);
				if (d < bestD) {
					bestD = d;
					best = j;
				}
			}
			jobs.remove(best);
			if (best.spec().matches(body.level().getBlockState(best.pos()))) continue; // already right
			current = best;
			nav.reset();
			return true;
		}
		return false;
	}

	/** A solid neighbour to place against, nearest to the eyes; null if the block would float. */
	private static BlockPos against(Body body, BlockPos pos) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			if (body.level().getBlockState(n).getCollisionShape(body.level(), n).isEmpty()) continue;
			double dist = Vec3.atCenterOf(n).distanceToSqr(body.agent.getEyePosition()) - (d == Direction.DOWN ? 0.5 : 0);
			if (dist < bestD) {
				bestD = dist;
				best = n;
			}
		}
		return best;
	}

	/** Moves off a block that is about to be placed. */
	private void stepAside(Body body, BlockPos pos) {
		Vec3 away = body.agent.position().subtract(Vec3.atCenterOf(pos)).multiply(1, 0, 1);
		if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
		body.moveToward(body.agent.position().add(away.normalize().scale(2)), 0.6);
	}

	private static String summary(Map<String, Integer> counts) {
		List<String> out = new ArrayList<>();
		counts.forEach((k, v) -> out.add(v + "x " + k));
		return String.join(", ", out);
	}

	@Override
	protected void stop(Body body) {
		digger.abort();
		nav.stop();
	}

	/** For the tool's first reply. */
	public String plan() {
		return String.format(Locale.ROOT, "%d blocks to place by hand, bottom layer first", total);
	}

	/** Items the plan needs that the inventory lacks right now, for the tool's first reply. */
	public String shortfall(Body body) {
		Map<String, Integer> need = new LinkedHashMap<>();
		for (Job j : jobs) {
			if (j.spec().state().isAir()) continue;
			Item item = j.spec().state().getBlock().asItem();
			if (item == null) continue;
			need.merge(Views.stack(new ItemStack(item)), 1, Integer::sum);
		}
		Map<String, Integer> have = new LinkedHashMap<>();
		for (ItemStack st : body.agent.getInventory().getNonEquipmentItems()) {
			if (!st.isEmpty()) have.merge(Views.stack(st.copyWithCount(1)), st.getCount(), Integer::sum);
		}
		List<String> out = new ArrayList<>();
		need.forEach((k, v) -> {
			int h = have.getOrDefault(k, 0);
			if (h < v) out.add((v - h) + "x " + k);
		});
		return out.isEmpty() ? "" : "short of " + String.join(", ", out);
	}

	public int size() {
		return total;
	}

	static int salience() {
		return EventLog.WAKE;
	}
}
