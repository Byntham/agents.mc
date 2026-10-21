package dev.agentsmc.client.mixin;

import dev.agentsmc.client.AgentCamera;
import dev.agentsmc.perception.Screenshots;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
	@Shadow
	@Final
	private LevelRenderer levelRenderer;

	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	/** Which sections a screenshot can see depends on where its camera is, not where the host's is. */
	@Inject(method = "extract", at = @At("HEAD"))
	private void agentsmc$agentGraph(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks, CallbackInfo ci) {
		if (AgentCamera.active != null) ((AgentCamera.Graph) levelRenderer.sectionOcclusionGraph()).agentsmc$buildFor(levelRenderState.cameraRenderState);
	}

	/** From its own eyes, an agent's head would fill the picture. */
	@Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
	private void agentsmc$hideAgent(Entity entity, Frustum frustum, double camX, double camY, double camZ, float partialTicks, long chunkFadeDuration,
									CallbackInfoReturnable<Boolean> cir) {
		Screenshots.Shot shot = AgentCamera.active;
		if (shot != null && entity.getId() == shot.hidden()) cir.setReturnValue(false);
	}
}
