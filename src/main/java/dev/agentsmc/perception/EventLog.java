package dev.agentsmc.perception;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Things that happened that an agent can't see by polling blocks: chat, joins, block changes made by
 * players and other agents, and what each agent's own senses noticed (a sound, damage, a mob closing
 * in, an intent finishing). Agents read it incrementally with a cursor (the seq of the last event seen).
 */
public final class EventLog {
	public static final EventLog DISCARD = new EventLog(null);
	private static final int CAPACITY = 1000;

	/** Salience: 0 background, 1 worth a line, 2 worth waking an idle agent, 3 urgent. */
	public static final int QUIET = 0, NOTABLE = 1, WAKE = 2, URGENT = 3;

	/**
	 * {@code actor} is the player who caused the event, if any; agents don't get their own events echoed back.
	 * {@code audience} is the one agent the event is for (its own senses), or null for everyone.
	 * {@code restricted} marks chat from a player who isn't allowed to direct the agents the mod runs, so
	 * those agents don't read it as instructions.
	 */
	public record Event(long seq, int tick, String type, String actor, String text, boolean restricted, String audience, int salience) {
		/** Whether the named agent should see this event. */
		public boolean visibleTo(String agent) {
			if (actor != null && actor.equalsIgnoreCase(agent)) return false;
			return audience == null || audience.equalsIgnoreCase(agent);
		}
	}

	private final MinecraftServer server;
	private final ArrayDeque<Event> events = new ArrayDeque<>();
	private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
	private long nextSeq = 1;

	public EventLog(MinecraftServer server) {
		this.server = server;
	}

	public void add(String type, String actor, String text) {
		add(type, actor, text, false);
	}

	public void add(String type, String actor, String text, boolean restricted) {
		add(type, actor, text, restricted, null, NOTABLE);
	}

	/** An event one agent sensed. {@code actor} is null unless a player caused it. */
	public void sensed(String agent, String type, String actor, String text, int salience) {
		add(type, actor, text, false, agent, salience);
	}

	public void add(String type, String actor, String text, boolean restricted, String audience, int salience) {
		if (server == null) return;
		Event e;
		synchronized (this) {
			e = new Event(nextSeq++, server.getTickCount(), type, actor, text, restricted, audience, salience);
			events.addLast(e);
			while (events.size() > CAPACITY) events.removeFirst();
		}
		for (Consumer<Event> l : listeners) l.accept(e);
	}

	/** Called for every event, on the thread that added it (usually the server thread). Keep it quick. */
	public void listen(Consumer<Event> listener) {
		listeners.add(listener);
	}

	/**
	 * One page of unread events.
	 *
	 * @param events    oldest first
	 * @param cursor    the seq to read after next time
	 * @param remaining matching events after this page
	 * @param lost      whether events after the old cursor were already evicted from the buffer
	 */
	public record Page(List<Event> events, long cursor, int remaining, boolean lost) {
	}

	/**
	 * The oldest {@code limit} events with seq greater than {@code after}, skipping those caused by
	 * {@code agent} and those sensed by other agents (may be null). Oldest first, so nothing is skipped
	 * when a lot happens.
	 */
	public Page since(long after, String agent, int limit) {
		return since(after, agent, limit, true);
	}

	/** @param includeRestricted whether to include chat from players who may not direct the mod's agents */
	public synchronized Page since(long after, String agent, int limit, boolean includeRestricted) {
		List<Event> out = new ArrayList<>();
		int remaining = 0;
		long cursor = after;
		for (Event e : events) {
			if (e.seq() <= after) continue;
			boolean skip = agent != null ? !e.visibleTo(agent) : e.audience() != null;
			if (skip || e.restricted() && !includeRestricted) {
				if (out.size() < limit) cursor = e.seq(); // skip own events, but don't re-scan them
				continue;
			}
			if (out.size() < limit) {
				out.add(e);
				cursor = e.seq();
			} else {
				remaining++;
			}
		}
		if (remaining == 0) cursor = Math.max(cursor, nextSeq - 1);
		boolean lost = !events.isEmpty() && events.peekFirst().seq() > after + 1 && after < nextSeq - 1;
		return new Page(out, cursor, remaining, lost);
	}

	public synchronized long lastSeq() {
		return nextSeq - 1;
	}
}
