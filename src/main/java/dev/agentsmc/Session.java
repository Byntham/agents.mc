package dev.agentsmc;

import dev.agentsmc.agent.AgentManager;
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
	McpServer mcp;

	Session(MinecraftServer server, Config config, AgentManager agents, EventLog events, ToolRegistry tools) {
		this.server = server;
		this.config = config;
		this.agents = agents;
		this.events = events;
		this.tools = tools;
	}
}
