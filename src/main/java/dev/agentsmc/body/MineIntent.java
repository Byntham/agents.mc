package dev.agentsmc.body;

import dev.agentsmc.perception.BlockCodec;
import dev.agentsmc.perception.Views;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
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
 * for as long as the block takes with the tool in hand, then pick up what dropped.
 */
public final class MineIntent extends Intent {
	private static final int COLLECT_TICKS = 50;
	private static final int BREAK_DELAY = 5;

	private final List<BlockPos> targets;
	private final String what;
	private final Navigator nav;
	private final Inventory snapshot;
	private BlockPos current;
	private BlockState expected;
	private Direction face = Direction.UP;
	private float progress;
	private boolean breaking;
	private int collectTicks, delay, approachTicks;
	private int mined, skipped;
	private final List<String> skippedWhy = new ArrayList<>();

	/** @param what how the model named the blocks, for messages */
	public MineIntent(Body body, List<BlockPos> targets, String what) {
		this.targets = new ArrayList<>(targets);
		this.what = what;
		this.nav = new Navigator(body);
		this.snapshot = new Inventory(body);
	}

	@Override
	public String kind() {
		return "mine";
	}

	@Override
	public String describe() {
		return "mining " + what + (current == null ? "" : " at " + current.getX() + " " + current.getY() + " " + current.getZ());
	}

	@Override
	public String progress() {
		return mined + "/" + (mined + targets.size() + (current != null ? 1 : 0)) + " broken" + (breaking ? String.format(Locale.ROOT, ", %.0f%%", progress * 100) : "");
	}

	@Override
	protected void tick(Body body) {
		if (delay > 0) {
			delay--;
			return;
		}
		if (collectTicks > 0) {
			collectTicks--;
			if (collectDrops(body)) return;
			collectTicks = 0;
		}
		if (current == null) {
			if (!pickNext(body)) {
				String gained = snapshot.gained(body);
				done(String.format(Locale.ROOT, "mined %d %s%s%s", mined, what, gained.isEmpty() ? "" : ", picked up " + gained,
						skipped == 0 ? "" : "; skipped " + skipped + " (" + String.join("; ", skippedWhy) + ")"));
			}
			return;
		}

		BlockState state = body.level().getBlockState(current);
		if (state.isAir() || !state.is(expected.getBlock())) {
			// Someone else got it, or it fell. Count it if it's gone.
			if (breaking) stopBreaking(body);
			if (state.isAir()) mined++;
			current = null;
			collectTicks = COLLECT_TICKS;
			return;
		}

		Vec3 centre = Vec3.atCenterOf(current);
		BlockHitResult sight = body.inReach(current) ? body.sight(current) : null;
		if (sight == null) {
			if (breaking) stopBreaking(body);
			body.face(centre);
			// Stand next to it, not in it: aim for within reach of the block.
			Navigator.State s = nav.toward(centre, 3.2);
			if (s == Navigator.State.UNREACHABLE || s == Navigator.State.STUCK || ++approachTicks > 20 * 30) {
				skip(body, s == Navigator.State.STUCK ? "got stuck on the way" : approachTicks > 20 * 30 ? "took too long to reach" : "no path within reach");
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
		body.face(sight.getLocation());
		if (!body.facing(sight.getLocation(), 15)) return; // finish turning first, like a player would
		face = sight.getDirection();
		if (!breaking) {
			body.holdToolFor(state);
			body.breakAction(current, face, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
			breaking = true;
			progress = 0;
			if (body.level().getBlockState(current).isAir()) { // creative mode breaks instantly
				finishedBlock(body);
				return;
			}
		}
		progress += state.getDestroyProgress(body.agent, body.level(), current);
		if (ticks() % 4 == 0) body.swingArm();
		if (progress >= 1f) {
			body.breakAction(current, face, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
			breaking = false;
			if (body.level().getBlockState(current).isAir()) {
				finishedBlock(body);
			} else {
				// The server's own progress estimate disagreed (e.g. the tool changed). Keep holding.
				progress = 0.9f;
				breaking = true;
			}
		}
	}

	private void finishedBlock(Body body) {
		mined++;
		current = null;
		breaking = false;
		delay = BREAK_DELAY;
		collectTicks = COLLECT_TICKS;
	}

	private void stopBreaking(Body body) {
		if (!breaking || current == null) return;
		body.breakAction(current, face, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
		breaking = false;
		progress = 0;
	}

	private void skip(Body body, String why) {
		skipped++;
		if (skippedWhy.size() < 3) skippedWhy.add(current.getX() + " " + current.getY() + " " + current.getZ() + ": " + why);
		stopBreaking(body);
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
			nav.reset();
			return true;
		}
		return false;
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
		// Stand on it once close: a fresh drop can't be picked up for half a second, and running past it wastes time.
		if (best > 0.6) body.moveToward(nearest.position(), 0.6);
		return true;
	}

	@Override
	protected void stop(Body body) {
		stopBreaking(body);
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
