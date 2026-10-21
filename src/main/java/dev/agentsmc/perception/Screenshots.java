package dev.agentsmc.perception;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/**
 * Pictures of the world for the screenshot tool. Only a game client can render, so the client part
 * of the mod installs a {@link Camera} when the world is hosted from a game (single-player or Open to
 * LAN). On a dedicated server there is none.
 */
public final class Screenshots {
	public static final int WIDTH = 1280, HEIGHT = 720;

	private static volatile Camera camera;

	private Screenshots() {
	}

	/** Renders shots on the host's game client. */
	public interface Camera {
		/**
		 * Completes with a JPEG, or exceptionally with a ToolException the model should see. Called on
		 * the server thread; rendering happens on the client's render thread.
		 */
		CompletableFuture<byte[]> take(Shot shot);
	}

	/**
	 * @param eye    camera position
	 * @param yaw    Minecraft yaw: 0 south, 90 west, 180 north, -90 east
	 * @param pitch  -90 up, 90 down
	 * @param hidden entity id not to draw, so an agent's own head doesn't block its view; -1 for none
	 */
	public record Shot(ResourceKey<Level> dimension, Vec3 eye, float yaw, float pitch, int hidden, int width, int height) {
	}

	public static void install(Camera c) {
		camera = c;
	}

	/** The host's camera, or null on a dedicated server. */
	public static Camera camera() {
		return camera;
	}
}
