package dev.agentsmc.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.agentsmc.AgentsMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Records the game's rendered frames to a video while a world is open, for demos. Reads the frame
 * back from the main render target (the same picture F2 saves), so it doesn't matter what is on the
 * screen or whether the window is covered. Enabled with {@code AGENTSMC_RECORD=<file.mkv>} and
 * {@code AGENTSMC_FFMPEG=<ffmpeg.exe>} in the game's environment; {@code AGENTSMC_RECORD_FPS}
 * (default 30) sets the rate.
 */
public final class FrameRecorder {
	private static final String OUT = System.getenv("AGENTSMC_RECORD");
	private static final String FFMPEG = System.getenv("AGENTSMC_FFMPEG");
	private static final int FPS = Integer.parseInt(System.getenv().getOrDefault("AGENTSMC_RECORD_FPS", "30"));

	/** A frame's pixels and how many times to write it (missed frames repeat the previous one). */
	private record Frame(int[] argb, int w, int h, int repeat) {
	}

	private static final Frame END = new Frame(null, 0, 0, 0);

	private static Process ffmpeg;
	private static Thread writer;
	private static final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(8);
	private static long start = -1;
	private static long sent;
	private static boolean pending;
	private static boolean stopped;

	private FrameRecorder() {
	}

	public static boolean enabled() {
		return OUT != null && !OUT.isBlank() && FFMPEG != null && !FFMPEG.isBlank();
	}

	/** Render thread, after each frame has been drawn into the main render target. */
	public static void afterFrame(Minecraft mc) {
		if (!enabled() || stopped || mc.level == null || pending) return;
		RenderTarget target = mc.gameRenderer.mainRenderTarget();
		if (ffmpeg == null) start(target.width, target.height);
		if (ffmpeg == null) return;
		if (start < 0) start = System.nanoTime();
		long due = (System.nanoTime() - start) * FPS / 1_000_000_000L;
		if (due <= sent) return;
		int repeat = (int) Math.min(due - sent, FPS); // never repeat more than a second's worth
		sent = due;
		pending = true;
		Screenshot.takeScreenshot(target, image -> {
			pending = false;
			int w = image.getWidth(), h = image.getHeight();
			int[] argb;
			try (image) {
				argb = image.getPixels();
			}
			if (!queue.offer(new Frame(argb, w, h, repeat))) AgentsMc.LOGGER.debug("Agents MC: recorder dropped a frame");
		});
	}

	private static void start(int w, int h) {
		List<String> cmd = new ArrayList<>(List.of(FFMPEG, "-v", "error", "-y",
				"-f", "rawvideo", "-pix_fmt", "argb", "-s", w + "x" + h, "-r", String.valueOf(FPS), "-i", "-",
				"-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", OUT));
		try {
			ffmpeg = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		} catch (IOException e) {
			AgentsMc.LOGGER.error("Agents MC: couldn't start ffmpeg for recording", e);
			stopped = true;
			return;
		}
		AgentsMc.LOGGER.info("Agents MC: recording {}x{} at {} fps to {}", w, h, FPS, OUT);
		OutputStream out = ffmpeg.getOutputStream();
		writer = new Thread(() -> {
			ByteBuffer buf = ByteBuffer.allocate(w * h * 4);
			try {
				while (true) {
					Frame f = queue.take();
					if (f == END) break;
					if (f.w() != w || f.h() != h) continue; // the window was resized; keep the stream's size
					buf.clear();
					buf.asIntBuffer().put(f.argb());
					for (int i = 0; i < f.repeat(); i++) out.write(buf.array(), 0, w * h * 4);
				}
				out.close();
			} catch (IOException | InterruptedException e) {
				AgentsMc.LOGGER.warn("Agents MC: recording stopped: {}", e.toString());
			}
		}, "agentsmc-recorder");
		writer.setDaemon(true);
		writer.start();
	}

	/** Client shutting down: finish the file. */
	public static void stop() {
		if (ffmpeg == null || stopped) return;
		stopped = true;
		queue.clear();
		try {
			queue.put(END);
			writer.join(10_000);
			if (!ffmpeg.waitFor(30, TimeUnit.SECONDS)) ffmpeg.destroy();
			AgentsMc.LOGGER.info("Agents MC: recording finished, {} frames", sent);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
