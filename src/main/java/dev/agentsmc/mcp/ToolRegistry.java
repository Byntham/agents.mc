package dev.agentsmc.mcp;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tools in registration order, so tools/list output is deterministic. */
public final class ToolRegistry {
	private final Map<String, Tool> tools = new LinkedHashMap<>();

	public void register(String name, String description, Schema schema, Tool.Handler handler) {
		add(new Tool(name, description, schema.build(), handler, true));
	}

	/** A tool that manages bodies rather than acting as one, so agents bound to a body don't get it. */
	public void registerUnbound(String name, String description, Schema schema, Tool.Handler handler) {
		add(new Tool(name, description, schema.build(), handler, false));
	}

	private void add(Tool tool) {
		if (tools.putIfAbsent(tool.name(), tool) != null) throw new IllegalStateException("Duplicate tool " + tool.name());
	}

	public Tool get(String name) {
		return tools.get(name);
	}

	public Collection<Tool> all() {
		return tools.values();
	}
}
