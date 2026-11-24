package dev.claudecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

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
import java.util.concurrent.TimeUnit;

/**
 * One headless Claude Code turn (claude -p) connected to a Minecraft MCP endpoint. Used by the host's sessions and,
 * for players who bring their own Claude, by their game client.
 */
public final class ClaudeProcess {
	public interface Listener {
		void onSession(String id);
		/** One line of Claude's reply for chat, already cleaned up. */
		void onText(String line);
		/** A tool Claude started, as a status verb ("Surveying"), or null when it's thinking again. */
		void onActivity(String activity);
		/** Something to tell players right away, e.g. a timeout. */
		void onError(String message);
	}

	public enum Outcome { OK, CANCELLED, STALE_SESSION, FAILED }

	public record Result(Outcome outcome, String error) {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private volatile Process proc;
	private volatile boolean cancelled;

	public void cancel() {
		cancelled = true;
		Process p = proc;
		if (p != null) kill(p);
	}

	/**
	 * Run one turn to completion on the calling thread.
	 * @param sessionId conversation to resume, or null for a fresh one
	 */
	public Result run(Config cfg, Path workDir, String systemPrompt, String mcpUrl, String sessionId, String prompt, Listener l)
			throws IOException, InterruptedException {
		String claude = resolveClaude(cfg);
		if (claude == null) {
			return new Result(Outcome.FAILED, "Can't find the claude executable. Set claudePath in config/claudecraft/config.json.");
		}
		JsonObject server = new JsonObject();
		server.addProperty("type", "http");
		server.addProperty("url", mcpUrl);
		JsonObject servers = new JsonObject();
		servers.add("minecraft", server);
		JsonObject mcpConfig = new JsonObject();
		mcpConfig.add("mcpServers", servers);

		List<String> cmd = new ArrayList<>(List.of(
			claude, "-p",
			"--output-format", "stream-json", "--verbose",
			"--strict-mcp-config", "--mcp-config", mcpConfig.toString(),
			"--tools", "",
			"--allowedTools", "mcp__minecraft",
			"--permission-mode", "dontAsk",
			"--append-system-prompt", systemPrompt));
		if (sessionId != null) {
			cmd.add("--resume");
			cmd.add(sessionId);
		}
		if (!cfg.model.isBlank()) {
			cmd.add("--model");
			cmd.add(cfg.model);
		}
		cmd.addAll(cfg.extraArgs);

		Files.createDirectories(workDir);
		ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile());
		// If Minecraft itself was started from inside a Claude Code session, don't confuse the child.
		pb.environment().remove("CLAUDECODE");
		pb.environment().remove("CLAUDE_CODE_ENTRYPOINT");
		Process p = pb.start();
		proc = p;
		if (cancelled) kill(p);

		try (OutputStream os = p.getOutputStream()) {
			os.write(prompt.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			if (!cancelled) throw e;
		}

		StringBuilder stderr = new StringBuilder();
		Thread errReader = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
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
				if (!p.waitFor(cfg.turnTimeoutSeconds, TimeUnit.SECONDS)) {
					l.onError("Claude took longer than " + cfg.turnTimeoutSeconds + "s; stopping it.");
					kill(p);
				}
			} catch (InterruptedException ignored) {}
		}, "ClaudeCraft-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();

		boolean gotResult = false;
		boolean resultError = false;
		String resultText = null;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
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
				if ("system".equals(type) && str(ev, "session_id") != null) l.onSession(str(ev, "session_id"));
				switch (type == null ? "" : type) {
					case "assistant" -> handleAssistant(ev, l);
					case "user" -> l.onActivity(null); // tool results came back; Claude is thinking again
					case "result" -> {
						gotResult = true;
						resultError = ev.has("is_error") && ev.get("is_error").getAsBoolean();
						resultText = str(ev, "result");
						if (str(ev, "session_id") != null) l.onSession(str(ev, "session_id"));
					}
					default -> {}
				}
			}
		}
		int exit = p.waitFor();
		watchdog.interrupt();
		errReader.join(2000);

		if (cancelled) return new Result(Outcome.CANCELLED, null);
		String err;
		synchronized (stderr) {
			err = stderr.toString().trim();
		}
		if (!gotResult || exit != 0 || resultError) {
			String detail = resultText != null && !resultText.isBlank() ? resultText : err;
			ClaudeCraft.LOGGER.warn("claude exited with {} (result error: {}): {}", exit, resultError, detail);
			// A stale session id (deleted transcript, different machine) shouldn't brick the bridge.
			if (sessionId != null && detail != null && detail.toLowerCase().contains("session")) {
				return new Result(Outcome.STALE_SESSION, detail);
			}
			return new Result(Outcome.FAILED, detail == null || detail.isBlank() ? "exit code " + exit : firstLines(detail, 3));
		}
		return new Result(Outcome.OK, null);
	}

	private static void handleAssistant(JsonObject ev, Listener l) {
		JsonObject message = ev.has("message") ? ev.getAsJsonObject("message") : null;
		if (message == null || !message.has("content")) return;
		for (JsonElement el : message.getAsJsonArray("content")) {
			JsonObject block = el.getAsJsonObject();
			String btype = str(block, "type");
			if ("text".equals(btype)) {
				l.onActivity(null);
				String text = cleanMarkdown(str(block, "text"));
				if (text.isBlank() || text.strip().equals("NO_REPLY")) continue;
				for (String line : text.split("\n")) if (!line.isBlank()) l.onText(line.strip());
			} else if ("tool_use".equals(btype)) {
				String name = str(block, "name");
				if (name != null) name = name.replaceFirst("^mcp__minecraft__", "");
				l.onActivity(switch (name == null ? "" : name) {
					case "get_context" -> "Looking around";
					case "get_terrain", "scan_area" -> "Surveying";
					case "view_build" -> "Inspecting";
					case "run_commands" -> "Running commands";
					case "undo" -> "Undoing";
					case "wait_for_builds" -> "Building";
					default -> null; // placement tools return at once; the build shows in the status anyway
				});
			}
		}
	}

	// ---------------------------------------------------------------- helpers

	public static String readSystemPrompt(Path promptFile) {
		try {
			return Files.readString(promptFile);
		} catch (IOException e) {
			return "";
		}
	}

	static String resolveClaude(Config cfg) {
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

	/** Saved conversation ids (sessions.json), by world. */
	public static Map<String, String> loadSessions(Path file) {
		try {
			if (Files.exists(file)) {
				Map<String, String> m = GSON.fromJson(Files.readString(file), new TypeToken<Map<String, String>>() {}.getType());
				if (m != null) return m;
			}
		} catch (Exception e) {
			ClaudeCraft.LOGGER.warn("Couldn't read {}", file, e);
		}
		return new HashMap<>();
	}

	public static synchronized void saveSession(Path file, String key, String id) {
		Map<String, String> m = loadSessions(file);
		if (id == null) m.remove(key);
		else m.put(key, id);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(m));
		} catch (IOException e) {
			ClaudeCraft.LOGGER.warn("Couldn't write {}", file, e);
		}
	}

	private static void kill(Process p) {
		p.descendants().forEach(ProcessHandle::destroyForcibly);
		p.destroyForcibly();
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
