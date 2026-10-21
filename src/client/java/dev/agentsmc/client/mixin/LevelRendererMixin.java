package dev.agentsmc.client.mixin;

import dev.agentsmc.client.AgentCamera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the renderer's sections laid out around the host's camera during a capture frame. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	/** Recentring on the agent's camera would drop the sections farthest from it, to be rebuilt for the host next frame. */
	@Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true)
	private void agentsmc$keepCentre(CameraRenderState camera, CallbackInfo ci) {
		if (AgentCamera.active != null) ci.cancel();
	}

	/** Re-sorting glass and water for the agent's camera would be undone on the host's next frame. */
	@Inject(method = "scheduleTranslucentSectionResort", at = @At("HEAD"), cancellable = true)
	private void agentsmc$keepSort(Vec3 cameraPos, CallbackInfo ci) {
		if (AgentCamera.active != null) ci.cancel();
	}
}
