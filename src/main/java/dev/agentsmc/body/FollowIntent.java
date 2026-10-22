package dev.agentsmc.body;

import dev.agentsmc.perception.EventLog;
import dev.agentsmc.perception.Senses;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/** Keep near a player as they move. Runs until cancelled or the player is gone. */
public final class FollowIntent extends Intent {
	private final String player;
	private final double distance;
	private final Navigator nav;
	private int lostTicks;
	private boolean closeReported;
	private int stuckStreak;

	public FollowIntent(Body body, String player, double distance) {
		this.player = player;
		this.distance = Math.max(1.5, distance);
		this.nav = new Navigator(body);
	}

	@Override
	public String kind() {
		return "follow";
	}

	@Override
	public String describe() {
		return "following " + player;
	}

	@Override
	protected void tick(Body body) {
		ServerPlayer p = body.agent.level().getServer().getPlayerList().getPlayerByName(player);
		if (p == null || p.isRemoved()) {
			if (++lostTicks > 100) fail(player + " is no longer online");
			return;
		}
		if (p.level() != body.agent.level()) {
			fail(player + " went to " + p.level().dimension().identifier().getPath() + "; can't follow across dimensions");
			return;
		}
		lostTicks = 0;
		double d = body.agent.distanceTo(p);
		if (d <= distance) {
			nav.reset();
			body.face(p);
			closeReported = true;
			return;
		}
		// Hang back a little so we don't crowd them, and stop re-planning for every step they take.
		Vec3 goal = p.position();
		Navigator.State s = nav.toward(goal, distance * 0.8);
		if (d > 6) body.face(p.position().add(0, 1, 0)); // look where they are while walking
		switch (s) {
			case UNREACHABLE, STUCK -> {
				if (++stuckStreak == 1) {
					body.event("follow", String.format(Locale.ROOT, "Can't reach %s from here (%s, %.0f blocks). Still trying.", player,
							Senses.relative(body.agent, p.position()), d), EventLog.WAKE);
				}
				nav.reset();
				if (stuckStreak > 6) fail("couldn't find a way to " + player);
			}
			case ARRIVED, MOVING -> {
				if (s == Navigator.State.ARRIVED) stuckStreak = 0;
			}
		}
		if (d > 48 && ticks() % 100 == 0) {
			body.event("follow", String.format(Locale.ROOT, "%s is far ahead, %.0f blocks %s", player, d, Senses.relative(body.agent, p.position())), EventLog.NOTABLE);
		}
	}
}
