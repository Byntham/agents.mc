package dev.agentsmc.body;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Walk over to dropped items and pick them up. */
public final class CollectIntent extends Intent {
	private final Predicate<ItemEntity> match;
	private final String what;
	private final double radius;
	private final Navigator nav;
	private final MineIntent.Inventory before;
	private int targetId = -1;
	private int idle;
	private final java.util.Set<Integer> unreachable = new java.util.HashSet<>();

	public CollectIntent(Body body, Predicate<ItemEntity> match, String what, double radius) {
		this.match = match;
		this.what = what;
		this.radius = radius;
		this.nav = new Navigator(body);
		this.before = new MineIntent.Inventory(body);
	}

	@Override
	public String kind() {
		return "collect";
	}

	@Override
	public String describe() {
		return "collecting " + what;
	}

	@Override
	protected void tick(Body body) {
		ItemEntity target = targetId < 0 ? null : body.level().getEntity(targetId) instanceof ItemEntity i && i.isAlive() ? i : null;
		if (target == null) {
			List<ItemEntity> items = body.level().getEntitiesOfClass(ItemEntity.class, body.agent.getBoundingBox().inflate(radius), match);
			double best = Double.MAX_VALUE;
			for (ItemEntity e : items) {
				if (unreachable.contains(e.getId())) continue; // tried that one; don't path to it again every tick
				double d = e.distanceToSqr(body.agent);
				if (d < best) {
					best = d;
					target = e;
				}
			}
			if (target == null) {
				String gained = before.gained(body);
				String got = gained.isEmpty() ? "picked up nothing" : "picked up " + gained;
				if (unreachable.isEmpty()) done(gained.isEmpty() ? "nothing to pick up" : "picked up " + gained);
				else fail(String.format(Locale.ROOT, "couldn't reach %d %s; %s", unreachable.size(), what, got));
				return;
			}
			targetId = target.getId();
			nav.reset();
			idle = 0;
		}
		body.face(target.position());
		Navigator.State s = nav.toward(target.position(), 0.8);
		if (s == Navigator.State.UNREACHABLE || s == Navigator.State.STUCK || ++idle > 20 * 20) {
			unreachable.add(targetId);
			targetId = -1; // try another
		}
	}

	static AABB around(Body body, double radius) {
		return body.agent.getBoundingBox().inflate(radius);
	}
}
