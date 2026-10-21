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

	/** {@code actor} is the player who caused the event, if any; agents don't get their own events echoed back. */
	public record Event(long seq, int tick, String type, String actor, String text) {
	}

	private final MinecraftServer server;
	private final ArrayDeque<Event> events = new ArrayDeque<>();
	private long nextSeq = 1;

	public EventLog(MinecraftServer server) {
		this.server = server;
	}

	public synchronized void add(String type, String actor, String text) {
		if (server == null) return;
		events.addLast(new Event(nextSeq++, server.getTickCount(), type, actor, text));
		while (events.size() > CAPACITY) events.removeFirst();
	}

	/**
	 * Events with seq greater than {@code after}, oldest first, skipping those caused by
	 * {@code excludeActor} (may be null); if more than {@code limit}, the most recent ones.
	 */
	public synchronized List<Event> since(long after, String excludeActor, int limit) {
		List<Event> out = new ArrayList<>();
		for (Event e : events) {
			if (e.seq() > after && (excludeActor == null || !excludeActor.equalsIgnoreCase(e.actor()))) out.add(e);
		}
		return out.size() > limit ? new ArrayList<>(out.subList(out.size() - limit, out.size())) : out;
	}

	public synchronized long lastSeq() {
		return nextSeq - 1;
	}
}
