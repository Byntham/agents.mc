package dev.agentsmc.tools;

import dev.agentsmc.Session;
import dev.agentsmc.mcp.ToolException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets tools take a player name as a target. Positions move constantly, so tools resolve names at
 * call time instead of making agents copy coordinates that may already be stale.
 */
final class Targets {
	private Targets() {
	}

	/** An online player (human or agent) by name, case-insensitive. */
	static ServerPlayer player(Session s, String name) {
		ServerPlayer p = s.server.getPlayerList().getPlayerByName(name);
		if (p != null) return p;
		List<String> online = new ArrayList<>();
		for (ServerPlayer o : s.server.getPlayerList().getPlayers()) online.add(o.getName().getString());
		throw new ToolException("No player named '" + name + "' is online. Online: " + (online.isEmpty() ? "nobody" : String.join(", ", online)));
	}

	/** The named player, or the only human online when no name is given. */
	static ServerPlayer playerOrOnlyHuman(Session s, String name) {
		if (name != null) return player(s, name);
		List<ServerPlayer> humans = s.agents.humans();
		if (humans.size() == 1) return humans.getFirst();
		throw new ToolException(humans.isEmpty() ? "No human players are online" : "Several humans are online; pass a player name");
	}

	/**
	 * A spot about {@code distance} blocks in front of the player where a body fits. Starts level with
	 * their feet and steps up out of terrain, so a spot on a hillside isn't inside the hill.
	 */
	static Vec3 inFrontOf(ServerPlayer p, double distance) {
		Vec3 look = p.getLookAngle().multiply(1, 0, 1);
		if (look.lengthSqr() < 1e-6) look = new Vec3(0, 0, 1); // looking straight up or down
		Vec3 pos = p.position().add(look.normalize().scale(distance));
		ServerLevel level = p.level();
		for (int i = 0; i < 8; i++) {
			BlockPos feet = BlockPos.containing(pos);
			if (passable(level, feet) && passable(level, feet.above())) return pos;
			pos = new Vec3(pos.x, feet.getY() + 1, pos.z);
		}
		return p.position(); // nowhere to stand in front of them; share their spot
	}

	private static boolean passable(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	/** Minecraft yaw (0 south, 90 west, 180 north, -90 east) that faces from one point toward another. */
	static float yawToward(Vec3 from, Vec3 to) {
		return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
	}
}
