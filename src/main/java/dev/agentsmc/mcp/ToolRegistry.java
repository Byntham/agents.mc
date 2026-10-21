package dev.agentsmc.mcp;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tools in registration order, so tools/list output is deterministic. */
public final class ToolRegistry {
	private final Map<String, Tool> tools = new LinkedHashMap<>();

	public void register(String name, String description, Schema schema, Tool.Handler handler) {
		if (tools.putIfAbsent(name, new Tool(name, description, schema.build(), handler)) != null) {
			throw new IllegalStateException("Duplicate tool " + name);
		}
	}

	public Tool get(String name) {
		return tools.get(name);
	}

	public Collection<Tool> all() {
		return tools.values();
	}
}
