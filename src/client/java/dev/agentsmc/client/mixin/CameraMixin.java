package dev.agentsmc.client.mixin;

import dev.agentsmc.client.AgentCamera;
import dev.agentsmc.perception.Screenshots;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Puts the camera where a screenshot is taken from. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER))
	private void agentsmc$aim(DeltaTracker deltaTracker, CallbackInfo ci) {
		Screenshots.Shot shot = AgentCamera.active;
		if (shot == null) return;
		setRotation(shot.yaw(), shot.pitch());
		setPosition(shot.eye());
		detached = true; // so the host's own body is drawn when it is in view
	}

	/** Vanilla's default field of view, without the host's sprinting or potion effects. */
	@Inject(method = "calculateFov", at = @At("HEAD"), cancellable = true)
	private void agentsmc$fov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (AgentCamera.active != null) cir.setReturnValue(70f);
	}
}
