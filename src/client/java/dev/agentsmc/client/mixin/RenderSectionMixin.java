package dev.agentsmc.client.mixin;

import dev.agentsmc.client.AgentCamera;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Screenshots show freshly built sections fully, without vanilla's fade-in. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {
	@Inject(method = "getVisibility", at = @At("HEAD"), cancellable = true)
	private void agentsmc$noFade(long now, long fadeDuration, CallbackInfoReturnable<Float> cir) {
		if (AgentCamera.active != null) cir.setReturnValue(1f);
	}
}
