package dev.claudecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs Claude Code headlessly (claude -p) for each batch of chat messages, resuming one shared
 * conversation per world, and relays Claude's replies into chat.
 */
public class ClaudeBridge {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** A chat message waiting for Claude, with a snapshot of where the sender was. */
	public record Pending(String player, String text, String where) {}

	private final MinecraftServer server;
	private final Config cfg;
	private final GameTools tools;
	private final McpServer mcp;
	private final Path workDir;
	private final Path promptFile;
	private final Path sessionsFile;
	private final String worldName;

	private final List<Pending> queue = new ArrayList<>();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "ClaudeCraft-Bridge");
		t.setDaemon(true);
		return t;
	});
	private boolean running;
	private volatile Process current;
	private volatile boolean cancelled;
	private volatile String sessionId;

	public ClaudeBridge(MinecraftServer server, Config cfg, GameTools tools, McpServer mcp, Path configDir) {
		this.server = server;
		this.cfg = cfg;
		this.tools = tools;
		this.mcp = mcp;
		this.workDir = configDir.resolve("workspace");
		this.promptFile = configDir.resolve("system-prompt.md");
		this.sessionsFile = configDir.resolve("sessions.json");
		this.worldName = server.getWorldData().getLevelName();
		try {
			Files.createDirectories(workDir);
		} catch (IOException e) {
			ClaudeCraft.LOGGER.error("Cannot create {}", workDir, e);
		}
		this.sessionId = loadSessions().get(worldName);
	}

	// ---------------------------------------------------------------- public API (called on the server thread)

	public void submit(Pending msg) {
		boolean busy;
		synchronized (queue) {
			queue.add(msg);
			busy = running;
			if (!running) {
				running = true;
				worker.execute(this::drain);
			}
		}
		ServerPlayer p = server.getPlayerList().getPlayerByName(msg.player());
		if (p != null) {
			p.sendSystemMessage(Component.literal(busy ? "Claude is busy; your message will be handled next." : "Claude is thinking...")
				.withStyle(ChatFormatting.GRAY), true);
		}
	}

	/** Stop the current turn and drop queued messages. */
	public boolean stop() {
		boolean had;
		synchronized (queue) {
			had = running;
			queue.clear();
		}
		cancelled = true;
		Process p = current;
		if (p != null) kill(p);
		return had;
	}

	/** Forget the conversation so the next message starts a fresh session. */
	public void reset() {
		stop();
		sessionId = null;
		saveSession(null);
	}

	public String status() {
		synchronized (queue) {
			return (running ? "working" : "idle") + ", " + queue.size() + " queued, session "
				+ (sessionId == null ? "none (fresh)" : sessionId) + ", " + tools.undoHistory().depth() + " undo step(s)";
		}
	}

	public void shutdown() {
		stop();
		worker.shutdownNow();
	}

	// ---------------------------------------------------------------- worker

	private void drain() {
		while (true) {
			List<Pending> batch;
			synchronized (queue) {
				if (queue.isEmpty()) {
					running = false;
					return;
				}
				batch = new ArrayList<>(queue);
				queue.clear();
			}
			cancelled = false;
			try {
				runTurn(batch, true);
			} catch (Exception e) {
				ClaudeCraft.LOGGER.error("Claude turn failed", e);
				broadcastError("Claude failed: " + e.getMessage());
			} finally {
				current = null;
				server.execute(() -> tools.undoHistory().commit());
			}
		}
	}

	private void runTurn(List<Pending> batch, boolean allowRetry) throws Exception {
		String requester = batch.getLast().player();
		tools.setDefaultPlayer(requester);

		StringBuilder prompt = new StringBuilder("[Minecraft chat]\n");
		for (Pending m : batch) {
			prompt.append('<').append(m.player()).append('>');
			if (m.where() != null) prompt.append(" (").append(m.where()).append(')');
			prompt.append(": ").append(m.text()).append('\n');
		}

		String claude = resolveClaude();
		if (claude == null) {
			broadcastError("Can't find the claude executable. Set claudePath in config/claudecraft/config.json.");
			return;
		}

		List<String> cmd = new ArrayList<>(List.of(
			claude, "-p",
			"--output-format", "stream-json", "--verbose",
			"--strict-mcp-config", "--mcp-config", mcpConfigJson(),
			"--tools", "",
			"--allowedTools", "mcp__minecraft",
			"--permission-mode", "dontAsk",
			"--append-system-prompt", readSystemPrompt()));
		if (sessionId != null) {
			cmd.add("--resume");
			cmd.add(sessionId);
		}
		if (!cfg.model.isBlank()) {
			cmd.add("--model");
			cmd.add(cfg.model);
		}
		cmd.addAll(cfg.extraArgs);

		ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile());
		// If Minecraft itself was started from inside a Claude Code session, don't confuse the child.
		pb.environment().remove("CLAUDECODE");
		pb.environment().remove("CLAUDE_CODE_ENTRYPOINT");
		Process proc = pb.start();
		current = proc;
		if (cancelled) kill(proc);

		try (OutputStream os = proc.getOutputStream()) {
			os.write(prompt.toString().getBytes(StandardCharsets.UTF_8));
		}

		StringBuilder stderr = new StringBuilder();
		Thread errReader = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					synchronized (stderr) {
						if (stderr.length() < 8000) stderr.append(line).append('\n');
					}
				}
			} catch (IOException ignored) {}
		}, "ClaudeCraft-stderr");
		errReader.setDaemon(true);
		errReader.start();

		Thread watchdog = new Thread(() -> {
			try {
				if (!proc.waitFor(cfg.turnTimeoutSeconds, TimeUnit.SECONDS)) {
					broadcastError("Claude took longer than " + cfg.turnTimeoutSeconds + "s; stopping it.");
					kill(proc);
				}
			} catch (InterruptedException ignored) {}
		}, "ClaudeCraft-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();

		boolean gotResult = false;
		boolean resultError = false;
		String resultText = null;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank()) continue;
				JsonObject ev;
				try {
					ev = JsonParser.parseString(line).getAsJsonObject();
				} catch (Exception e) {
					continue;
				}
				String type = str(ev, "type");
				if ("system".equals(type) && str(ev, "session_id") != null) updateSession(str(ev, "session_id"));
				switch (type == null ? "" : type) {
					case "assistant" -> handleAssistant(ev, requester);
					case "result" -> {
						gotResult = true;
						resultError = ev.has("is_error") && ev.get("is_error").getAsBoolean();
						resultText = str(ev, "result");
						if (str(ev, "session_id") != null) updateSession(str(ev, "session_id"));
					}
					default -> {}
				}
			}
		}
		int exit = proc.waitFor();
		watchdog.interrupt();
		errReader.join(2000);

		if (cancelled) {
			broadcast(Component.literal("Stopped.").withStyle(ChatFormatting.GRAY));
			return;
		}
		String err;
		synchronized (stderr) {
			err = stderr.toString().trim();
		}
		if (!gotResult || exit != 0 || resultError) {
			String detail = resultText != null && !resultText.isBlank() ? resultText : err;
			ClaudeCraft.LOGGER.warn("claude exited with {} (result error: {}): {}", exit, resultError, detail);
			// A stale session id (deleted transcript, different machine) shouldn't brick the bridge.
			if (allowRetry && sessionId != null && detail != null && detail.toLowerCase().contains("session")) {
				ClaudeCraft.LOGGER.info("Retrying with a fresh session");
				sessionId = null;
				saveSession(null);
				runTurn(batch, false);
				return;
			}
			broadcastError("Claude error: " + (detail == null || detail.isBlank() ? "exit code " + exit : firstLines(detail, 3)));
		}
	}

	private void handleAssistant(JsonObject ev, String requester) {
		JsonObject message = ev.has("message") ? ev.getAsJsonObject("message") : null;
		if (message == null || !message.has("content")) return;
		for (JsonElement el : message.getAsJsonArray("content")) {
			JsonObject block = el.getAsJsonObject();
			String btype = str(block, "type");
			if ("text".equals(btype)) {
				String text = cleanMarkdown(str(block, "text"));
				if (text.isBlank() || text.strip().equals("NO_REPLY")) continue;
				for (String line : text.split("\n")) {
					if (line.isBlank()) continue;
					MutableComponent c = Component.literal("[Claude] ").withStyle(ChatFormatting.GOLD)
						.append(Component.literal(line.strip()).withStyle(ChatFormatting.WHITE));
					broadcast(c);
				}
			} else if ("tool_use".equals(btype) && cfg.showToolActivity) {
				String name = str(block, "name");
				if (name != null) name = name.replaceFirst("^mcp__minecraft__", "");
				String label = switch (name == null ? "" : name) {
					case "get_context" -> "looking around";
					case "get_terrain", "scan_area" -> "surveying the area";
					case "place_blueprint", "fill_region", "place_blocks" -> "building";
					case "run_commands" -> "running commands";
					case "undo" -> "undoing";
					default -> name;
				};
				String finalLabel = label;
				server.execute(() -> {
					ServerPlayer p = server.getPlayerList().getPlayerByName(requester);
					if (p != null) p.sendSystemMessage(Component.literal("Claude is " + finalLabel + "...").withStyle(ChatFormatting.GRAY), true);
				});
			}
		}
	}

	// ---------------------------------------------------------------- helpers

	private String mcpConfigJson() {
		JsonObject server = new JsonObject();
		server.addProperty("type", "http");
		server.addProperty("url", mcp.url());
		JsonObject servers = new JsonObject();
		servers.add("minecraft", server);
		JsonObject root = new JsonObject();
		root.add("mcpServers", servers);
		return root.toString();
	}

	private String readSystemPrompt() {
		try {
			return Files.readString(promptFile);
		} catch (IOException e) {
			return "";
		}
	}

	private String resolveClaude() {
		if (!cfg.claudePath.isBlank()) return cfg.claudePath;
		boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
		List<String> names = windows ? List.of("claude.exe", "claude.cmd", "claude") : List.of("claude");
		List<Path> dirs = new ArrayList<>();
		String path = System.getenv("PATH");
		if (path != null) for (String d : path.split(File.pathSeparator)) if (!d.isBlank()) dirs.add(Path.of(d));
		String home = System.getProperty("user.home");
		dirs.add(Path.of(home, ".local", "bin"));
		dirs.add(Path.of(home, ".claude", "local"));
		dirs.add(Path.of(home, ".npm-global", "bin"));
		dirs.add(Path.of("/usr/local/bin"));
		dirs.add(Path.of("/opt/homebrew/bin"));
		for (Path d : dirs) {
			for (String n : names) {
				Path c = d.resolve(n);
				if (Files.isExecutable(c)) return c.toString();
			}
		}
		return null;
	}

	private void updateSession(String id) {
		if (id != null && !id.equals(sessionId)) {
			sessionId = id;
			saveSession(id);
		}
	}

	private Map<String, String> loadSessions() {
		try {
			if (Files.exists(sessionsFile)) {
				Map<String, String> m = GSON.fromJson(Files.readString(sessionsFile), new TypeToken<Map<String, String>>() {}.getType());
				if (m != null) return m;
			}
		} catch (Exception e) {
			ClaudeCraft.LOGGER.warn("Couldn't read {}", sessionsFile, e);
		}
		return new HashMap<>();
	}

	private synchronized void saveSession(String id) {
		Map<String, String> m = loadSessions();
		if (id == null) m.remove(worldName);
		else m.put(worldName, id);
		try {
			Files.writeString(sessionsFile, GSON.toJson(m));
		} catch (IOException e) {
			ClaudeCraft.LOGGER.warn("Couldn't write {}", sessionsFile, e);
		}
	}

	private static void kill(Process p) {
		p.descendants().forEach(ProcessHandle::destroyForcibly);
		p.destroyForcibly();
	}

	private void broadcast(Component c) {
		server.execute(() -> server.getPlayerList().broadcastSystemMessage(c, false));
		ClaudeCraft.LOGGER.info("[chat] {}", c.getString());
	}

	private void broadcastError(String msg) {
		broadcast(Component.literal("[Claude] ").withStyle(ChatFormatting.GOLD)
			.append(Component.literal(msg).withStyle(ChatFormatting.RED)));
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static String firstLines(String s, int n) {
		String[] lines = s.strip().split("\n");
		return String.join(" ", java.util.Arrays.copyOf(lines, Math.min(n, lines.length)));
	}

	/** Minecraft chat doesn't render markdown; strip the most common syntax. */
	static String cleanMarkdown(String s) {
		if (s == null) return "";
		return s.replaceAll("\\*\\*(.+?)\\*\\*", "$1")
			.replaceAll("(?m)^#{1,6}\\s*", "")
			.replaceAll("(?m)^\\s*[-*]\\s+", "- ")
			.replace("`", "")
			.replace("§", "");
	}

}
