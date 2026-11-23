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
			go(sp, """
				{"id":"alien_hills","name":"Alien Hills","sky":"daylight","biome":"alien",
				 "terrain":{"style":"hills","variation":20,"surface":"minecraft:grass_block","stone":"minecraft:purpur_block"}}""");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(40);
			ctx.takeScreenshot("claudecraft-dimension-alien");
			String dim = ctx.computeOnClient(mc -> mc.level.dimension().identifier().toString());
			if (!dim.equals("claudecraft:alien_hills")) throw new AssertionError("client should be in the new dimension, is in " + dim);

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
