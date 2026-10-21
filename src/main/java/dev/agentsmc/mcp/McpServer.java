package dev.agentsmc.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agentsmc.AgentsMc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A minimal, dependency-free MCP server over Streamable HTTP (JSON responses only, no SSE).
 *
 * <p>Dual-era: modern clients (2026-07-28) send per-request {@code _meta} and may call
 * {@code server/discover}; legacy clients (2025-11-25 and earlier) call {@code initialize} first.
 * The server keeps no sessions in either case, which legacy revisions permit.
 */
public final class McpServer {
	public static final String MODERN_VERSION = "2026-07-28";
	public static final List<String> LEGACY_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
	private static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
	private static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final HttpServer http;
	private final ExecutorService executor;
	private final ToolRegistry tools;
	private final String token;
	private final String instructions;

	public McpServer(String host, int port, String token, ToolRegistry tools, String instructions) throws IOException {
		this.tools = tools;
		this.token = token == null ? "" : token;
		this.instructions = instructions;
		this.http = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 0);
		this.executor = Executors.newFixedThreadPool(4, r -> {
			Thread t = new Thread(r, "agentsmc-mcp");
			t.setDaemon(true);
			return t;
		});
		http.setExecutor(executor);
		http.createContext("/mcp", this::handle);
	}

	public void start() {
		http.start();
	}

	public void stop() {
		http.stop(0);
		executor.shutdownNow();
	}

	public int port() {
		return http.getAddress().getPort();
	}

	private void handle(HttpExchange ex) throws IOException {
		try (ex) {
			// DNS-rebinding protection: browsers always send Origin; only accept local pages.
			String origin = ex.getRequestHeaders().getFirst("Origin");
			if (origin != null && !isLocalOrigin(origin)) {
				send(ex, 403, "text/plain", "Forbidden origin");
				return;
			}
			if (!token.isEmpty() && !("Bearer " + token).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
				send(ex, 401, "text/plain", "Missing or invalid bearer token");
				return;
			}
			if (!"POST".equals(ex.getRequestMethod())) {
				// No server-initiated stream is offered, which the spec allows.
				ex.getResponseHeaders().add("Allow", "POST");
				send(ex, 405, "text/plain", "Method not allowed");
				return;
			}

			JsonElement body;
			try {
				body = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			} catch (JsonParseException e) {
				send(ex, 400, "application/json", GSON.toJson(error(null, -32700, "Parse error", null)));
				return;
			}

			JsonElement response;
			if (body.isJsonArray()) {
				JsonArray out = new JsonArray();
				for (JsonElement el : body.getAsJsonArray()) {
					JsonObject r = el.isJsonObject() ? dispatch(el.getAsJsonObject()) : error(null, -32600, "Invalid request", null);
					if (r != null) out.add(r);
				}
				response = out.isEmpty() ? null : out;
			} else if (body.isJsonObject()) {
				response = dispatch(body.getAsJsonObject());
			} else {
				response = error(null, -32600, "Invalid request", null);
			}

			if (response == null) {
				send(ex, 202, null, null);
			} else {
				send(ex, 200, "application/json", GSON.toJson(response));
			}
		} catch (Exception e) {
			AgentsMc.LOGGER.error("MCP request failed", e);
		}
	}

	private JsonObject dispatch(JsonObject msg) {
		if (!msg.has("method")) return null; // a response to us; we never send requests
		JsonElement id = msg.get("id");
		if (id == null) return null; // notification (e.g. notifications/initialized)

		String method = msg.get("method").getAsString();
		JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();

		String requested = metaString(params, META_VERSION);
		if (requested != null && !requested.equals(MODERN_VERSION) && !LEGACY_VERSIONS.contains(requested)) {
			JsonObject data = new JsonObject();
			data.add("supported", supportedVersions());
			data.addProperty("requested", requested);
			return error(id, -32022, "Unsupported protocol version", data);
		}

		try {
			JsonObject result = switch (method) {
				case "initialize" -> initialize(params);
				case "server/discover" -> discover();
				case "ping" -> new JsonObject();
				case "tools/list" -> toolsList();
				case "tools/call" -> toolsCall(params);
				default -> throw new RpcException(-32601, "Method not found: " + method);
			};
			result.addProperty("resultType", "complete");
			JsonObject meta = result.has("_meta") ? result.getAsJsonObject("_meta") : new JsonObject();
			meta.add(META_SERVER_INFO, serverInfo());
			result.add("_meta", meta);
			JsonObject ok = new JsonObject();
			ok.addProperty("jsonrpc", "2.0");
			ok.add("id", id);
			ok.add("result", result);
			return ok;
		} catch (RpcException e) {
			return error(id, e.code, e.getMessage(), null);
		}
	}

	private JsonObject initialize(JsonObject params) {
		String asked = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : null;
		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", asked != null && LEGACY_VERSIONS.contains(asked) ? asked : LEGACY_VERSIONS.getFirst());
		result.add("capabilities", capabilities());
		result.add("serverInfo", serverInfo());
		result.addProperty("instructions", instructions);
		return result;
	}

	private JsonObject discover() {
		JsonObject result = new JsonObject();
		result.add("supportedVersions", supportedVersions());
		result.add("capabilities", capabilities());
		result.addProperty("instructions", instructions);
		result.addProperty("ttlMs", 60_000);
		result.addProperty("cacheScope", "private");
		return result;
	}

	private JsonObject toolsList() {
		JsonArray list = new JsonArray();
		for (Tool tool : tools.all()) {
			JsonObject t = new JsonObject();
			t.addProperty("name", tool.name());
			t.addProperty("description", tool.description());
			t.add("inputSchema", tool.inputSchema());
			list.add(t);
		}
		JsonObject result = new JsonObject();
		result.add("tools", list);
		result.addProperty("ttlMs", 60_000);
		result.addProperty("cacheScope", "private");
		return result;
	}

	private JsonObject toolsCall(JsonObject params) {
		String name = params.has("name") ? params.get("name").getAsString() : "";
		Tool tool = tools.get(name);
		if (tool == null) throw new RpcException(-32602, "Unknown tool: " + name);
		JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments") : new JsonObject();

		String text;
		boolean isError = false;
		try {
			text = tool.handler().call(new Args(arguments));
		} catch (ToolException e) {
			text = e.getMessage();
			isError = true;
		} catch (Exception e) {
			AgentsMc.LOGGER.warn("Tool {} failed", name, e);
			text = "Internal error: " + e;
			isError = true;
		}

		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", text);
		JsonArray contents = new JsonArray();
		contents.add(content);
		JsonObject result = new JsonObject();
		result.add("content", contents);
		result.addProperty("isError", isError);
		return result;
	}

	private static JsonObject capabilities() {
		JsonObject caps = new JsonObject();
		JsonObject toolsCap = new JsonObject();
		toolsCap.addProperty("listChanged", false);
		caps.add("tools", toolsCap);
		return caps;
	}

	private static JsonObject serverInfo() {
		JsonObject info = new JsonObject();
		info.addProperty("name", AgentsMc.MOD_ID);
		info.addProperty("version", AgentsMc.version());
		return info;
	}

	private static JsonArray supportedVersions() {
		JsonArray arr = new JsonArray();
		arr.add(MODERN_VERSION);
		LEGACY_VERSIONS.forEach(arr::add);
		return arr;
	}

	private static String metaString(JsonObject params, String key) {
		if (!params.has("_meta") || !params.get("_meta").isJsonObject()) return null;
		JsonElement v = params.getAsJsonObject("_meta").get(key);
		return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
	}

	private static JsonObject error(JsonElement id, int code, String message, JsonObject data) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		if (data != null) err.add("data", data);
		JsonObject out = new JsonObject();
		out.addProperty("jsonrpc", "2.0");
		out.add("id", id);
		out.add("error", err);
		return out;
	}

	private static boolean isLocalOrigin(String origin) {
		try {
			String host = URI.create(origin).getHost();
			return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
		if (body == null) {
			ex.sendResponseHeaders(status, -1);
			return;
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		if (contentType != null) ex.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	private static final class RpcException extends RuntimeException {
		final int code;

		RpcException(int code, String message) {
			super(message);
			this.code = code;
		}
	}
}
