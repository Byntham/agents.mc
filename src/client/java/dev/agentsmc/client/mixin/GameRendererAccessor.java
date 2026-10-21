package dev.agentsmc.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Parts of a full frame that renderLevel alone doesn't do, which a capture frame does itself. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	/** Shaders read the camera position from this uniform. */
	@Accessor("globalSettingsUniform")
	GlobalSettingsUniform agentsmc$globalSettings();

	@Accessor("lightmap")
	Lightmap agentsmc$lightmap();

	@Accessor("fogRenderer")
	FogRenderer agentsmc$fogRenderer();
}
