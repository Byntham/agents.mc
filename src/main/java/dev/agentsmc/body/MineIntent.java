package dev.agentsmc.body;

import dev.agentsmc.body.path.PathFinder;
import dev.agentsmc.perception.BlockCodec;
import dev.agentsmc.perception.Views;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Break blocks like a survival player: walk within reach, look at the block, hold the mouse button
 * for as long as the block takes with the tool in hand, then pick up what dropped. With digging
 * allowed, buried blocks are reached by digging a staircase or tunnel to them, and a block in the
 * line of sight is broken out of the way.
 */
public final class MineIntent extends Intent {
	private static final int COLLECT_TICKS = 100;
	/** In the open, anywhere within reach will do. */
	private static final double REACH = 3.2;
	/** Underground, stand right next to the block, or there's stone in between. */
	private static final double DIG_REACH = 1.5;

	private final List<BlockPos> targets;
	private final String what;
	private final Navigator nav;
	private final Digger digger;
	private final Inventory snapshot;
	private boolean dig;
	private BlockPos current;
	private BlockState expected;
	/** A block in the line of sight that we're breaking out of the way, with digging on. */
	private BlockPos blocking;
	private double reach = REACH;
	private int collectTicks, approachTicks;
	private int mined, skipped, cleared;
	private final List<String> skippedWhy = new ArrayList<>();

	/** @param what how the model named the blocks, for messages */
	public MineIntent(Body body, List<BlockPos> targets, String what) {
		this.targets = new ArrayList<>(targets);
		this.what = what;
		this.nav = new Navigator(body).digReach(DIG_REACH);
		this.digger = new Digger(body);
		this.snapshot = new Inventory(body);
	}

	/** Allow digging to reach buried blocks. */
	public MineIntent dig(boolean on) {
		this.dig = on;
		nav.dig(on ? Navigator.Dig.WHEN_NEEDED : Navigator.Dig.NEVER);
		return this;
	}

	@Override
	public String kind() {
		return "mine";
	}

	@Override
	public String describe() {
		return (nav.digging() ? "digging to " : "mining ") + what + (current == null ? "" : " at " + current.getX() + " " + current.getY() + " " + current.getZ());
	}

	@Override
	public String progress() {
		return mined + "/" + (mined + targets.size() + (current != null ? 1 : 0)) + " broken"
				+ (digger.breaking() ? String.format(Locale.ROOT, ", %.0f%%", digger.progress() * 100) : "")
				+ (dug() > 0 ? ", dug " + dug() + " on the way" : "");
	}

	/** Blocks broken to get at the targets: the way there plus anything in the line of sight. */
	private int dug() {
		return nav.dug() + cleared;
	}

	@Override
	protected void tick(Body body) {
		if (collectTicks > 0) {
			collectTicks--;
			if (collectDrops(body)) return;
			collectTicks = 0;
		}
		if (current == null) {
			if (!pickNext(body)) {
				String gained = snapshot.gained(body);
				done(String.format(Locale.ROOT, "mined %d %s%s%s%s", mined, what, gained.isEmpty() ? "" : ", picked up " + gained,
						dug() == 0 ? "" : "; dug through " + dug() + " other block" + (dug() == 1 ? "" : "s") + " to get there",
						skipped == 0 ? "" : "; skipped " + skipped + " (" + String.join("; ", skippedWhy) + ")"));
			}
			return;
		}

		BlockState state = body.level().getBlockState(current);
		if (state.isAir() || !state.is(expected.getBlock())) {
			// Someone else got it, or it fell. Count it if it's gone.
			digger.abort();
			if (state.isAir()) mined++;
			current = null;
			collectTicks = COLLECT_TICKS;
			return;
		}

		BlockHitResult sight = body.inReach(current) ? body.sight(current) : null;
		if (sight == null) {
			if (!digger.breaking()) body.face(Vec3.atCenterOf(current));
			Navigator.State s = nav.toward(Vec3.atCenterOf(current), reach);
			// Arrived, but a block is in the way: break it rather than walk around, as a player would.
			// Only once arrived, so a planned staircase isn't cut short by a diagonal hole.
			if (s == Navigator.State.ARRIVED && dig && body.inReach(current) && clearLineOfSight(body)) return;
			// Digging by hand can take minutes; walking shouldn't take more than half of one.
			int limit = nav.digging() ? 20 * 60 * 10 : 20 * 30;
			if (s == Navigator.State.UNREACHABLE || s == Navigator.State.STUCK || ++approachTicks > limit) {
				skip(body, s == Navigator.State.STUCK ? "got stuck on the way" : approachTicks > limit ? "took too long to reach" : dig ? "no way there, even digging" : "no path within reach");
				return;
			}
			if (s == Navigator.State.ARRIVED && body.inReach(current) && body.sight(current) == null) {
				// Close enough but something's in the line of sight (a leaf, a step). Try from the block's side.
				if (approachTicks % 20 == 19) nav.reset();
			}
			return;
		}
		approachTicks = 0;

		// Within reach with a clear line of sight: dig.
		switch (digger.tick(current, sight)) {
			case BROKEN -> {
				mined++;
				current = null;
				collectTicks = COLLECT_TICKS;
			}
			case FAILED -> skip(body, BlockCodec.id(state) + " can't be broken");
			case BUSY -> {
			}
		}
	}

	/**
	 * With the target in reach but a block in the way, breaks that block, as a player would. Never the
	 * block under our own feet, and only where digging is safe. Returns false if there's nothing it can
	 * break, and then the approach comes closer instead.
	 */
	private boolean clearLineOfSight(Body body) {
		var level = body.level();
		BlockPos b = blocking != null && !level.getBlockState(blocking).isAir() ? blocking : Digger.blocker(body, current);
		if (b == null || b.equals(body.agent.blockPosition().below()) || !body.inReach(b) || !digger.breaking() && !PathFinder.safeToDig(level, b)) {
			blocking = null;
			if (reach > DIG_REACH) {
				reach = DIG_REACH; // stand right next to it instead
				nav.reset();
			}
			return false;
		}
		blocking = b;
		switch (digger.tick(b, Digger.hitOn(body, b))) {
			case BROKEN -> {
				cleared++;
				blocking = null;
			}
			case FAILED -> {
				blocking = null;
				return false;
			}
			case BUSY -> {
			}
		}
		return true;
	}

	private void skip(Body body, String why) {
		skipped++;
		if (skippedWhy.size() < 3) skippedWhy.add(current.getX() + " " + current.getY() + " " + current.getZ() + ": " + why);
		digger.abort();
		current = null;
		nav.reset();
		approachTicks = 0;
	}

	/** Nearest remaining target that still exists. */
	private boolean pickNext(Body body) {
		Vec3 here = body.agent.position();
		targets.sort(Comparator.comparingDouble(p -> p.distSqr(BlockPos.containing(here))));
		while (!targets.isEmpty()) {
			BlockPos p = targets.removeFirst();
			BlockState s = body.level().getBlockState(p);
			if (s.isAir() || s.getFluidState().isSource() && s.getCollisionShape(body.level(), p).isEmpty()) continue;
			if (s.getDestroySpeed(body.level(), p) < 0) {
				skipped++;
				if (skippedWhy.size() < 3) skippedWhy.add(BlockCodec.id(s) + " can't be broken");
				continue;
			}
			current = p;
			expected = s;
			blocking = null;
			reach = REACH;
			nav.reset();
			return true;
		}
		return false;
	}

	/**
	 * Plans the way to the first block now, so the tool can say whether reaching it means digging and
	 * how long that takes. Empty when it's a plain walk. The intent keeps the path.
	 */
	public String planNow(Body body) {
		if (current == null && !pickNext(body)) return "";
		if (body.inReach(current) && body.sight(current) != null) return "";
		Navigator.State s = nav.toward(Vec3.atCenterOf(current), reach);
		if (s == Navigator.State.UNREACHABLE) return dig ? "no way to it found, even digging" : "no walking path to it; dig=true would dig there";
		return nav.digging() ? "getting there means " + nav.summary() : "";
	}

	/** Walks onto nearby drops so they get picked up. Returns false when there is nothing to collect. */
	private boolean collectDrops(Body body) {
		List<ItemEntity> items = body.level().getEntitiesOfClass(ItemEntity.class, body.agent.getBoundingBox().inflate(6, 3, 6), e -> true);
		ItemEntity nearest = null;
		double best = Double.MAX_VALUE;
		for (ItemEntity e : items) {
			double d = e.distanceToSqr(body.agent);
			if (d < best) {
				best = d;
				nearest = e;
			}
		}
		if (nearest == null) return false;
		body.face(nearest.position());
		// A drop down in a hole (where a mined block was) often lies at its edge. Aim for the middle of
		// the hole, or the body stays propped on the rim, too high to pick it up.
		Vec3 to = Math.abs(nearest.getY() - body.agent.getY()) > 0.5 ? Vec3.atBottomCenterOf(nearest.blockPosition()) : nearest.position();
		// Stand on it once close: a fresh drop can't be picked up for half a second, and running past it wastes time.
		if (to.subtract(body.agent.position()).horizontalDistanceSqr() > 0.04 || best > 0.6) body.moveToward(to, 0.6);
		return true;
	}

	@Override
	protected void stop(Body body) {
		digger.abort();
		nav.stop();
	}

	/** Counts of items in the inventory, to report what mining gained. */
	static final class Inventory {
		private final java.util.Map<String, Integer> counts = new java.util.HashMap<>();

		Inventory(Body body) {
			var inv = body.agent.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack s = inv.getItem(i);
				if (!s.isEmpty()) counts.merge(Views.stack(s.copyWithCount(1)), s.getCount(), Integer::sum);
			}
		}

		String gained(Body body) {
			Inventory now = new Inventory(body);
			List<String> out = new ArrayList<>();
			for (var e : now.counts.entrySet()) {
				int diff = e.getValue() - counts.getOrDefault(e.getKey(), 0);
				if (diff > 0) out.add(diff + "x " + e.getKey());
			}
			return String.join(", ", out);
		}
	}

	/** The box around a set of targets, for messages. */
	static AABB bounds(List<BlockPos> targets) {
		AABB box = null;
		for (BlockPos p : targets) box = box == null ? new AABB(p) : box.minmax(new AABB(p));
		return box;
	}
}
