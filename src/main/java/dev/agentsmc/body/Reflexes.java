package dev.agentsmc.body;

import dev.agentsmc.perception.EventLog;
import dev.agentsmc.perception.Senses;
import dev.agentsmc.perception.Views;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * The things a player does without thinking: back away from a hissing creeper, hit back at whatever
 * is hitting you, eat when hungry. They run every tick with no model involved and report what they
 * did as events, so the model can adjust its plan.
 */
public final class Reflexes {
	public boolean autoEat = true, fightBack = true, fleeCreepers = true;

	private final Body body;
	private int eatingSlot = -1;
	private int lastFoodWarn = Integer.MAX_VALUE;
	private int fleeCooldown, fightCooldown;

	Reflexes(Body body) {
		this.body = body;
	}

	void tick() {
		var agent = body.agent;
		if (!agent.isAlive() || agent.isCreative() || agent.isSpectator()) return;
		if (fleeCooldown > 0) fleeCooldown--;
		if (fightCooldown > 0) fightCooldown--;

		if (fleeCreepers) flee();
		if (fightBack) fight();
		if (autoEat) eat();
	}

	private void flee() {
		List<Creeper> creepers = body.level().getEntitiesOfClass(Creeper.class, body.agent.getBoundingBox().inflate(5), c -> c.getSwellDir() > 0 || c.isIgnited());
		if (creepers.isEmpty()) return;
		Creeper c = creepers.getFirst();
		body.sprint(true);
		body.moveAwayFrom(c.position(), 1.0);
		body.face(c);
		if (fleeCooldown == 0) {
			fleeCooldown = 60;
			body.event("reflex", String.format(Locale.ROOT, "A creeper is about to explode %s. Backing away.", Senses.relative(body.agent, c.position())), EventLog.URGENT);
		}
	}

	private void fight() {
		if (fightCooldown > 0 || body.current() instanceof AttackIntent) return;
		LivingEntity attacker = body.agent.getLastHurtByMob();
		if (!(attacker instanceof Monster) || attacker.isDeadOrDying() || attacker.isRemoved()) return;
		if (body.agent.tickCount - body.agent.getLastHurtByMobTimestamp() > 40) return;
		if (body.agent.distanceTo(attacker) > 8) return;
		fightCooldown = 40;
		body.event("reflex", String.format(Locale.ROOT, "Fighting back at the %s that hit you (%s).", Senses.name(attacker),
				Senses.relative(body.agent, attacker.position())), EventLog.WAKE);
		body.pushReflex(new AttackIntent(body, attacker, true));
	}

	private void eat() {
		var agent = body.agent;
		int food = agent.getFoodData().getFoodLevel();
		if (eatingSlot >= 0) {
			if (agent.isUsingItem()) {
				body.face(agent.getEyePosition().add(agent.getLookAngle())); // keep still-ish; eating while walking is fine
				return;
			}
			// Finished (or interrupted). Put the tool back.
			int slot = eatingSlot;
			eatingSlot = -1;
			if (slot != agent.getInventory().getSelectedSlot()) body.hold(slot);
			body.event("reflex", String.format(Locale.ROOT, "Ate something. Food %d/20.", agent.getFoodData().getFoodLevel()), EventLog.QUIET);
			return;
		}
		if (food > 14 || agent.isUsingItem()) {
			if (food > 10) lastFoodWarn = Integer.MAX_VALUE;
			return;
		}
		if (body.current() instanceof AttackIntent) return; // not mid-fight
		if (!body.level().getEntitiesOfClass(Monster.class, agent.getBoundingBox().inflate(6), m -> m.getTarget() == agent).isEmpty()) return;
		int slot = foodSlot();
		if (slot < 0) {
			if (food <= 6 && food < lastFoodWarn) {
				lastFoodWarn = food;
				body.event("hunger", String.format(Locale.ROOT, "You're hungry (food %d/20) and have nothing to eat.", food), EventLog.WAKE);
			}
			return;
		}
		int prev = agent.getInventory().getSelectedSlot();
		body.hold(slot);
		ItemStack stack = agent.getMainHandItem();
		var result = agent.gameMode.useItem(agent, body.level(), stack, InteractionHand.MAIN_HAND);
		if (agent.isUsingItem()) {
			eatingSlot = slot < 9 ? prev : agent.getInventory().getSelectedSlot(); // where the tool went
			if (slot >= 9) eatingSlot = agent.getInventory().getSelectedSlot(); // swapped into the selected slot; swap back later
			body.event("reflex", String.format(Locale.ROOT, "Eating %s (food %d/20).", Views.stack(stack.copyWithCount(1)), food), EventLog.QUIET);
		} else if (!result.consumesAction()) {
			body.hold(prev);
		}
	}

	/** An inventory slot with food, preferring the worst food that still fills the gap. */
	private int foodSlot() {
		var inv = body.agent.getInventory();
		int best = -1;
		int bestNutrition = Integer.MAX_VALUE;
		int missing = 20 - body.agent.getFoodData().getFoodLevel();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			var food = s.get(DataComponents.FOOD);
			if (food == null || !s.has(DataComponents.CONSUMABLE)) continue;
			String id = Views.stack(s.copyWithCount(1));
			if (id.contains("rotten") || id.contains("spider_eye") || id.contains("pufferfish") || id.contains("chorus")) continue;
			int n = food.nutrition();
			// Prefer the smallest food that covers the gap; otherwise the biggest we have.
			boolean better = best < 0 || n >= missing && (bestNutrition < missing || n < bestNutrition) || n < missing && bestNutrition < missing && n > bestNutrition;
			if (better) {
				best = i;
				bestNutrition = n;
			}
		}
		return best;
	}

	public String describe() {
		return "auto_eat=" + autoEat + " fight_back=" + fightBack + " flee_creepers=" + fleeCreepers;
	}
}
