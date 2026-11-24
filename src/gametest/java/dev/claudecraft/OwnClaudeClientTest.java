package dev.claudecraft;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.claudecraft.client.ClaudeCraftScreen;
import dev.claudecraft.client.OwnClaude;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A player who brings their own Claude joins a dedicated server: their request runs on "their" computer (a fake
 * claude script standing in for Claude Code), its block changes happen on the host, a view_build of a loaded area is
 * drawn by their game, and one of a far-away area comes from the host. The server and client share one JVM and so
 * one config file, which turns on both allowPlayerClaude (host) and useOwnClaude (player).
 */
public class OwnClaudeClientTest implements FabricClientGameTest {
	/** Reads the player's position from the prompt and calls the game's tools over MCP, like Claude would. */
	private static final String FAKE_CLAUDE = """
		#!/bin/bash
		LOG="$PWD/fake-claude.log"
		while [ $# -gt 0 ]; do
		  case "$1" in
		    --mcp-config) CFG="$2"; shift;;
		    --resume) echo "RESUME $2" >> "$LOG"; shift;;
		  esac
		  shift
		done
		URL=$(printf '%s' "$CFG" | sed -E 's/.*"url":"([^"]+)".*/\\1/')
		PROMPT=$(cat)
		[[ "$PROMPT" =~ at\\ (-?[0-9]+)\\ (-?[0-9]+)\\ (-?[0-9]+) ]] || echo "NOPOS $PROMPT" >> "$LOG"
		X=${BASH_REMATCH[1]}; Y=${BASH_REMATCH[2]}; Z=${BASH_REMATCH[3]}
		call() { curl -s -X POST -H 'Content-Type: application/json' -d "{\\"jsonrpc\\":\\"2.0\\",\\"id\\":1,\\"method\\":\\"tools/call\\",\\"params\\":{\\"name\\":\\"$1\\",\\"arguments\\":$2}}" "$URL"; }
		echo '{"type":"system","subtype":"init","session_id":"fake-session"}'
		echo '{"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__minecraft__fill_region"}]}}'
		echo "FILL $(call fill_region "{\\"from\\":[$((X+2)),$Y,$((Z+2))],\\"to\\":[$((X+4)),$((Y+2)),$((Z+4))],\\"block\\":\\"minecraft:stone\\"}")" >> "$LOG"
		echo "NEAR $(call view_build "{\\"from\\":[$((X+1)),$((Y-1)),$((Z+1))],\\"to\\":[$((X+5)),$((Y+3)),$((Z+5))]}")" >> "$LOG"
		echo "FAR $(call view_build "{\\"from\\":[$((X+3000)),$Y,$Z],\\"to\\":[$((X+3002)),$((Y+2)),$((Z+2))]}")" >> "$LOG"
		echo '{"type":"assistant","message":{"content":[{"type":"text","text":"Built a stone cube."}]}}'
		echo '{"type":"result","is_error":false,"result":"ok","session_id":"fake-session"}'
		echo DONE >> "$LOG"
		""";

	/** System chat the host broadcast, to see Claude's replies arrive. */
	private static final java.util.List<String> CHAT = new java.util.concurrent.CopyOnWriteArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		net.fabricmc.fabric.api.message.v1.ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
			if (!overlay) CHAT.add(message.getString());
		});
		if (System.getProperty("os.name", "").toLowerCase().contains("win")) return; // the fake claude is a bash script
		Path dir = FabricLoader.getInstance().getConfigDir().resolve(ClaudeCraft.MOD_ID);
		Path cfgFile = dir.resolve("config.json");
		Path script = dir.resolve("fake-claude.sh");
		Path log = dir.resolve("workspace").resolve("fake-claude.log");
		String original = null;
		try {
			Files.createDirectories(log.getParent());
			original = Files.exists(cfgFile) ? Files.readString(cfgFile) : null;
			Files.writeString(script, FAKE_CLAUDE);
			script.toFile().setExecutable(true);
			Files.deleteIfExists(log);
			JsonObject cfg = original != null ? JsonParser.parseString(original).getAsJsonObject() : new JsonObject();
			cfg.addProperty("claudePath", script.toString());
			cfg.addProperty("allowPlayerClaude", true);
			cfg.addProperty("useOwnClaude", true);
			cfg.addProperty("animateBuilds", false);
			Files.writeString(cfgFile, new GsonBuilder().setPrettyPrinting().create().toJson(cfg));

			try (TestDedicatedServerContext server = ctx.worldBuilder().createServer();
					TestDedicatedServerConnection conn = server.connect()) {
				conn.waitForChunksRender();
				ctx.waitFor(mc -> OwnClaude.isReady(), 200);
				BlockPos at = server.computeOnServer(s -> conn.getServerPlayer().blockPosition());
				int viewsBefore = OwnClaude.localViews();
				ctx.runOnClient(mc -> mc.player.connection.sendCommand("claude ask build a little stone cube"));
				ctx.waitFor(mc -> read(log).contains("DONE"), 1200);
				String out = read(log);

				check(!out.contains("NOPOS"), "the prompt carries the player's position: " + out);
				String fill = line(out, "FILL"), near = line(out, "NEAR"), far = line(out, "FAR");
				check(fill.contains("\"isError\":false"), "fill_region ran on the host: " + fill);
				check(near.contains("\"type\":\"image\"") && near.contains("\"isError\":false"), "the nearby view has a picture: " + abbreviate(near));
				check(far.contains("\"type\":\"image\"") && far.contains("\"isError\":false"), "the far view has a picture: " + abbreviate(far));
				check(OwnClaude.localViews() == viewsBefore + 1, "only the nearby view was drawn on the player's computer: "
					+ (OwnClaude.localViews() - viewsBefore));
				check(server.computeOnServer(s -> s.overworld().getBlockState(at.offset(3, 1, 3)).is(Blocks.STONE)), "the cube is in the host's world");

				// The reply reaches everyone's chat under the player's name, and the turn finishes.
				String name = ctx.computeOnClient(mc -> mc.player.getScoreboardName());
				server.waitFor(s -> CHAT.contains("[" + name + "'s Claude] Built a stone cube."), 200);
				server.waitFor(s -> ClaudeCraft.bridge().status().contains(name + "'s own Claude: idle, 0 queued, session fake-session"), 200);
				check(CHAT.stream().noneMatch(m -> m.contains("Stopped") || m.contains("error")), "no errors: " + CHAT);

				// The menu: a friend can't change the world's settings, but can switch their own Claude off and on.
				String own = name + "'s own Claude";
				openMenu(ctx);
				check(!ctx.computeOnClient(mc -> button(mc, "Claude sessions at once").active), "a friend can't change the host's settings");
				check(ClaudeCraftMenuClientTest.press(ctx, "Use my own Claude: ON"), "their own Claude shows as on: " + widgets(ctx));
				server.waitFor(s -> !ClaudeCraft.bridge().status().contains(own), 100);
				ctx.waitFor(mc -> !OwnClaude.isReady(), 100);
				check(ClaudeCraftMenuClientTest.press(ctx, "Use my own Claude: OFF"), "and now off");
				ctx.waitFor(mc -> OwnClaude.isReady(), 200);
				server.waitFor(s -> ClaudeCraft.bridge().status().contains(own), 100);

				// An operator turns friends' own Claude off for everyone, and the friend's game lets go of it.
				server.runCommand("op " + name);
				openMenu(ctx);
				check(ctx.computeOnClient(mc -> button(mc, "Friends can use their own Claude").active), "an operator can change the host's settings");
				check(ClaudeCraftMenuClientTest.press(ctx, "Friends can use their own Claude: ON"), "friends' own Claude shows as allowed");
				server.waitFor(s -> !ClaudeCraft.bridge().status().contains(own), 100);
				ctx.waitFor(mc -> !OwnClaude.isReady(), 100);
				ctx.waitFor(mc -> button(mc, "Friends can use their own Claude").getMessage().getString().endsWith("OFF"), 100);
				// Allowing it again invites them back without rejoining.
				check(ClaudeCraftMenuClientTest.press(ctx, "Friends can use their own Claude: OFF"), "and now not allowed");
				ctx.waitFor(mc -> OwnClaude.isReady(), 200);
				server.waitFor(s -> ClaudeCraft.bridge().status().contains(own), 100);
				ctx.setScreen(() -> null);
			}
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		} finally {
			try {
				if (original != null) Files.writeString(cfgFile, original);
				Files.deleteIfExists(script);
			} catch (java.io.IOException ignored) {}
		}
	}

	private static String widgets(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			StringBuilder sb = new StringBuilder();
			for (var c : mc.gui.screen().children()) {
				if (c instanceof net.minecraft.client.gui.components.AbstractWidget w) sb.append("[").append(w.getMessage().getString()).append(w.active ? "" : " (inactive)").append("] ");
			}
			return sb.toString();
		});
	}

	private static void openMenu(ClientGameTestContext ctx) {
		ctx.setScreen(() -> new ClaudeCraftScreen(null));
		ctx.waitFor(mc -> ClaudeCraftScreen.loaded(), 100);
		ctx.waitTick();
	}

	private static net.minecraft.client.gui.components.AbstractWidget button(net.minecraft.client.Minecraft mc, String label) {
		for (var child : mc.gui.screen().children()) {
			if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getMessage().getString().startsWith(label)) return w;
		}
		throw new AssertionError("No " + label + " button");
	}

	private static String read(Path p) {
		try {
			return Files.exists(p) ? Files.readString(p) : "";
		} catch (java.io.IOException e) {
			return "";
		}
	}

	private static String line(String out, String prefix) {
		for (String l : out.split("\n")) if (l.startsWith(prefix + " ")) return l;
		return "(missing " + prefix + ")";
	}

	private static String abbreviate(String s) {
		return s.replaceAll("\"data\":\"[^\"]{40,}\"", "\"data\":\"…\"");
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
	}
}
