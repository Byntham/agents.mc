package dev.agentsmc.client.mixin;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightmapRenderStateExtractor.class)
public interface LightmapExtractorAccessor {
	/** Set once a tick and cleared by the extract that builds the lightmap. */
	@Accessor("needsUpdate")
	void agentsmc$setNeedsUpdate(boolean needsUpdate);
}
