package dev.claudecraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.claudecraft.ClaudeCraft;
import dev.claudecraft.ClaudeProcess;
import dev.claudecraft.Config;
import dev.claudecraft.GameTools;
import dev.claudecraft.McpServer;
import dev.claudecraft.RemoteLink;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs Claude on this computer, with this player's own Claude Code account, while playing in someone else's world
 * ({@link Config#useOwnClaude}). The host sends each request as a turn; Claude's tool calls go back to the host to
 * be carried out, except view_build pictures of places this game already has loaded, which are drawn right here.
 * See {@link RemoteLink} for the messages.
 */
public final class OwnClaude implements McpServer.Tools {
	private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve(ClaudeCraft.MOD_ID);
	/** Tool calls that place blocks animate for a while; this is generous. */
	private static final long TOOL_TIMEOUT_MINUTES = 35;

	private static volatile OwnClaude current;
	private static final java.util.concurrent.atomic.AtomicInteger LOCAL_VIEWS = new java.util.concurrent.atomic.AtomicInteger();

	/** Whether the host accepted this player's own Claude and it's ready for turns. For tests. */
	public static boolean isReady() {
		OwnClaude c = current;
		return c != null && c.mcp != null;
	}

	/** How many view_build pictures were drawn on this computer instead of the host's. For tests. */
	public static int localViews() {
		return LOCAL_VIEWS.get();
	}

	static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");

	static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			if (Config.load(CONFIG_FILE).useOwnClaude) start();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			OwnClaude c = current;
			current = null;
			if (c != null) c.close();
		});
		ClientPlayNetworking.registerGlobalReceiver(RemoteLink.Payload.TYPE, (payload, ctx) -> {
			try {
				JsonObject msg = payload.parse();
				switch (msg.get("type").getAsString()) {
					case "settings" -> ClaudeCraftScreen.onSettings(msg);
					case "invite" -> {
						if (current == null && Config.load(CONFIG_FILE).useOwnClaude) start();
					}
					default -> {
						OwnClaude c = current;
						if (c != null) c.onMessage(msg);
					}
				}
			} catch (RuntimeException e) {
				ClaudeCraft.LOGGER.warn("Bad ClaudeCraft link message from the host", e);
			}
		});
	}

	/** Whether this game could run its own Claude here: someone else's world that has ClaudeCraft. Client thread. */
	static boolean possibleHere() {
		Minecraft mc = Minecraft.getInstance();
		// Hosting (singleplayer or open to LAN): your Claude already runs here as the host's.
		return mc.getConnection() != null && !mc.isLocalServer() && ClientPlayNetworking.canSend(RemoteLink.Payload.TYPE);
	}

	/** Ask the host to send this player's messages here. Client thread. */
	private static void start() {
		if (current != null || !possibleHere()) return;
		current = new OwnClaude(Config.load(CONFIG_FILE));
		ClientPlayNetworking.send(new RemoteLink.Payload(RemoteLink.message("hello")));
	}

	/** The "Use my own Claude" switch in the menu: saves it and takes effect right away. Client thread. */
	static void setEnabled(boolean on) {
		Config.saveSetting(CONFIG_FILE, "useOwnClaude", new com.google.gson.JsonPrimitive(on));
		if (on) {
			start();
			return;
		}
		OwnClaude c = current;
		current = null;
		if (c == null) return;
		ClientPlayNetworking.send(new RemoteLink.Payload(RemoteLink.message("bye")));
		c.close();
		tell(Component.literal("ClaudeCraft: your messages go to the host's Claude again.").withStyle(ChatFormatting.GRAY));
	}

	private final Config cfg;
	private final Path sessionsFile = CONFIG_DIR.resolve("sessions.json");
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "ClaudeCraft-OwnClaude");
		t.setDaemon(true);
		return t;
	});
	private final AtomicLong ids = new AtomicLong();
	private final Map<Long, CompletableFuture<JsonObject>> toolCalls = new ConcurrentHashMap<>();
	private final Map<Long, CompletableFuture<String>> idleWaits = new ConcurrentHashMap<>();
	private volatile McpServer mcp;
	private volatile JsonArray toolList = new JsonArray();
	private volatile String sessionKey;
	private volatile String sessionId;
	private volatile ClaudeProcess proc;
	private volatile int turn;
	private volatile boolean closed;

	private OwnClaude(Config cfg) {
		this.cfg = cfg;
	}

	/** Client thread. */
	private void onMessage(JsonObject msg) {
		switch (msg.get("type").getAsString()) {
			case "welcome" -> {
				toolList = msg.getAsJsonArray("tools");
				sessionKey = "remote:" + msg.get("world").getAsString();
				// Saved before worlds had ids: move it from the world's name to its id, once.
				if (msg.has("worldName") && !ClaudeProcess.loadSessions(sessionsFile).containsKey(sessionKey)) {
					String oldKey = "remote:" + msg.get("worldName").getAsString(), old = ClaudeProcess.loadSessions(sessionsFile).get(oldKey);
					if (old != null) {
						ClaudeProcess.saveSession(sessionsFile, sessionKey, old);
						ClaudeProcess.saveSession(sessionsFile, oldKey, null);
					}
				}
				sessionId = ClaudeProcess.loadSessions(sessionsFile).get(sessionKey);
				if (mcp == null) {
					try {
						McpServer m = new McpServer(this);
						m.start(0);
						mcp = m;
					} catch (Exception e) {
						ClaudeCraft.LOGGER.error("Couldn't start the local MCP endpoint", e);
						tell(Component.literal("ClaudeCraft: couldn't start your own Claude: " + e.getMessage()).withStyle(ChatFormatting.RED));
						return;
					}
				}
				tell(Component.literal("ClaudeCraft: your messages go to Claude on this computer, with your own account.")
					.withStyle(ChatFormatting.GOLD));
				ClaudeCraftScreen.refresh();
			}
			case "declined" -> {
				tell(Component.literal("ClaudeCraft: " + msg.get("reason").getAsString()).withStyle(ChatFormatting.GRAY));
				if (current == this) current = null;
				close();
				ClaudeCraftScreen.refresh();
			}
			case "turn" -> {
				int t = msg.get("turn").getAsInt();
				String prompt = msg.get("prompt").getAsString();
				worker.execute(() -> runTurn(t, prompt));
			}
			case "tool_result" -> {
				CompletableFuture<JsonObject> f = toolCalls.remove(msg.get("id").getAsLong());
				if (f != null) f.complete(msg.getAsJsonObject("result"));
			}
			case "builds_idle" -> {
				CompletableFuture<String> f = idleWaits.remove(msg.get("id").getAsLong());
				if (f != null) f.complete(msg.has("reports") ? msg.get("reports").getAsString() : "");
			}
			case "stop" -> {
				ClaudeProcess p = proc;
				if (p != null) p.cancel();
			}
			case "reset" -> {
				sessionId = null;
				if (sessionKey != null) ClaudeProcess.saveSession(sessionsFile, sessionKey, null);
			}
			default -> {}
		}
	}

	// ---------------------------------------------------------------- turns (worker thread)

	private void runTurn(int t, String prompt) {
		turn = t;
		String error = null;
		try {
			error = runOnce(t, prompt, true);
		} catch (Exception e) {
			ClaudeCraft.LOGGER.error("Claude turn failed", e);
			error = String.valueOf(e.getMessage());
		} finally {
			proc = null;
			JsonObject done = message("done", t);
			if (error != null) done.addProperty("error", error);
			send(done);
		}
	}

	/** Returns an error message, or null if the turn went fine (or was stopped). */
	private String runOnce(int t, String prompt, boolean allowRetry) throws Exception {
		McpServer m = mcp;
		if (m == null) return "Claude isn't set up on " + playerName() + "'s computer.";
		ClaudeProcess p = new ClaudeProcess();
		proc = p;
		ClaudeProcess.Result r = p.run(cfg, CONFIG_DIR.resolve("workspace"), ClaudeProcess.readSystemPrompt(CONFIG_DIR.resolve("system-prompt.md")),
			m.url(0), sessionId, prompt, new ClaudeProcess.Listener() {
				@Override
				public void onSession(String id) {
					if (id == null || id.equals(sessionId)) return;
					sessionId = id;
					if (sessionKey != null) ClaudeProcess.saveSession(sessionsFile, sessionKey, id);
					JsonObject s = message("session", t);
					s.addProperty("id", id);
					send(s);
				}

				@Override
				public void onText(String line) {
					JsonObject s = message("text", t);
					s.addProperty("line", line);
					send(s);
				}

				@Override
				public void onActivity(String activity) {
					JsonObject s = message("activity", t);
					if (activity != null) s.addProperty("verb", activity);
					send(s);
				}

				@Override
				public void onError(String message) {
					JsonObject s = message("text", t);
					s.addProperty("line", message);
					send(s);
				}
			});
		return switch (r.outcome()) {
			case OK, CANCELLED -> null;
			case STALE_SESSION -> {
				if (!allowRetry) yield r.error();
				sessionId = null;
				if (sessionKey != null) ClaudeProcess.saveSession(sessionsFile, sessionKey, null);
				yield runOnce(t, prompt, false);
			}
			case FAILED -> r.error();
		};
	}

	// ---------------------------------------------------------------- MCP tools (MCP threads)

	@Override
	public JsonArray listTools() {
		return toolList;
	}

	@Override
	public JsonObject call(int session, String name, JsonObject args) {
		if (name.equals("view_build")) {
			JsonObject local = viewHere(args);
			if (local != null) return local;
		}
		long id = ids.incrementAndGet();
		CompletableFuture<JsonObject> f = new CompletableFuture<>();
		toolCalls.put(id, f);
		JsonObject msg = message("tool", turn);
		msg.addProperty("id", id);
		msg.addProperty("name", name);
		msg.add("args", args);
		send(msg);
		try {
			return f.get(TOOL_TIMEOUT_MINUTES, TimeUnit.MINUTES);
		} catch (Exception e) {
			return GameTools.toolResult("Error: no answer from the game (" + e.getClass().getSimpleName() + ").", List.of(), true);
		} finally {
			toolCalls.remove(id);
		}
	}

	/**
	 * Draw view_build here if this game has the whole box loaded: same pictures, nothing to send from the host.
	 * Returns null to have the host do it instead.
	 */
	private JsonObject viewHere(JsonObject args) {
		Minecraft mc = Minecraft.getInstance();
		try {
			Level level = mc.submit(() -> mc.level != null && GameTools.canViewBuild(mc.level, args) ? mc.level : null).get(10, TimeUnit.SECONDS);
			if (level == null) return null;
			// The host's queued builds have to be placed (and reach this game) first, as when it draws them itself.
			long id = ids.incrementAndGet();
			CompletableFuture<String> idle = new CompletableFuture<>();
			idleWaits.put(id, idle);
			JsonObject msg = message("builds_idle", turn);
			msg.addProperty("id", id);
			send(msg);
			String reports;
			try {
				reports = idle.get(TOOL_TIMEOUT_MINUTES, TimeUnit.MINUTES);
			} finally {
				idleWaits.remove(id);
			}
			if (mc.level != level) return null; // changed dimension meanwhile
			JsonObject result = GameTools.viewBuildOn(level, mc, args);
			if (result != null) LOCAL_VIEWS.incrementAndGet();
			if (result != null && !reports.isEmpty()) {
				JsonArray content = result.getAsJsonArray("content");
				JsonObject text = content.get(content.size() - 1).getAsJsonObject();
				text.addProperty("text", text.get("text").getAsString() + reports);
			}
			return result;
		} catch (Exception e) {
			ClaudeCraft.LOGGER.warn("Couldn't draw view_build locally; asking the host", e);
			return null;
		}
	}

	// ---------------------------------------------------------------- helpers

	private static JsonObject message(String type, int turn) {
		JsonObject o = RemoteLink.message(type);
		o.addProperty("turn", turn);
		return o;
	}

	private void send(JsonObject msg) {
		if (closed) return;
		RemoteLink.Payload payload = new RemoteLink.Payload(msg);
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (!closed && mc.getConnection() != null) ClientPlayNetworking.send(payload);
		});
	}

	private static void tell(Component c) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) mc.player.sendSystemMessage(c);
		});
	}

	private static String playerName() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null ? mc.player.getScoreboardName() : "this player";
	}

	private void close() {
		closed = true;
		ClaudeProcess p = proc;
		if (p != null) p.cancel();
		worker.shutdownNow();
		McpServer m = mcp;
		if (m != null) m.stop();
		toolCalls.values().forEach(f -> f.cancel(false));
		idleWaits.values().forEach(f -> f.cancel(false));
	}
}
