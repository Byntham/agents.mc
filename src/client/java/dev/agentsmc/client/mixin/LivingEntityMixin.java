package dev.agentsmc.client.mixin;

import dev.agentsmc.client.AgentCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fog, sky and the lightmap read effects from the host, who stays the camera entity during a capture.
 * Their blindness, darkness or night vision isn't part of the agent's view.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "hasEffect", at = @At("HEAD"), cancellable = true)
	private void agentsmc$noHostEffects(Holder<MobEffect> effect, CallbackInfoReturnable<Boolean> cir) {
		if (AgentCamera.active != null && (Object) this == Minecraft.getInstance().player) cir.setReturnValue(false);
	}

	@Inject(method = "getEffectBlendFactor", at = @At("HEAD"), cancellable = true)
	private void agentsmc$noHostEffectBlend(Holder<MobEffect> effect, float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (AgentCamera.active != null && (Object) this == Minecraft.getInstance().player) cir.setReturnValue(0f);
	}
}
