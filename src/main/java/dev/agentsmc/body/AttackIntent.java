package dev.agentsmc.body;

import dev.agentsmc.perception.Senses;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/** Chase and hit an entity until it dies or gets away. */
public final class AttackIntent extends Intent {
	private static final int GIVE_UP_TICKS = 20 * 60;
	private static final double GIVE_UP_DISTANCE = 40;

	private final int targetId;
	private final String targetName;
	private final boolean reflex;
	private final Navigator nav;
	private int hits;
	private Vec3 lastSeen;

	/** @param reflex started by the body itself (fighting back), not by the model */
	public AttackIntent(Body body, Entity target, boolean reflex) {
		this.targetId = target.getId();
		this.targetName = Senses.name(target);
		this.reflex = reflex;
		this.nav = new Navigator(body);
		this.lastSeen = target.position();
	}

	@Override
	public String kind() {
		return reflex ? "fight_back" : "attack";
	}

	@Override
	public String describe() {
		return "fighting " + targetName;
	}

	@Override
	public String progress() {
		return hits + " hits";
	}

	@Override
	public boolean pausable() {
		return !reflex;
	}

	@Override
	protected void tick(Body body) {
		Entity target = body.level().getEntity(targetId);
		if (target == null || target.isRemoved() || target instanceof LivingEntity l && l.isDeadOrDying()) {
			done(String.format(Locale.ROOT, "%s %s after %d hits", targetName, target == null && hits == 0 ? "is gone" : "is dead", hits));
			return;
		}
		lastSeen = target.position();
		double d = body.agent.distanceTo(target);
		if (d > GIVE_UP_DISTANCE) {
			fail(targetName + " got away (" + Senses.relative(body.agent, lastSeen) + ")");
			return;
		}
		if (ticks() > GIVE_UP_TICKS) {
			fail(String.format(Locale.ROOT, "couldn't finish %s off in a minute (%d hits)", targetName, hits));
			return;
		}
		if (ticks() == 1) body.holdWeapon();

		body.face(target);
		double reach = body.agent.entityInteractionRange();
		double toBox = Math.sqrt(target.getBoundingBox().distanceToSqr(body.agent.getEyePosition()));
		if (toBox > reach - 0.3) {
			Navigator.State s = nav.toward(target.position(), 1.5);
			body.sprint(d > 5);
			if (s == Navigator.State.UNREACHABLE || s == Navigator.State.STUCK) {
				// Can't path to it (it's flying, or across water). Push straight at it and hope.
				body.moveToward(target.position(), 1.0);
				nav.reset();
			}
			return;
		}
		// In range: back off half a step if we're inside them, and swing when the cooldown allows.
		if (d < 1.0) body.moveAwayFrom(target.position(), 0.5);
		if (body.agent.getAttackStrengthScale(0.5f) >= 1f && body.facing(target.getEyePosition(), 60)) {
			body.agent.attack(target);
			body.swingArm();
			hits++;
		}
	}
}
