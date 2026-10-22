package dev.agentsmc.body;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Walk to a point, or to a player. A player target is re-read every tick, so "come here" ends up
 * next to where the player is, not where they were when they asked.
 */
public final class GotoIntent extends Intent {
	private final Vec3 point;
	private final String player;
	private final double tolerance;
	private final Navigator nav;
	private final Vec3 from;
	private Vec3 current;
	private int lostTicks;

	public GotoIntent(Body body, Vec3 target, double tolerance) {
		this.point = target;
		this.player = null;
		this.tolerance = Math.max(0.5, tolerance);
		this.nav = new Navigator(body);
		this.from = body.agent.position();
	}

	/** Walk to a player and stop {@code distance} blocks from them. */
	public GotoIntent(Body body, String player, double distance) {
		this.point = null;
		this.player = player;
		this.tolerance = Math.max(1.0, distance);
		this.nav = new Navigator(body);
		this.from = body.agent.position();
	}

	@Override
	public String kind() {
		return "goto";
	}

	@Override
	public String describe() {
		if (player != null) return "walking to " + player;
		return String.format(Locale.ROOT, "walking to %d %d %d", (int) Math.floor(point.x), (int) Math.floor(point.y), (int) Math.floor(point.z));
	}

	@Override
	public String progress() {
		Vec3 t = point != null ? point : lastTarget;
		if (t == null) return "";
		return String.format(Locale.ROOT, "%.0f blocks to go", t.distanceTo(current == null ? from : current));
	}

	private Vec3 lastTarget;

	@Override
	protected void tick(Body body) {
		current = body.agent.position();
		Vec3 target = point;
		if (player != null) {
			ServerPlayer p = body.level().getServer().getPlayerList().getPlayerByName(player);
			if (p == null || p.isRemoved()) {
				if (++lostTicks > 100) fail(player + " is no longer online");
				return;
			}
			if (p.level() != body.level()) {
				fail(player + " is in another dimension");
				return;
			}
			lostTicks = 0;
			target = p.position();
			if (body.agent.distanceTo(p) > 6) body.face(p.position().add(0, 1, 0));
		}
		lastTarget = target;
		Navigator.State last = nav.toward(target, tolerance);
		switch (last) {
			case ARRIVED -> {
				if (player != null) body.face(body.level().getServer().getPlayerList().getPlayerByName(player));
				done(player != null
						? String.format(Locale.ROOT, "reached %s after %.1fs, %.0f blocks away", player, ticks() / 20.0, current.distanceTo(target))
						: String.format(Locale.ROOT, "arrived at %.1f %.1f %.1f after %.1fs", current.x, current.y, current.z, ticks() / 20.0));
			}
			case UNREACHABLE -> fail(String.format(Locale.ROOT, "no path to %s; stopped at %.1f %.1f %.1f, %.0f blocks short%s",
					player != null ? player : String.format(Locale.ROOT, "%.0f %.0f %.0f", target.x, target.y, target.z),
					current.x, current.y, current.z, current.distanceTo(target), nav.note()));
			case STUCK -> fail(String.format(Locale.ROOT, "got stuck at %.1f %.1f %.1f, %.0f blocks from the goal",
					current.x, current.y, current.z, current.distanceTo(target)));
			case MOVING -> {
				if (ticks() > 20 * 180) fail("gave up after 3 minutes, " + progress());
			}
		}
	}

	/** The plan, for the tool's first reply. */
	public String plan() {
		return nav.describe() + nav.note();
	}

	/** Plans now so the tool can report the route; the intent keeps the path. */
	public Navigator.State planNow(Body body) {
		current = body.agent.position();
		Vec3 target = point;
		if (player != null) {
			ServerPlayer p = body.level().getServer().getPlayerList().getPlayerByName(player);
			if (p == null) return Navigator.State.UNREACHABLE;
			target = p.position();
		}
		return nav.toward(target, tolerance);
	}
}
