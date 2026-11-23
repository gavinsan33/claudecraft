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
	private volatile boolean running;
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
		if (p != null && busy) {
			p.sendSystemMessage(Component.literal("Claude is busy; your message will be handled next.").withStyle(ChatFormatting.GRAY));
		}
	}

	// ---------------------------------------------------------------- status line (server thread)

	/** Claude's orange, and the lighter shade that sweeps across the verb, as in Claude Code's spinner. */
	private static final int ORANGE = 0xD77757, SHIMMER = 0xF5C1A9;
	private static final String[] SPINNER = {"·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢"};
	private static final String[] THINKING = {"Thinking", "Pondering", "Scheming", "Tinkering", "Imagining", "Sketching", "Crafting", "Mulling"};

	/** What Claude is doing right now, shown in the status line. */
	private volatile String activity = THINKING[0];
	private volatile String thinkingVerb = THINKING[0];
	private long activeSince = -1, statusTicks;

	/**
	 * Keep an animated status in every player's action bar while Claude is working or blocks are still going up,
	 * like Claude Code's spinner: "✻ Pondering… (12s · 340 blocks to go)". Action bar text fades on its own,
	 * so this re-sends it every couple of ticks.
	 */
	public void tickStatus(BuildQueue builds) {
		if (!cfg.showToolActivity) return;
		boolean building = builds.isBusy();
		long now = System.currentTimeMillis();
		if (!running && !building) {
			if (activeSince >= 0) {
				String took = elapsed(now - activeSince);
				activeSince = -1;
				sendStatus(Component.literal("✻ ").withColor(ORANGE)
					.append(Component.literal("Worked for " + took).withStyle(ChatFormatting.GRAY)));
			}
			return;
		}
		if (activeSince < 0) activeSince = now;
		if (statusTicks++ % 2 != 0) return;

		long frame = statusTicks / 2;
		// While blocks are going up and Claude is only thinking, the build is what players see.
		String verb = (building && (!running || activity.equals(thinkingVerb)) ? "Building" : activity) + "…";
		MutableComponent line = Component.literal(SPINNER[(int) (frame % SPINNER.length)] + " ").withColor(ORANGE);
		int sweep = (int) (frame % (verb.length() + 8)) - 4;
		for (int i = 0; i < verb.length(); i++) {
			line.append(Component.literal(String.valueOf(verb.charAt(i))).withColor(Math.abs(i - sweep) <= 1 ? SHIMMER : ORANGE));
		}
		StringBuilder info = new StringBuilder(" (").append(elapsed(now - activeSince));
		if (building) info.append(" · ").append(String.format(java.util.Locale.ROOT, "%,d", builds.blocksLeft())).append(" blocks to go");
		int queued;
		synchronized (queue) {
			queued = queue.size();
		}
		if (queued > 0) info.append(" · ").append(queued).append(" message").append(queued == 1 ? "" : "s").append(" queued");
		line.append(Component.literal(info.append(')').toString()).withStyle(ChatFormatting.GRAY));
		sendStatus(line);
	}

	private void sendStatus(Component c) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(c, true);
	}

	private static String elapsed(long ms) {
		long s = ms / 1000;
		return s < 60 ? s + "s" : s / 60 + "m " + s % 60 + "s";
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
			thinkingVerb = THINKING[java.util.concurrent.ThreadLocalRandom.current().nextInt(THINKING.length)];
			activity = thinkingVerb;
			try {
				runTurn(batch, true);
			} catch (Exception e) {
				ClaudeCraft.LOGGER.error("Claude turn failed", e);
				broadcastError("Claude failed: " + e.getMessage());
			} finally {
				current = null;
				// Builds keep animating after Claude's reply; they still belong to this turn's undo step.
				try {
					tools.buildsIdle().get(30, TimeUnit.MINUTES);
				} catch (Exception e) {
					ClaudeCraft.LOGGER.warn("Gave up waiting for builds to finish", e);
				}
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
					case "assistant" -> handleAssistant(ev);
					case "user" -> activity = thinkingVerb; // tool results came back; Claude is thinking again
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

	private void handleAssistant(JsonObject ev) {
		JsonObject message = ev.has("message") ? ev.getAsJsonObject("message") : null;
		if (message == null || !message.has("content")) return;
		for (JsonElement el : message.getAsJsonArray("content")) {
			JsonObject block = el.getAsJsonObject();
			String btype = str(block, "type");
			if ("text".equals(btype)) {
				activity = thinkingVerb;
				String text = cleanMarkdown(str(block, "text"));
				if (text.isBlank() || text.strip().equals("NO_REPLY")) continue;
				for (String line : text.split("\n")) {
					if (line.isBlank()) continue;
					MutableComponent c = Component.literal("[Claude] ").withStyle(ChatFormatting.GOLD)
						.append(Component.literal(line.strip()).withStyle(ChatFormatting.WHITE));
					broadcast(c);
				}
			} else if ("tool_use".equals(btype)) {
				String name = str(block, "name");
				if (name != null) name = name.replaceFirst("^mcp__minecraft__", "");
				activity = switch (name == null ? "" : name) {
					case "get_context" -> "Looking around";
					case "get_terrain", "scan_area" -> "Surveying";
					case "view_build" -> "Inspecting";
					case "run_commands" -> "Running commands";
					case "undo" -> "Undoing";
					case "wait_for_builds" -> "Building";
					default -> thinkingVerb; // placement tools return at once; the build shows in the status anyway
				};
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
