package dev.agentsmc.client.mixin;

import dev.agentsmc.client.AgentCamera;
import dev.agentsmc.client.FrameRecorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws agents' screenshot frames just before the host's own frame, which then draws over them, and
 * hands finished frames to the demo recorder.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;)V"))
	private void agentsmc$screenshots(boolean advanceGameTime, CallbackInfo ci) {
		AgentCamera.beforeFrame((Minecraft) (Object) this);
	}

	@Inject(method = "renderFrame", at = @At("RETURN"))
	private void agentsmc$record(boolean advanceGameTime, CallbackInfo ci) {
		if (FrameRecorder.enabled()) FrameRecorder.afterFrame((Minecraft) (Object) this);
	}
}
