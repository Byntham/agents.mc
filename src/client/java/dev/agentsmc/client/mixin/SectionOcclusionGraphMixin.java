package dev.agentsmc.client.mixin;

import dev.agentsmc.client.AgentCamera;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The graph of sections visible from the camera. Vanilla rebuilds it in the background when the camera
 * moves, which would be a frame late for a screenshot and would then be rebuilt for the host. A capture
 * frame builds one for the agent's camera on the render thread and puts the host's back afterwards.
 *
 * <p>The build runs inline rather than on the background pool because terrain builders on that pool
 * can spin until the render thread uploads their meshes, so waiting on the pool here could freeze the
 * game. For the same reason a capture waits for a host rebuild to finish on its own (see
 * {@link #agentsmc$busy}) instead of blocking on it.
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphMixin implements AgentCamera.Graph {
	@Shadow
	private boolean needsFullUpdate;

	@Shadow
	private Future<?> fullUpdateTask;

	/** Holds a private GraphState, so its type can't be named here. */
	@Shadow
	@Final
	private AtomicReference<Object> currentGraph;

	@Shadow
	@Final
	private AtomicBoolean needsFrustumUpdate;

	@Unique
	private boolean agentsmc$swapped;

	@Unique
	private Object agentsmc$hostGraph;

	@Shadow
	private void scheduleFullUpdate(CameraRenderState camera) {
	}

	@Shadow
	public abstract void updateLoadedChunks(LongOpenHashSet added, LongOpenHashSet removed);

	@Shadow
	public abstract void updateEmptySections(LongOpenHashSet added, LongOpenHashSet removed);

	@Override
	public boolean agentsmc$busy() {
		return fullUpdateTask != null && !fullUpdateTask.isDone();
	}

	@Override
	public void agentsmc$buildFor(CameraRenderState camera) {
		if (!agentsmc$swapped) {
			agentsmc$hostGraph = currentGraph.get();
			agentsmc$swapped = true;
		}
		scheduleFullUpdate(camera);
	}

	@Override
	public void agentsmc$restore() {
		if (!agentsmc$swapped) return;
		currentGraph.set(agentsmc$hostGraph);
		agentsmc$hostGraph = null;
		agentsmc$swapped = false;
		// Sections built during the capture frame were added to the agent's graph, so rebuild the host's.
		needsFullUpdate = true;
		needsFrustumUpdate.set(true);
	}

	@Redirect(method = "scheduleFullUpdate", at = @At(value = "INVOKE",
			target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
	private CompletableFuture<Void> agentsmc$inlineForCapture(Runnable task, Executor executor) {
		if (AgentCamera.active == null) return CompletableFuture.runAsync(task, executor);
		task.run();
		return CompletableFuture.completedFuture(null);
	}

	/**
	 * The renderer replaced its sections, such as after F3+A or a render distance change. The host's graph
	 * pointed at the old ones, so there is nothing to put back, and a capture frame doing this drew nothing.
	 */
	@Inject(method = "waitAndReset", at = @At("HEAD"))
	private void agentsmc$dropHostGraph(ViewArea viewArea, CallbackInfo ci) {
		agentsmc$hostGraph = null;
		agentsmc$swapped = false;
		if (AgentCamera.active != null) AgentCamera.graphReset = true;
	}

	/** During a capture frame, keep track of loaded chunks but leave the graph alone. */
	@Inject(method = "update", at = @At("HEAD"), cancellable = true)
	private void agentsmc$holdForCapture(CameraRenderState camera, int fov, ChunkLoadingRenderState chunks, CallbackInfo ci) {
		if (AgentCamera.active == null) return;
		updateLoadedChunks(chunks.addedLoadedChunks, chunks.removedLoadedChunks);
		updateEmptySections(chunks.addedEmptySections, chunks.removedEmptySections);
		ci.cancel();
	}
}
