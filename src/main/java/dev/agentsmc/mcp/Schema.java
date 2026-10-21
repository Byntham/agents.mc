package dev.agentsmc.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Small builder for a tool's JSON Schema input object. */
public final class Schema {
	private final JsonObject properties = new JsonObject();
	private final JsonArray required = new JsonArray();

	public static Schema of() {
		return new Schema();
	}

	public Schema str(String name, String description, boolean req) {
		return prop(name, "string", description, req);
	}

	public Schema integer(String name, String description, boolean req) {
		return prop(name, "integer", description, req);
	}

	public Schema number(String name, String description, boolean req) {
		return prop(name, "number", description, req);
	}

	public Schema bool(String name, String description, boolean req) {
		return prop(name, "boolean", description, req);
	}

	public Schema enumStr(String name, String description, boolean req, String... values) {
		JsonObject p = new JsonObject();
		p.addProperty("type", "string");
		p.addProperty("description", description);
		JsonArray e = new JsonArray();
		for (String v : values) e.add(v);
		p.add("enum", e);
		return raw(name, p, req);
	}

	/** Adds the agent parameter shared by most tools. */
	public Schema agent(boolean req) {
		return str("agent", "Name of the agent body (from spawn_agent / list_agents).", req);
	}

	/** Adds integer x, y, z block coordinates. */
	public Schema xyz(String what, boolean req) {
		integer("x", what + " block x", req);
		integer("y", what + " block y", req);
		return integer("z", what + " block z", req);
	}

	public Schema raw(String name, JsonObject schema, boolean req) {
		properties.add(name, schema);
		if (req) required.add(name);
		return this;
	}

	private Schema prop(String name, String type, String description, boolean req) {
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("description", description);
		return raw(name, p, req);
	}

	public JsonObject build() {
		JsonObject s = new JsonObject();
		s.addProperty("type", "object");
		s.add("properties", properties);
		if (!required.isEmpty()) s.add("required", required);
		return s;
	}
}
