package dev.agentsmc.util;

import dev.agentsmc.mcp.ToolException;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs world access on the server thread. Tool handlers run on HTTP threads and must never touch
 * the world directly.
 */
public final class OnServer {
	private static final long TIMEOUT_MS = 10_000;
	private static final int QUEUED = 0, RUNNING = 1, ABANDONED = 2;

	private OnServer() {
	}

	public static <T> T call(MinecraftServer server, Supplier<T> task) {
		if (server.isSameThread()) return task.get();
		if (!server.isRunning()) throw new ToolException("The world is closing; try again once it is open.");

		AtomicInteger state = new AtomicInteger(QUEUED);
		CompletableFuture<T> result = new CompletableFuture<>();
		server.executeIfPossible(() -> {
			// A stopped server may run tasks inline on the caller's thread; never touch the world from there.
			if (!server.isSameThread()) {
				result.completeExceptionally(new ToolException("The world is closing; try again once it is open."));
				return;
			}
			// If the caller already gave up, don't make changes it was told didn't happen.
			if (!state.compareAndSet(QUEUED, RUNNING)) return;
			try {
				result.complete(task.get());
			} catch (Throwable t) {
				result.completeExceptionally(t);
			}
		});

		try {
			return result.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException re) throw re;
			throw new RuntimeException(cause);
		} catch (TimeoutException e) {
			if (state.compareAndSet(QUEUED, ABANDONED)) {
				throw new ToolException("The server was too busy to run this within 10 seconds, so nothing was done. It is safe to retry.");
			}
			throw new ToolException("This is still running after 10 seconds and will finish on its own. Check the result before retrying.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			state.compareAndSet(QUEUED, ABANDONED);
			throw new ToolException("Interrupted");
		}
	}
}
