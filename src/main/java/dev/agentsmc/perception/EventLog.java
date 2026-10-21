package dev.agentsmc.perception;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Things that happened that an agent can't see by polling blocks: chat, joins, and block changes made
 * by players and other agents. Agents read it incrementally with a cursor (the seq of the last event seen).
 */
public final class EventLog {
	public static final EventLog DISCARD = new EventLog(null);
	private static final int CAPACITY = 1000;

	/**
	 * {@code actor} is the player who caused the event, if any; agents don't get their own events echoed back.
	 * {@code restricted} marks chat from a player who isn't allowed to direct the agents the mod runs, so
	 * those agents don't read it as instructions.
	 */
	public record Event(long seq, int tick, String type, String actor, String text, boolean restricted) {
	}

	private final MinecraftServer server;
	private final ArrayDeque<Event> events = new ArrayDeque<>();
	private long nextSeq = 1;

	public EventLog(MinecraftServer server) {
		this.server = server;
	}

	public void add(String type, String actor, String text) {
		add(type, actor, text, false);
	}

	public synchronized void add(String type, String actor, String text, boolean restricted) {
		if (server == null) return;
		events.addLast(new Event(nextSeq++, server.getTickCount(), type, actor, text, restricted));
		while (events.size() > CAPACITY) events.removeFirst();
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
	 * {@code excludeActor} (may be null). Oldest first, so nothing is skipped when a lot happens.
	 */
	public Page since(long after, String excludeActor, int limit) {
		return since(after, excludeActor, limit, true);
	}

	/** @param includeRestricted whether to include chat from players who may not direct the mod's agents */
	public synchronized Page since(long after, String excludeActor, int limit, boolean includeRestricted) {
		List<Event> out = new ArrayList<>();
		int remaining = 0;
		long cursor = after;
		for (Event e : events) {
			if (e.seq() <= after) continue;
			if (excludeActor != null && excludeActor.equalsIgnoreCase(e.actor()) || e.restricted() && !includeRestricted) {
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
