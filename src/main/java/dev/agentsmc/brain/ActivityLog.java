package dev.agentsmc.brain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** What an agent has said and done, oldest first, for the agents menu. Thread-safe. */
public final class ActivityLog {
	private static final int CAPACITY = 300;

	public enum Kind {
		/** A message from a player. {@code who} is the player. */
		USER,
		/** Text the model wrote outside of tools. Not shown in chat. */
		TEXT,
		/** A tool call, summarised. */
		TOOL,
		/** A chat message the agent sent with the say tool. */
		SAY,
		ERROR,
		/** Turn finished, session started, and similar notes. */
		INFO
	}

	/** @param time wall-clock millis */
	public record Entry(long id, long time, Kind kind, String who, String text) {
	}

	private final ArrayDeque<Entry> entries = new ArrayDeque<>();
	private long nextId = 1;

	public synchronized Entry add(Kind kind, String who, String text) {
		Entry e = new Entry(nextId++, System.currentTimeMillis(), kind, who, text);
		entries.addLast(e);
		while (entries.size() > CAPACITY) entries.removeFirst();
		return e;
	}

	/** The newest {@code n} entries, oldest first. */
	public synchronized List<Entry> tail(int n) {
		List<Entry> all = new ArrayList<>(entries);
		return all.subList(Math.max(0, all.size() - n), all.size());
	}

	public synchronized Entry last() {
		return entries.peekLast();
	}

	/** Puts back entries saved with the world, before anything new is added. */
	synchronized void restore(List<Entry> saved) {
		for (Entry e : saved) {
			if (e == null || e.kind() == null || e.text() == null) continue;
			entries.addLast(e);
			nextId = Math.max(nextId, e.id() + 1);
		}
		while (entries.size() > CAPACITY) entries.removeFirst();
	}
}
