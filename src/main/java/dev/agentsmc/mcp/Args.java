package dev.agentsmc.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Typed access to tool arguments with model-readable errors. */
public final class Args {
	private final JsonObject json;

	public Args(JsonObject json) {
		this.json = json;
	}

	public boolean has(String name) {
		JsonElement e = json.get(name);
		return e != null && !e.isJsonNull();
	}

	public String str(String name) {
		return require(name).getAsString();
	}

	public String str(String name, String def) {
		return has(name) ? json.get(name).getAsString() : def;
	}

	public int integer(String name) {
		try {
			return require(name).getAsInt();
		} catch (NumberFormatException | UnsupportedOperationException e) {
			throw new ToolException("Argument '" + name + "' must be an integer");
		}
	}

	public int integer(String name, int def) {
		return has(name) ? integer(name) : def;
	}

	public double number(String name) {
		try {
			return require(name).getAsDouble();
		} catch (NumberFormatException | UnsupportedOperationException e) {
			throw new ToolException("Argument '" + name + "' must be a number");
		}
	}

	public double number(String name, double def) {
		return has(name) ? number(name) : def;
	}

	public boolean bool(String name, boolean def) {
		return has(name) ? json.get(name).getAsBoolean() : def;
	}

	public JsonArray array(String name) {
		JsonElement e = require(name);
		if (!e.isJsonArray()) throw new ToolException("Argument '" + name + "' must be an array");
		return e.getAsJsonArray();
	}

	public JsonObject object(String name) {
		JsonElement e = require(name);
		if (!e.isJsonObject()) throw new ToolException("Argument '" + name + "' must be an object");
		return e.getAsJsonObject();
	}

	private JsonElement require(String name) {
		if (!has(name)) throw new ToolException("Missing required argument '" + name + "'");
		return json.get(name);
	}
}
