package dev.agentsmc.net;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packets for the agents menu. The client asks for the menu state about twice a second while the
 * menu is open and the server answers with a JSON snapshot, so nothing is pushed to players who
 * aren't looking.
 */
public final class MenuPayloads {
	/** Max size of the state JSON, in characters. The server keeps snapshots well below it. */
	private static final int MAX_STATE = 1 << 20;

	private MenuPayloads() {
	}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String path) {
		return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("agentsmc", path));
	}

	/** Client to server: send me the menu state, with this agent's log (may be empty). */
	public record Request(String selected) implements CustomPacketPayload {
		public static final Type<Request> TYPE = id("menu_request");
		public static final StreamCodec<ByteBuf, Request> CODEC = ByteBufCodecs.stringUtf8(64).map(Request::new, Request::selected);

		@Override
		public Type<Request> type() {
			return TYPE;
		}
	}

	/**
	 * Client to server: do something with an agent.
	 *
	 * @param action "send" (text is the message), "stop", "spawn" (text is an optional first task),
	 *               "configure" (switch to model and effort) or "remove"
	 * @param model  for "spawn" and "configure": a model id from the menu state, "" for the default
	 * @param effort for "spawn" and "configure": an effort id from the menu state, "" for the default
	 */
	public record Action(String action, String agent, String text, String model, String effort) implements CustomPacketPayload {
		public static final Type<Action> TYPE = id("menu_action");
		public static final StreamCodec<ByteBuf, Action> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(16), Action::action,
				ByteBufCodecs.stringUtf8(64), Action::agent,
				ByteBufCodecs.stringUtf8(8192), Action::text,
				ByteBufCodecs.stringUtf8(128), Action::model,
				ByteBufCodecs.stringUtf8(64), Action::effort,
				Action::new);

		public Action(String action, String agent, String text) {
			this(action, agent, text, "", "");
		}

		@Override
		public Type<Action> type() {
			return TYPE;
		}
	}

	/** Server to client: the menu state as JSON (see Brains.snapshot). */
	public record State(String json) implements CustomPacketPayload {
		public static final Type<State> TYPE = id("menu_state");
		public static final StreamCodec<ByteBuf, State> CODEC = ByteBufCodecs.stringUtf8(MAX_STATE).map(State::new, State::json);

		@Override
		public Type<State> type() {
			return TYPE;
		}
	}

	/** Server to client: open the agents menu (from /agent menu). */
	public record Open() implements CustomPacketPayload {
		public static final Type<Open> TYPE = id("menu_open");
		public static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.unit(new Open());

		@Override
		public Type<Open> type() {
			return TYPE;
		}
	}

	/** Registers the payload types. Runs on both sides, from the common initializer. */
	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Action.TYPE, Action.CODEC);
		PayloadTypeRegistry.clientboundPlay().registerLarge(State.TYPE, State.CODEC, MAX_STATE * 4);
		PayloadTypeRegistry.clientboundPlay().register(Open.TYPE, Open.CODEC);
	}
}
