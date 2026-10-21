package dev.agentsmc.mcp;

import com.google.gson.JsonObject;

public record Tool(String name, String description, JsonObject inputSchema, Handler handler) {
	@FunctionalInterface
	public interface Handler {
		/** Returns the text shown to the model. Throw {@link ToolException} for user-facing errors. */
		String call(Args args) throws Exception;
	}
}
