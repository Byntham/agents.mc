package dev.agentsmc.body;

/**
 * Something the body is doing over many ticks: walking somewhere, following a player, mining,
 * fighting. Intents run one at a time; the model starts one with a tool call and gets an event when
 * it ends. {@link #tick} runs on the server thread every tick while the intent is current.
 */
public abstract class Intent {
	public enum Status {
		RUNNING, DONE, FAILED, CANCELLED
	}

	int id;
	private Status status = Status.RUNNING;
	private String outcome = "";
	private int ticks;

	/** A short verb phrase for the status line, e.g. "walking to 100 64 -20". */
	public abstract String describe();

	/** One tick of work. Call {@link #done} or {@link #fail} to finish. */
	protected abstract void tick(Body body);

	/** Called once when the intent stops being current, for any reason. Release held input here. */
	protected void stop(Body body) {
	}

	/** Progress for the status line, e.g. "12/40 blocks". Empty when there is nothing to say. */
	public String progress() {
		return "";
	}

	/** Whether a reflex (fighting back, fleeing) may pause this intent and resume it afterwards. */
	public boolean pausable() {
		return true;
	}

	public final int id() {
		return id;
	}

	public final Status status() {
		return status;
	}

	public final String outcome() {
		return outcome;
	}

	/** Ticks spent running so far. */
	public final int ticks() {
		return ticks;
	}

	final void run(Body body) {
		ticks++;
		tick(body);
	}

	protected final void done(String outcome) {
		if (status == Status.RUNNING) {
			status = Status.DONE;
			this.outcome = outcome;
		}
	}

	protected final void fail(String reason) {
		if (status == Status.RUNNING) {
			status = Status.FAILED;
			this.outcome = reason;
		}
	}

	final void cancel(String reason) {
		if (status == Status.RUNNING) {
			status = Status.CANCELLED;
			this.outcome = reason;
		}
	}

	public final boolean finished() {
		return status != Status.RUNNING;
	}

	/** e.g. "goto#3". */
	public final String tag() {
		return kind() + "#" + id;
	}

	/** The tool name that starts this kind of intent. */
	public abstract String kind();

	@Override
	public String toString() {
		return tag() + " " + describe() + (progress().isEmpty() ? "" : ", " + progress());
	}
}
