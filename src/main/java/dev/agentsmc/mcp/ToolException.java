package dev.agentsmc.mcp;

/** An error the model should see and can act on (bad arguments, unknown agent, etc.). */
public class ToolException extends RuntimeException {
	public ToolException(String message) {
		super(message);
	}
}
