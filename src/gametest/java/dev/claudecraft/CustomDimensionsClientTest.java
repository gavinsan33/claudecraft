package dev.claudecraft;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Creates dimensions while a player is connected, sends them there, and screenshots what they see. */
public class CustomDimensionsClientTest implements FabricClientGameTest {
	private static void go(TestSingleplayerContext sp, String json) {
		sp.getServer().runOnServer(server -> {
			var def = JsonParser.parseString(json).getAsJsonObject();
			def.addProperty("kind", "dimension");
			CustomItems.define(server, def);
			var level = Dimensions.ensure(server, def);
			Dimensions.visit(server.getPlayerList().getPlayers().getFirst(), level, null);
		});
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			// Like a player's flag world: the whole underground is a painted custom block. Before solid custom blocks
			// hid each other's faces, this drew millions of buried quads and ran the game out of memory.
			sp.getServer().runOnServer(server -> {
				StringBuilder rows = new StringBuilder();
				for (int r = 0; r < 16; r++) rows.append(r > 0 ? "," : "").append('"').append(r % 4 < 2 ? "wwwwwwwbbwwwwwww" : "bbwwbbwwbbwwbbww").append('"');
				var flag = JsonParser.parseString("{\"id\":\"flag_stone\",\"kind\":\"block\",\"slot\":200,\"name\":\"Flag Stone\",\"style\":\"block\","
					+ "\"model\":{\"palette\":{\"w\":\"#FFFFFF\",\"b\":\"#0038B8\"},\"faces\":{\"all\":[" + rows + "]}},\"properties\":{}}").getAsJsonObject();
				CustomItems.define(server, flag);
			});
			go(sp, """
				{"id":"alien_hills","name":"Alien Hills","sky":"daylight","biome":"alien",
				 "terrain":{"style":"hills","variation":20,"surface":"minecraft:grass_block","stone":"custom:flag_stone"}}""");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(40);
			ctx.takeScreenshot("claudecraft-dimension-alien");
			String dim = ctx.computeOnClient(mc -> mc.level.dimension().identifier().toString());
			if (!dim.equals("claudecraft:alien_hills")) throw new AssertionError("client should be in the new dimension, is in " + dim);
			int flagBlocks = sp.getServer().computeOnServer(server -> {
				var level = server.getLevel(Dimensions.key("alien_hills"));
				int n = 0;
				for (int y = level.getMinY(); y < 60; y++) if (level.getBlockState(new net.minecraft.core.BlockPos(4, y, 4)).is(CustomBlocks.BLOCK)) n++;
				return n;
			});
			if (flagBlocks < 50) throw new AssertionError("the underground should be flag stone, found " + flagBlocks + " in a column");

			go(sp, """
				{"id":"sky_islands","name":"Sky Islands","sky":"starry","biome":"golden","terrain":{"style":"islands"}}""");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(40);
			ctx.takeScreenshot("claudecraft-dimension-islands");

			go(sp, """
				{"id":"underdark","name":"Underdark","sky":"cavern","biome":"crimson","terrain":{"style":"caves","stone":"minecraft:deepslate"}}""");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(40);
			ctx.takeScreenshot("claudecraft-dimension-cavern");

			// And home again.
			sp.getServer().runOnServer(server -> Dimensions.visit(server.getPlayerList().getPlayers().getFirst(), server.overworld(), null));
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(20);
			dim = ctx.computeOnClient(mc -> mc.level.dimension().identifier().toString());
			if (!dim.equals("minecraft:overworld")) throw new AssertionError("should be back in the overworld, is in " + dim);
		}
	}
}
