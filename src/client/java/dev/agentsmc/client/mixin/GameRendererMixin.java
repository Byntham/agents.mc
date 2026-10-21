package dev.agentsmc.client.mixin;

import dev.agentsmc.client.AgentCamera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.util.profiling.Profiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/**
	 * The hand, fire and underwater overlays and the crosshair belong to the host. This pass also draws
	 * into a depth target sized to the window, which no longer matches the resized main target.
	 */
	@Inject(method = "render3dHud", at = @At("HEAD"), cancellable = true)
	private void agentsmc$noHostHud(CameraRenderState cameraState, PlayerRenderState playerState, OptionsRenderState optionsState,
									boolean consistentDepthRequired, CallbackInfo ci) {
		if (AgentCamera.active == null) return;
		Profiler.get().pop(); // render3dHud pops the "level" section renderLevel pushed
		ci.cancel();
	}
}
