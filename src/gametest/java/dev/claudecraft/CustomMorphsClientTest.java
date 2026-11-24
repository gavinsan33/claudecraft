package dev.claudecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Morphs the player (seen from the front in third person) with a giant belly, then into a spider, and screenshots both. */
public class CustomMorphsClientTest implements FabricClientGameTest {
	private static final String BELLY = """
		{"id":"big_belly","name":"Big Belly","parts":[
		 {"skin":"body","center":[0,14,4],"scale":[1.4,0.75,3],"attach":"body"}]}""";

	/** Legs splay out from the body's sides, alternating phases so it scuttles. */
	private static String spider() {
		StringBuilder legs = new StringBuilder();
		for (int k = 0; k < 4; k++) {
			for (int side : new int[]{-1, 1}) {
				float z = 2 - k * 3.5f;
				legs.append(String.format(java.util.Locale.ROOT,
					",{\"skin\":\"%s\",\"pivot\":[%d,8,%.1f],\"center\":[%d,0.8,%.1f],\"scale\":[0.5,1.2,0.5],\"rotation\":[0,%d,%d],\"role\":\"leg\",\"phase\":%d}",
					side < 0 ? "right_arm" : "left_arm", side * 4, z, side * 4, z, (k - 1) * 15 * side, side * 65, (k + (side > 0 ? 1 : 0)) % 2));
			}
		}
		return """
			{"id":"spider","name":"Spider","hide":"all","palette":{"r":"#E01010"},"parts":[
			 {"skin":"body","center":[0,8,-5],"pivot":[0,8,-5],"scale":[1.5,0.9,2.4],"rotation":[90,0,0]},
			 {"skin":"head","center":[0,8,4],"pivot":[0,8,1],"scale":[1,0.85,1],"role":"head"},
			 {"layers":[["r.r"]],"origin":[-2,9,8],"pivot":[0,8,1],"role":"head"}%s]}""".formatted(legs);
	}

	private static void define(net.minecraft.server.MinecraftServer server, String json) {
		JsonObject def = JsonParser.parseString(json).getAsJsonObject();
		def.addProperty("kind", "morph");
		String bad = Morphs.validate(def);
		if (bad != null) throw new AssertionError(bad);
		CustomItems.define(server, def);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(server -> {
				define(server, BELLY);
				define(server, spider());
				var src = server.createCommandSourceStack();
				int y = server.overworld().getChunk(0, 0).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0) + 1;
				server.getCommands().performPrefixedCommand(src, "tp @a 0.5 " + y + " 0.5 200 10");
				server.getCommands().performPrefixedCommand(src, "time set noon");
				server.getCommands().performPrefixedCommand(src, "claude morph big_belly @a");
			});
			ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
			ctx.waitTicks(2);
			// Face the body the way the head (and the camera) faces; a standing player's body otherwise lags behind.
			ctx.runOnClient(mc -> mc.player.yBodyRot = mc.player.yBodyRotO = mc.player.getYRot());
			ctx.waitTicks(20);
			ctx.takeScreenshot("claudecraft-morph-belly");
			sp.getServer().runOnServer(server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "claude morph spider @a"));
			ctx.runOnClient(mc -> mc.player.yBodyRot = mc.player.yBodyRotO = mc.player.getYRot());
			ctx.waitTicks(20);
			ctx.takeScreenshot("claudecraft-morph-spider");
			boolean morphed = sp.getServer().computeOnServer(server -> "spider".equals(server.getPlayerList().getPlayers().getFirst().getAttached(Morphs.MORPH)));
			if (!morphed) throw new AssertionError("player should wear the spider morph");
			sp.getServer().runOnServer(server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "claude unmorph @a"));
			ctx.waitTicks(5);
			ctx.takeScreenshot("claudecraft-morph-none");
		}
	}
}
