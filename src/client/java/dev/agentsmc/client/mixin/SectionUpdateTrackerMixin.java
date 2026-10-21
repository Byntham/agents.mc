package dev.agentsmc.client.mixin;

import dev.agentsmc.client.AgentCamera;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps track of which sections need building around the host's camera, not a screenshot's. */
@Mixin(SectionUpdateTracker.class)
public abstract class SectionUpdateTrackerMixin {
	@Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true)
	private void agentsmc$keepCentre(SectionPos cameraSectionPos, CallbackInfo ci) {
		if (AgentCamera.active != null) ci.cancel();
	}
}
