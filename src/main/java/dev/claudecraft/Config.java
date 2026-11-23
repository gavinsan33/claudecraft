package dev.claudecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** User-editable settings, stored at config/claudecraft/config.json. */
public class Config {
	/** Path to the claude executable. Empty = auto-detect (PATH, ~/.local/bin, ~/.claude/local). */
	public String claudePath = "";
	/** Model alias or id passed to --model. Empty = Claude Code's default. */
	public String model = "";
	/** Only chat messages starting with this are sent to Claude (prefix is stripped). Empty = every message. */
	public String triggerPrefix = "";
	/** Permission level for commands Claude runs: 0 all, 1 moderators, 2 gamemasters, 3 admins, 4 owners. */
	public int commandPermissionLevel = 2;
	/** Player names allowed to talk to Claude. Empty = everyone. */
	public List<String> allowedPlayers = new ArrayList<>();
	/** Max blocks a single placement/fill tool call may change. */
	public int maxBlocksPerCall = 100_000;
	/** Max volume scan_area may read in one call. */
	public int maxScanVolume = 32_768;
	/** Port for the local MCP endpoint. 0 = pick a free port. */
	public int mcpPort = 0;
	/** Kill a Claude turn that runs longer than this. */
	public int turnTimeoutSeconds = 900;
	/** Show what Claude is doing (tool calls) in the action bar. */
	public boolean showToolActivity = true;
	/** Extra CLI args appended to every claude invocation, e.g. ["--effort", "high"]. */
	public List<String> extraArgs = new ArrayList<>();

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public static Config load(Path file) {
		Config cfg = new Config();
		try {
			if (Files.exists(file)) {
				Config read = GSON.fromJson(Files.readString(file), Config.class);
				if (read != null) cfg = read;
			}
			// Rewrite so newly added options show up in existing files.
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(cfg));
		} catch (Exception e) {
			ClaudeCraft.LOGGER.error("Failed to load {}; using defaults", file, e);
		}
		if (cfg.allowedPlayers == null) cfg.allowedPlayers = new ArrayList<>();
		if (cfg.extraArgs == null) cfg.extraArgs = new ArrayList<>();
		if (cfg.triggerPrefix == null) cfg.triggerPrefix = "";
		return cfg;
	}

	public boolean isAllowed(String playerName) {
		return allowedPlayers.isEmpty() || allowedPlayers.stream().anyMatch(p -> p.equalsIgnoreCase(playerName));
	}

	static void writeIfMissing(Path file, String content) throws IOException {
		if (!Files.exists(file)) {
			Files.createDirectories(file.getParent());
			Files.writeString(file, content);
		}
	}
}
