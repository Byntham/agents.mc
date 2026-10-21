package dev.agentsmc.client.mixin;

import dev.agentsmc.client.AgentCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rain fog eases toward a value for the camera's block each frame. Capture frames leave the host's value alone. */
@Mixin(AtmosphericFogEnvironment.class)
public abstract class AtmosphericFogEnvironmentMixin {
	@Inject(method = "updateRainFogState", at = @At("HEAD"), cancellable = true)
	private void agentsmc$holdForCapture(Camera camera, ClientLevel level, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (AgentCamera.active != null) ci.cancel();
	}
}
