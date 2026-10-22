package dev.agentsmc;

import dev.agentsmc.agent.AgentManager;
import dev.agentsmc.body.Waiters;
import dev.agentsmc.brain.Brains;
import dev.agentsmc.mcp.McpServer;
import dev.agentsmc.mcp.ToolRegistry;
import dev.agentsmc.perception.EventLog;
import net.minecraft.server.MinecraftServer;

/** Everything that lives for the duration of one running server (one opened world). */
public final class Session {
	public final MinecraftServer server;
	public final Config config;
	public final AgentManager agents;
	public final EventLog events;
	public final ToolRegistry tools;
	/** Blocked wait tool calls, woken by events. */
	public final Waiters waiters = new Waiters();
	public McpServer mcp;
	/** Agents the mod runs with Claude Code. */
	public Brains brains;

	Session(MinecraftServer server, Config config, AgentManager agents, EventLog events, ToolRegistry tools) {
		this.server = server;
		this.config = config;
		this.agents = agents;
		this.events = events;
		this.tools = tools;
	}
}
