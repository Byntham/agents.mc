package dev.agentsmc.body;

import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/** Walk to a point. */
public final class GotoIntent extends Intent {
	private final Vec3 target;
	private final double tolerance;
	private final Navigator nav;
	private Navigator.State last = Navigator.State.MOVING;
	private final Vec3 from;

	public GotoIntent(Body body, Vec3 target, double tolerance) {
		this.target = target;
		this.tolerance = Math.max(0.5, tolerance);
		this.nav = new Navigator(body);
		this.from = body.agent.position();
	}

	@Override
	public String kind() {
		return "goto";
	}

	@Override
	public String describe() {
		return String.format(Locale.ROOT, "walking to %.0f %.0f %.0f", target.x, target.y, target.z);
	}

	private Vec3 current;

	@Override
	public String progress() {
		return String.format(Locale.ROOT, "%.0f blocks to go", target.distanceTo(current == null ? from : current));
	}

	@Override
	protected void tick(Body body) {
		current = body.agent.position();
		last = nav.toward(target, tolerance);
		switch (last) {
			case ARRIVED -> done(String.format(Locale.ROOT, "arrived at %.1f %.1f %.1f after %.1fs", current.x, current.y, current.z, ticks() / 20.0));
			case UNREACHABLE -> fail(String.format(Locale.ROOT, "no path to %.0f %.0f %.0f; stopped at %.1f %.1f %.1f, %.0f blocks short",
					target.x, target.y, target.z, current.x, current.y, current.z, current.distanceTo(target)));
			case STUCK -> fail(String.format(Locale.ROOT, "got stuck at %.1f %.1f %.1f, %.0f blocks from the goal",
					current.x, current.y, current.z, current.distanceTo(target)));
			case MOVING -> {
				if (ticks() > 20 * 180) fail("gave up after 3 minutes, " + progress());
			}
		}
	}

	/** The plan, for the tool's first reply. */
	public String plan() {
		return nav.describe();
	}

	/** Plans now so the tool can report the route; the intent keeps the path. */
	public Navigator.State planNow(Body body) {
		current = body.agent.position();
		return nav.toward(target, tolerance);
	}
}
