package dev.agentsmc.agent;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

/**
 * A server-side player driven by an AI agent instead of a game client. Humans see it as a normal
 * player: it has a name tag, shows in the tab list, takes damage, and is saved with the world.
 */
public final class AgentPlayer extends ServerPlayer {
	private final AgentManager manager;

	AgentPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, AgentManager manager) {
		super(server, level, profile, ClientInformation.createDefault());
		this.manager = manager;
		// Show all skin layers (hat, jacket, sleeves...), like a client with default settings would.
		this.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);
	}

	@Override
	public void tick() {
		// A real client's packets would drive movement physics (doTick) and chunk tracking; do it here.
		if (level().getServer().getTickCount() % 10 == 0) {
			connection.resetPosition();
			level().getChunkSource().move(this);
		}
		super.tick();
		doTick();
	}

	@Override
	public boolean isClientAuthoritative() {
		// Players' movement is normally owned by their client, so the server skips things like fall
		// damage. An agent has no client: the server is the authority.
		return false;
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		manager.onDeath(this);
	}
}
