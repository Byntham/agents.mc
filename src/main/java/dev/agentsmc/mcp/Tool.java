package dev.agentsmc.mcp;

import com.google.gson.JsonObject;

/** @param forBoundAgents whether an agent bound to one body (see McpServer) may call it */
public record Tool(String name, String description, JsonObject inputSchema, Handler handler, boolean forBoundAgents) {
	@FunctionalInterface
	public interface Handler {
		/** Returns the text shown to the model. Throw {@link ToolException} for user-facing errors. */
		String call(Args args) throws Exception;
	}
}
