package dev.claudecraft;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Conversations belong to the world, not its name: a world adopts its old name-keyed conversations once, and a second
 * world with the same name ("New World") starts its own instead of sharing them.
 */
public class WorldSessionsClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try {
			Path shared = FabricLoader.getInstance().getConfigDir().resolve(ClaudeCraft.MOD_ID).resolve("sessions.json");
			Files.createDirectories(shared.getParent());
			Files.writeString(shared, "{\"New World\":\"old-convo-1\",\"New World#2\":\"old-convo-2\",\"Other\":\"keep\"}");
			String firstId;
			try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
				sp.getConnection().waitForChunksRender();
				var mine = JsonParser.parseString(Files.readString(sessions(sp))).getAsJsonObject();
				check("New World".equals(sp.getServer().computeOnServer(s -> s.getWorldData().getLevelName())), "test worlds are called New World");
				check(mine.has("world_id"), "the world gets an id: " + mine);
				check("old-convo-1".equals(mine.get("1").getAsString()) && "old-convo-2".equals(mine.get("2").getAsString()), "it adopts its old conversations: " + mine);
				check(sp.getServer().computeOnServer(s -> ClaudeCraft.bridge().status()).contains("old-convo-1"), "and resumes them");
				var left = JsonParser.parseString(Files.readString(shared)).getAsJsonObject();
				check(!left.has("New World") && !left.has("New World#2") && left.has("Other"), "they leave the shared file, other worlds' stay: " + left);
				firstId = mine.get("world_id").getAsString();
			}
			try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
				sp.getConnection().waitForChunksRender();
				var mine = JsonParser.parseString(Files.readString(sessions(sp))).getAsJsonObject();
				check(!mine.has("1") && !mine.has("2"), "a second New World starts its own conversations: " + mine);
				check(!mine.get("world_id").getAsString().equals(firstId), "and has its own id");
				check(!sp.getServer().computeOnServer(s -> ClaudeCraft.bridge().status()).contains("old-convo"), "it doesn't resume the first world's");
			}
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static Path sessions(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(s -> s.getWorldPath(LevelResource.ROOT).resolve("claudecraft").resolve("sessions.json"));
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
	}
}
