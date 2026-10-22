package dev.agentsmc.tools;

import dev.agentsmc.Session;
import dev.agentsmc.agent.AgentPlayer;
import dev.agentsmc.mcp.ToolException;
import net.minecraft.world.level.GameType;

/**
 * What a body may do depends on its game mode. A survival or adventure body plays by the rules a
 * human in that mode plays by: it walks, digs, crafts and places from its inventory. Teleporting,
 * commands and instant builds are for creative bodies. {@code survivalRules} in the config turns
 * this off for people driving bodies from outside the game.
 */
public final class Rules {
	private Rules() {
	}

	/** Whether the body plays by survival rules: walks, digs, places by hand. */
	public static boolean playsByTheRules(Session s, AgentPlayer a) {
		if (!s.config.survivalRules) return false;
		GameType mode = a.gameMode();
		return mode != GameType.CREATIVE && mode != GameType.SPECTATOR;
	}

	/** Throws unless the body is creative or spectator, or the rules are off. */
	public static void creativeOnly(Session s, AgentPlayer a, String tool, String instead) {
		if (!s.config.survivalRules) return;
		GameType mode = a.gameMode();
		if (mode == GameType.CREATIVE || mode == GameType.SPECTATOR) return;
		throw new ToolException(tool + " is for creative bodies. You are in " + mode.getName() + ", so play it like a player would: " + instead);
	}
}
