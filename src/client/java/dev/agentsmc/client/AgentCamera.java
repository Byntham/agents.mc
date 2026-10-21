package dev.agentsmc.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import dev.agentsmc.client.mixin.GameRendererAccessor;
import dev.agentsmc.client.mixin.LightmapExtractorAccessor;
import dev.agentsmc.mcp.ToolException;
import dev.agentsmc.perception.Screenshots;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Renders agents' screenshots on the host's game. Each shot takes a few extra frames, drawn from the
 * agent's camera just before the host's own frame, which then draws over them, so they never reach
 * the screen. Like vanilla's panorama screenshots, they resize the main render target while drawing.
 *
 * <p>Moving the camera for one frame would disturb state the renderer keeps for the host's camera, so
 * the mixins in {@code dev.agentsmc.client.mixin} hold that state still while {@link #active} is set.
 * The extra frames also ask for terrain the host isn't facing to be built, which the renderer only
 * does for sections in view. The shot is taken once no sections have been waiting to be built for
 * a moment.
 */
public final class AgentCamera implements Screenshots.Camera {
	/** How long no terrain must be waiting to be built before the picture is taken. */
	private static final long SETTLE_MS = 150;
	/** Take the picture by then even if terrain is still being built. */
	private static final long MAX_MS = 3000;
	private static final int MIN_FRAMES = 3;

	private static final Queue<Job> queue = new ConcurrentLinkedQueue<>();
	/** Render thread only, like everything below. */
	private static Job job;
	/** The shot a capture frame is drawing, or null during the host's own frames. Read by the mixins. */
	public static Screenshots.Shot active;
	/** Set when a capture frame's extract replaced the renderer's sections, so that frame drew nothing. */
	public static boolean graphReset;

	/** The section occlusion graph, which the mixins swap to the agent's camera for a capture frame. */
	public interface Graph {
		/** Whether the host's graph is being rebuilt in the background. A capture waits until it isn't. */
		boolean agentsmc$busy();

		/** Replaces the graph with one built from this camera, keeping the host's to put back. */
		void agentsmc$buildFor(CameraRenderState camera);

		/** Puts the host's graph back. Does nothing if it wasn't swapped. */
		void agentsmc$restore();
	}

	private static final class Job {
		final Screenshots.Shot shot;
		final CompletableFuture<byte[]> result = new CompletableFuture<>();
		long started;
		long lastBusy;
		int frames;

		Job(Screenshots.Shot shot) {
			this.shot = shot;
		}
	}

	@Override
	public CompletableFuture<byte[]> take(Screenshots.Shot shot) {
		Job j = new Job(shot);
		queue.add(j);
		return j.result;
	}

	/** Called every frame, before the host's camera updates. */
	public static void beforeFrame(Minecraft mc) {
		if (job == null || job.result.isDone()) {
			do job = queue.poll();
			while (job != null && job.result.isDone()); // the tool gave up waiting
		}
		if (job == null) return;

		String problem = problem(mc, job.shot);
		if (problem != null) {
			job.result.completeExceptionally(new ToolException(problem));
			job = null;
			return;
		}
		if (job.started == 0) job.started = job.lastBusy = Util.getMillis();
		// Before the host's first frame in a level, the renderer isn't set up for one.
		if (!mc.gameRenderer.mainCamera().isInitialized()) return;
		if (((Graph) mc.levelRenderer.sectionOcclusionGraph()).agentsmc$busy()) return;
		try {
			if (capture(mc, job)) job = null;
		} catch (RuntimeException e) {
			// Rendering may have stopped partway, leaving state the host's frames would draw with,
			// so crash as vanilla does for an error while rendering.
			job.result.completeExceptionally(e);
			job = null;
			throw e;
		}
	}

	/** Why the host's game can't show this shot, or null if it can. */
	private static String problem(Minecraft mc, Screenshots.Shot shot) {
		if (mc.level == null || mc.player == null) return "The host's game isn't showing the world right now.";
		String host = mc.player.getName().getString();
		if (mc.level.dimension() != shot.dimension()) {
			return "Screenshots only show the dimension " + host + " is in (" + mc.level.dimension().identifier().getPath() + ").";
		}
		int cx = SectionPos.blockToSectionCoord(shot.eye().x), cz = SectionPos.blockToSectionCoord(shot.eye().z);
		int chunks = Math.max(Math.abs(cx - mc.player.chunkPosition().x()), Math.abs(cz - mc.player.chunkPosition().z()));
		int renderDistance = mc.options.getEffectiveRenderDistance();
		if (chunks >= renderDistance || !mc.level.getChunkSource().hasChunk(cx, cz)) {
			return String.format(Locale.ROOT, "The camera is %.0f blocks from %s, beyond what their game has loaded (render distance %d chunks), so it can't be shown. "
					+ "Move the camera closer to %s or use the text views.", Math.sqrt(mc.player.distanceToSqr(shot.eye())), host, renderDistance, host);
		}
		return null;
	}

	/** Draws one frame from the shot's camera. Returns true once the picture has been taken. */
	private static boolean capture(Minecraft mc, Job job) {
		Screenshots.Shot shot = job.shot;
		Window window = mc.getWindow();
		RenderTarget target = mc.gameRenderer.mainRenderTarget();
		int width = window.getWidth(), height = window.getHeight();
		active = shot;
		try {
			window.setWidth(shot.width());
			window.setHeight(shot.height());
			target.resize(shot.width(), shot.height());
			GameRendererAccessor renderer = (GameRendererAccessor) mc.gameRenderer;
			mc.gameRenderer.setRenderBlockOutline(false);
			// Build this frame's lightmap without the host's effects (see LivingEntityMixin).
			((LightmapExtractorAccessor) renderer.agentsmc$lightmapExtractor()).agentsmc$setNeedsUpdate(true);
			mc.gameRenderer.update(DeltaTracker.ONE);
			mc.gameRenderer.extract(DeltaTracker.ONE, true);

			GameRenderState state = mc.gameRenderer.gameRenderState();
			SectionRenderDispatcher dispatcher = mc.levelRenderer.sectionRenderDispatcher();
			// Sections queued this frame or still waiting to be built, by this frame or the host's.
			boolean busy = graphReset || !state.levelRenderState.sectionUpdateRenderStates.isEmpty() || dispatcher != null && !dispatcher.isQueueEmpty();
			graphReset = false;
			// extract built the lightmap state and expects this frame to draw it.
			renderer.agentsmc$lightmap().render(state.lightmapRenderState);
			// The loading screen waits for the section at the host's camera, not this one.
			state.levelRenderState.playerCompiledSectionCallback = null;
			// The host's view bobbing, hurt tilt and portal wobble aren't part of the agent's view.
			state.optionsRenderState.bobView = false;
			state.levelRenderState.cameraRenderState.entityRenderState.isLiving = false;
			state.levelRenderState.playerRenderState.portalEffectIntensity = 0;
			state.levelRenderState.playerRenderState.nauseaEffectIntensity = 0;
			// GameRenderer.render sets this before drawing the level; renderLevel alone doesn't.
			renderer.agentsmc$globalSettings().update(shot.width(), shot.height(),
					state.optionsRenderState.glintStrength, state.levelRenderState.gameTime, state.levelRenderState.worldPartialTicks,
					state.optionsRenderState.menuBackgroundBlurriness, state.levelRenderState.cameraRenderState.pos,
					state.optionsRenderState.textureFiltering == TextureFilteringMethod.RGSS);
			mc.gameRenderer.renderLevel();
			// The fog and cloud uniforms live in mapped buffers. Move to the next ones, as at the end of a
			// frame, so the host's values don't overwrite this frame's before the GPU reads them.
			renderer.agentsmc$fogRenderer().endFrame();
			mc.levelRenderer.endFrame();

			long now = Util.getMillis();
			if (busy) job.lastBusy = now;
			boolean settled = ++job.frames >= MIN_FRAMES && now - job.lastBusy >= SETTLE_MS;
			if (!settled && now - job.started < MAX_MS) return false;
			Screenshot.takeScreenshot(target, image -> encode(image, job.result));
			return true;
		} finally {
			active = null;
			graphReset = false;
			((Graph) mc.levelRenderer.sectionOcclusionGraph()).agentsmc$restore();
			// The host's frame rebuilds its own lightmap.
			((LightmapExtractorAccessor) ((GameRendererAccessor) mc.gameRenderer).agentsmc$lightmapExtractor()).agentsmc$setNeedsUpdate(true);
			mc.gameRenderer.setRenderBlockOutline(true);
			window.setWidth(width);
			window.setHeight(height);
			target.resize(width, height);
		}
	}

	/** Copies the pixels here, on the render thread, and encodes them on a background thread. */
	private static void encode(NativeImage image, CompletableFuture<byte[]> result) {
		int w = image.getWidth(), h = image.getHeight();
		int[] argb;
		try (image) {
			argb = image.getPixels();
		}
		CompletableFuture.runAsync(() -> {
			try {
				result.complete(jpeg(argb, w, h));
			} catch (Exception e) {
				result.completeExceptionally(e);
			}
		}, Util.backgroundExecutor());
	}

	private static byte[] jpeg(int[] argb, int w, int h) throws IOException {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		img.setRGB(0, 0, w, h, argb, 0, w);
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(0.9f);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
			writer.setOutput(stream);
			writer.write(null, new IIOImage(img, null, null), param);
		} finally {
			writer.dispose();
		}
		return out.toByteArray();
	}
}
