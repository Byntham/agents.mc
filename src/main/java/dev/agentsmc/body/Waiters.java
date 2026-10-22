package dev.agentsmc.body;

import dev.agentsmc.perception.EventLog;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Lets a tool call block (off the server thread) until something happens to an agent. The model calls
 * {@code wait} while its body walks or fights, costs nothing meanwhile, and gets the event back as
 * soon as it lands.
 */
public final class Waiters {
	public record Waiter(String agent, int minSalience, boolean intentsOnly, boolean includeRestricted, CompletableFuture<EventLog.Event> future) {
	}

	private final List<Waiter> waiting = new CopyOnWriteArrayList<>();

	/** Hook this up to {@link EventLog#listen}. */
	public void onEvent(EventLog.Event e) {
		for (Waiter w : waiting) {
			if (!e.visibleTo(w.agent) || e.salience() < w.minSalience) continue;
			if (e.restricted() && !w.includeRestricted) continue;
			if (w.intentsOnly && !e.type().equals("intent")) continue;
			w.future.complete(e);
		}
	}

	/**
	 * Blocks until a matching event arrives or {@code ms} pass. Returns the event, or null on timeout.
	 */
	public EventLog.Event await(String agent, int minSalience, boolean intentsOnly, boolean includeRestricted, long ms) throws InterruptedException {
		return await(register(agent, minSalience, intentsOnly, includeRestricted), ms);
	}

	/**
	 * Starts listening now, so an event that lands before the caller gets round to blocking is not
	 * missed. Call on the server thread, in the same task that checks for events already pending,
	 * then {@link #await(Waiter, long)} off it.
	 */
	public Waiter register(String agent, int minSalience, boolean intentsOnly, boolean includeRestricted) {
		Waiter w = new Waiter(agent, minSalience, intentsOnly, includeRestricted, new CompletableFuture<>());
		waiting.add(w);
		return w;
	}

	/** Blocks on a registered waiter until its event arrives or {@code ms} pass, then unregisters it. */
	public EventLog.Event await(Waiter w, long ms) throws InterruptedException {
		try {
			return w.future.get(ms, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			return null;
		} catch (ExecutionException e) {
			return null;
		} finally {
			waiting.remove(w);
		}
	}

	/** Stops listening without waiting. */
	public void unregister(Waiter w) {
		waiting.remove(w);
	}

	/** Wakes everyone, e.g. when the world closes. */
	public void cancelAll() {
		for (Waiter w : waiting) w.future.complete(null);
	}
}
