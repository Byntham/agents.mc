package dev.agentsmc.body;

import dev.agentsmc.perception.BlockCodec;
import dev.agentsmc.perception.EventLog;
import dev.agentsmc.perception.Views;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a plan out of the inventory, at the site. The body walks to the build and then places and
 * removes blocks anywhere within {@link #RADIUS} of itself, any height, a few blocks a second, bottom
 * layer first. No block to click, no line of sight, no scaffolding: a player manages those with hands
 * and eyes the body hasn't got. What it does need is the materials, since every block placed comes
 * out of the inventory and every block removed goes into it, and to be there while it works. Taking
 * a block out waits its break time first, as mining would.
 */
public final class BuildIntent extends Intent {
	/** How far from the body a block may be placed or removed. */
	public static final double RADIUS = 24.0;
	/** Ticks between blocks placed: a few a second, so a wall visibly grows rather than appears. */
	private static final int PLACE_TICKS = 4;
	/** Give up on a block after this long trying to get within range of it. */
	private static final int APPROACH_TICKS = 20 * 30;

	private record Job(BlockPos pos, BlockCodec.Spec spec) {
	}

	private final List<Job> jobs;
	private final String description;
	private final Navigator nav;
	private final int total;
	private final Vec3 site;
	private Job current;
	/** The block whose break time is being waited out, so cancelling the job doesn't skip the wait. */
	private BlockPos breaking;
	private int placed, broken, approachTicks, stuck, cooldown;
	private final Map<String, Integer> missing = new LinkedHashMap<>();
	private final List<String> failed = new ArrayList<>();

	public BuildIntent(Body body, String description, Map<BlockPos, BlockCodec.Spec> changes) {
		this.description = description;
		this.jobs = new ArrayList<>();
		changes.forEach((p, spec) -> jobs.add(new Job(p, spec)));
		// Bottom up, then nearest first within a layer: walls grow from the ground, along the wall.
		Vec3 here = body.agent.position();
		jobs.sort(Comparator.comparingInt((Job j) -> j.pos().getY()).thenComparingDouble(j -> j.pos().distSqr(BlockPos.containing(here))));
		this.total = jobs.size();
		double x = 0, z = 0;
		int minY = Integer.MAX_VALUE;
		for (Job j : jobs) {
			x += j.pos().getX() + 0.5;
			z += j.pos().getZ() + 0.5;
			minY = Math.min(minY, j.pos().getY());
		}
		this.site = jobs.isEmpty() ? here : new Vec3(x / jobs.size(), minY, z / jobs.size());
		this.nav = new Navigator(body);
		this.nav.dig(Navigator.Dig.NEVER);
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
		if (cooldown > 0) {
			cooldown--;
			return;
		}
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

		// Be at the site first: within range of this block, walking toward the middle of the build
		// so one spot serves most of it. The world isn't read until then, so a far plan never pulls
		// in chunks the body isn't near.
		Vec3 centre = Vec3.atCenterOf(pos);
		Vec3 here = body.agent.position();
		if (Math.hypot(here.x - centre.x, here.z - centre.z) > RADIUS - 2 || !body.level().hasChunkAt(pos)) {
			body.face(centre);
			Vec3 goal = new Vec3(site.x, here.y, site.z);
			Navigator.State s = nav.toward(goal.distanceTo(centre) < RADIUS - 6 ? goal : centre, 6.0);
			if (s == Navigator.State.UNREACHABLE || s == Navigator.State.STUCK || ++approachTicks > APPROACH_TICKS) {
				skip(s == Navigator.State.STUCK ? "got stuck getting there" : "couldn't get near it");
			}
			return;
		}
		approachTicks = 0;
		BlockState have = body.level().getBlockState(pos);
		if (current.spec().matches(have)) {
			finish();
			return;
		}
		if (!body.mayInteract(pos)) {
			skip("protected spawn area");
			return;
		}

		// Something is there that shouldn't be: take it out first (also how "air" in a plan is done).
		// Grass and flowers under a block to be placed go with the placement; under "air" they go too.
		boolean clear = !have.isAir() && (!have.canBeReplaced() || want.isAir());
		if (clear) {
			if (have.getDestroySpeed(body.level(), pos) < 0) {
				skip("can't break " + BlockCodec.id(have, BlockCodec.Detail.NONE));
				return;
			}
			if (!pos.equals(breaking)) {
				// Wait the break time out first, as mining would; then take it out.
				breaking = pos;
				cooldown = (int) Math.clamp(Math.ceil(Digger.seconds(body, pos, have, null) * 20), PLACE_TICKS, 100);
				body.face(centre);
				return;
			}
			breaking = null;
			body.removeToInventory(pos);
			if (want.isAir()) {
				broken++;
				finish();
				cooldown = PLACE_TICKS;
			}
			return;
		}
		if (want.isAir()) {
			finish(); // already air
			return;
		}

		// Placing: never into the body itself, nor into anyone else.
		if (body.agent.getBoundingBox().intersects(new AABB(pos))) {
			stepAside(body, pos);
			if (++stuck > 60) skip("kept standing in the way");
			return;
		}
		Item item = want.getBlock().asItem();
		if (item == null || item == Items.AIR) {
			skip("no item places " + BlockCodec.id(want, BlockCodec.Detail.NONE));
			return;
		}
		switch (body.placeFromInventory(pos, want, st -> st.is(item))) {
			case PLACED -> {
				BlockState now = body.level().getBlockState(pos);
				if (!current.spec().matches(now)) {
					// The block changed itself on placement (water flowing in, say).
					if (failed.size() < 10) failed.add(pos.toShortString() + ": placed " + BlockCodec.id(now) + " instead of " + BlockCodec.id(want));
				} else {
					placed++;
				}
				cooldown = PLACE_TICKS;
				finish();
			}
			case NO_ITEM -> {
				missing.merge(Views.stack(new ItemStack(item)), Body.itemsFor(want), Integer::sum);
				finish();
			}
			case BLOCKED -> {
				// A player or a mob is standing there. Give them a moment to move.
				if (++stuck > 100) skip("someone was standing there");
				else cooldown = 5;
			}
			case PROTECTED -> skip("protected spawn area");
			case FAILED -> skip("couldn't place " + BlockCodec.id(want, BlockCodec.Detail.NONE));
		}
	}

	private void finish() {
		current = null;
		breaking = null;
		stuck = 0;
		approachTicks = 0;
	}

	private void skip(String why) {
		if (failed.size() < 10) failed.add(current.pos().toShortString() + ": " + why);
		nav.reset();
		finish();
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
			current = best;
			return true;
		}
		return false;
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
		nav.stop();
	}

	/** For the tool's first reply. */
	public String plan() {
		return String.format(Locale.ROOT, "%d blocks to place from the inventory, bottom layer first", total);
	}

	/** Items the plan needs that the inventory lacks right now, for the tool's first reply. */
	public String shortfall(Body body) {
		Map<String, Integer> need = new LinkedHashMap<>();
		for (Job j : jobs) {
			if (j.spec().state().isAir()) continue;
			Item item = j.spec().state().getBlock().asItem();
			if (item == null) continue;
			need.merge(Views.stack(new ItemStack(item)), Body.itemsFor(j.spec().state()), Integer::sum);
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
