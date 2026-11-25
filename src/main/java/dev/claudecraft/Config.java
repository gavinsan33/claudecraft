package dev.claudecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
	/** Claude's effort level (low, medium, high, xhigh, max), or empty for Claude Code's default. */
	public String effort = "";
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
	/**
	 * How many Claude sessions may work at once. Each is its own conversation: a player's follow-ups stay with the
	 * session handling them, and a new request goes to an idle session instead of waiting. 1 = one at a time.
	 */
	public int maxConcurrentSessions = 2;
	/**
	 * Hosting: let players who set useOwnClaude run Claude on their own computer, with their own account. Their
	 * Claude can then change this world through the same tools as yours, so only players on allowedPlayers may.
	 */
	public boolean allowPlayerClaude = false;
	/**
	 * Joining someone else's world: run Claude on this computer with your own account instead of the host's.
	 * The host has to allow it (allowPlayerClaude); otherwise your messages go to the host's Claude as usual.
	 */
	public boolean useOwnClaude = false;
	/** Kill a Claude turn that runs longer than this. */
	public int turnTimeoutSeconds = 900;
	/** Place blocks gradually so players can watch builds go up. false = instant. */
	public boolean animateBuilds = true;
	/** Minimum placement speed while animating (20 ticks per second). */
	public int buildBlocksPerTick = 2;
	/** Big builds speed up so no single placement call animates longer than this. */
	public int maxBuildSeconds = 12;
	/** Block sounds and particles while animating. */
	public boolean buildEffects = true;
	/** Animated status line in the action bar while Claude works. */
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

	/**
	 * Change one setting in the file, leaving the rest as they are on disk. The host's world and its player's own game
	 * share this file in singleplayer, so neither should write back settings it didn't change.
	 */
	public static synchronized void saveSetting(Path file, String key, com.google.gson.JsonElement value) {
		try {
			com.google.gson.JsonObject o = Files.exists(file)
				? com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject() : GSON.toJsonTree(new Config()).getAsJsonObject();
			o.add(key, value);
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(o));
		} catch (Exception e) {
			ClaudeCraft.LOGGER.error("Couldn't save {} to {}", key, file, e);
		}
	}

	public boolean isAllowed(String playerName) {
		return allowedPlayers.isEmpty() || allowedPlayers.stream().anyMatch(p -> p.equalsIgnoreCase(playerName));
	}
}
