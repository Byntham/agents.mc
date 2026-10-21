package dev.agentsmc.agent;

import dev.agentsmc.mixin.ConnectionAccessor;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A network connection with nobody on the other end. The server treats the agent as a normal
 * connected player; packets it would send to the agent's client are dropped.
 */
public final class FakeConnection extends Connection {
	public FakeConnection() {
		super(PacketFlow.SERVERBOUND);
		// An open, handler-less channel makes isConnected() true and gives code that inspects the
		// channel (remote address, event loop) something sane to look at.
		((ConnectionAccessor) (Object) this).agentsmc$setChannel(new EmbeddedChannel());
	}

	@Override
	public void send(Packet<?> packet) {
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener) {
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
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
