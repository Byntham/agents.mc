package dev.agentsmc.agent;

import dev.agentsmc.mixin.ConnectionAccessor;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;

import java.util.function.Consumer;

/**
 * A network connection with nobody on the other end. The server treats the agent as a normal
 * connected player; packets it would send to the agent's client are dropped.
 */
public final class FakeConnection extends Connection {
	private final Consumer<Component> onKick;

	/** @param onKick called when the server tries to disconnect this player (kick, duplicate login, ban) */
	public FakeConnection(Consumer<Component> onKick) {
		super(PacketFlow.SERVERBOUND);
		this.onKick = onKick;
		// An open, handler-less channel makes isConnected() true and gives code that inspects the
		// channel (remote address, event loop) something sane to look at.
		((ConnectionAccessor) (Object) this).agentsmc$setChannel(new EmbeddedChannel());
	}

	@Override
	public void send(Packet<?> packet) {
		send(packet, null, true);
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener) {
		send(packet, listener, true);
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
		// Vanilla disconnects a player by sending this packet and finishing once it is delivered.
		// Nothing is delivered here, so hand the request to the agent manager instead.
		if (packet instanceof ClientboundDisconnectPacket disconnect) onKick.accept(disconnect.reason());
	}

	@Override
	public void setReadOnly() {
	}

	@Override
	public void handleDisconnection() {
	}

	@Override
	public void setListenerForServerboundHandshake(PacketListener listener) {
	}

	@Override
	public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) {
	}
}
