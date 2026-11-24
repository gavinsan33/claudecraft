package dev.claudecraft;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal MCP server (streamable HTTP transport, JSON responses only) bound to localhost.
 * The URL path contains a random secret so other local processes can't drive the game.
 */
public class McpServer {
	private static final Gson GSON = new Gson();

	/** What the endpoint serves: the game's tools, or (on a player's own client) tools forwarded to the host. */
	public interface Tools {
		JsonArray listTools();

		/** @param session which concurrent Claude session is calling (the number at the end of its URL) */
		JsonObject call(int session, String name, JsonObject args);
	}

	private final Tools tools;
	private final String secret;
	private HttpServer http;
	private ExecutorService pool;

	public McpServer(Tools tools) {
		this.tools = tools;
		byte[] b = new byte[24];
		new SecureRandom().nextBytes(b);
		this.secret = HexFormat.of().formatHex(b);
	}

	public void start(int port) throws IOException {
		http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		pool = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "ClaudeCraft-MCP");
			t.setDaemon(true);
			return t;
		});
		http.setExecutor(pool);
		http.createContext("/", this::handle);
		http.start();
		ClaudeCraft.LOGGER.info("ClaudeCraft MCP endpoint listening on 127.0.0.1:{}", http.getAddress().getPort());
	}

	public void stop() {
		if (http != null) http.stop(0);
		if (pool != null) pool.shutdownNow();
	}

	/** The endpoint for one Claude session; the session number in the path tells tool calls apart. */
	public String url(int session) {
		return "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp/" + secret + "/" + session;
	}

	/** The session number from a request path, or -1 if the path is wrong. */
	private int sessionOf(String path) {
		String prefix = "/mcp/" + secret;
		if (path.equals(prefix)) return 0;
		if (!path.startsWith(prefix + "/")) return -1;
		try {
			return Math.max(-1, Integer.parseInt(path.substring(prefix.length() + 1)));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private void handle(HttpExchange ex) throws IOException {
		try (ex) {
			int session = sessionOf(ex.getRequestURI().getPath());
			if (session < 0) {
				send(ex, 404, null);
				return;
			}
			switch (ex.getRequestMethod()) {
				case "POST" -> {
					String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
					JsonElement in = JsonParser.parseString(body);
					JsonElement out;
					if (in.isJsonArray()) {
						JsonArray responses = new JsonArray();
						for (JsonElement msg : in.getAsJsonArray()) {
							JsonObject r = dispatch(session, msg.getAsJsonObject());
							if (r != null) responses.add(r);
						}
						out = responses.isEmpty() ? null : responses;
					} else {
						out = dispatch(session, in.getAsJsonObject());
					}
					if (out == null) send(ex, 202, null);
					else send(ex, 200, GSON.toJson(out));
				}
				case "DELETE" -> send(ex, 200, null);
				default -> send(ex, 405, null); // no server-initiated SSE stream
			}
		} catch (Exception e) {
			ClaudeCraft.LOGGER.error("MCP request failed", e);
			try { send(ex, 500, null); } catch (IOException ignored) {}
		}
	}

	private static void send(HttpExchange ex, int code, String json) throws IOException {
		if (json == null) {
			ex.sendResponseHeaders(code, -1);
			return;
		}
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(code, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** Returns the JSON-RPC response, or null for notifications. */
	private JsonObject dispatch(int session, JsonObject msg) {
		if (!msg.has("method")) return null; // a response to us; we never send requests
		JsonElement id = msg.get("id");
		if (id == null || id.isJsonNull()) return null; // notification
		String method = msg.get("method").getAsString();
		JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();

		JsonObject resp = new JsonObject();
		resp.addProperty("jsonrpc", "2.0");
		resp.add("id", id);
		switch (method) {
			case "initialize" -> {
				JsonObject result = new JsonObject();
				String version = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : "2025-06-18";
				result.addProperty("protocolVersion", version);
				JsonObject caps = new JsonObject();
				caps.add("tools", new JsonObject());
				result.add("capabilities", caps);
				JsonObject info = new JsonObject();
				info.addProperty("name", "minecraft");
				info.addProperty("version", "1.0");
				result.add("serverInfo", info);
				result.addProperty("instructions", "Live connection to a Minecraft world. Use these tools to observe and change it.");
				resp.add("result", result);
			}
			case "ping" -> resp.add("result", new JsonObject());
			case "tools/list" -> {
				JsonObject result = new JsonObject();
				result.add("tools", tools.listTools());
				resp.add("result", result);
			}
			case "tools/call" -> {
				String name = params.get("name").getAsString();
				JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
					? params.getAsJsonObject("arguments") : new JsonObject();
				resp.add("result", tools.call(session, name, args));
			}
			default -> {
				JsonObject err = new JsonObject();
				err.addProperty("code", -32601);
				err.addProperty("message", "Method not found: " + method);
				resp.add("error", err);
			}
		}
		return resp;
	}
}
