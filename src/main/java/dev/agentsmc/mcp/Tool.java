package dev.agentsmc.mcp;

import com.google.gson.JsonObject;

/** @param forBoundAgents whether an agent bound to one body (see McpServer) may call it */
public record Tool(String name, String description, JsonObject inputSchema, Handler handler, boolean forBoundAgents) {
	@FunctionalInterface
	public interface Handler {
		/** Returns what the model sees. Throw {@link ToolException} for user-facing errors. */
		Result call(Args args) throws Exception;
	}

	/** A tool that only returns text. */
	@FunctionalInterface
	public interface TextHandler {
		/** Returns the text shown to the model. Throw {@link ToolException} for user-facing errors. */
		String call(Args args) throws Exception;
	}

	/** @param image a picture shown after the text, or null */
	public record Result(String text, Image image) {
	}

	/** @param mimeType e.g. image/jpeg */
	public record Image(byte[] data, String mimeType) {
	}
}
