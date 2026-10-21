package dev.agentsmc.util;

import dev.agentsmc.mcp.ToolException;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Runs world access on the server thread. Tool handlers run on HTTP threads and must never touch
 * the world directly.
 */
public final class OnServer {
	private static final long TIMEOUT_MS = 10_000;

	private OnServer() {
	}

	public static <T> T call(MinecraftServer server, Supplier<T> task) {
		if (server.isSameThread()) return task.get();
		try {
			return CompletableFuture.supplyAsync(task, server).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException re) throw re;
			throw new RuntimeException(cause);
		} catch (TimeoutException e) {
			throw new ToolException("Timed out waiting for the server thread (overloaded or shutting down?)");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ToolException("Interrupted");
		}
	}
}
