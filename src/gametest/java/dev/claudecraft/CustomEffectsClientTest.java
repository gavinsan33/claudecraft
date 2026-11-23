package dev.claudecraft;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;

/** Plays a laser, lightning, a black hole, a spiral beam and a shockwave in front of the camera and screenshots them. */
public class CustomEffectsClientTest implements FabricClientGameTest {
	private static final String[] EFFECTS = {
		"""
		{"id":"laser","name":"Laser","duration_seconds":30,"parts":[{"type":"beam","color":"#FF2020","width":0.08,"glow":4}]}""",
		"""
		{"id":"zap","name":"Lightning","duration_seconds":30,"parts":[
		 {"type":"beam","style":"lightning","color":"#7FB8FF","width":0.1,"branches":4},
		 {"type":"sparks","at":"end","count":40,"color":"#CFE6FF","speed":2,"gravity":3,"end_seconds":1.5,"fade":0}]}""",
		"""
		{"id":"black_hole","name":"Black Hole","duration_seconds":30,"parts":[
		 {"type":"orb","radius":0.9,"color":"#000000","glow_color":"#9040FF","glow":2.4,"ring":3.2,"ring_color":"#FFB060","tilt":70,"spin":0.4},
		 {"type":"sparks","inward":true,"radius":4,"count":80,"color":"#C080FF","speed":2,"size":0.06}]}""",
		"""
		{"id":"spiral","name":"Spiral","duration_seconds":30,"parts":[{"type":"beam","style":"spiral","color":"#40FF80","amplitude":0.4,"frequency":0.5}]}""",
		"""
		{"id":"shock","name":"Shockwave","duration_seconds":30,"parts":[{"type":"ring","from_radius":0.5,"to_radius":3,"color":"#FFE060","fade":0,"end_seconds":30}]}""",
	};

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(server -> {
				for (String json : EFFECTS) {
					var def = JsonParser.parseString(json).getAsJsonObject();
					def.addProperty("kind", "effect");
					CustomItems.define(server, def);
				}
				var level = server.overworld();
				int y = level.getChunk(0, 0).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0) + 1;
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "tp @a 0.5 " + y + " 0.5 0 0");
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set night");
				double e = y + 1.6;
				// Facing south (+z): +x is to the camera's left.
				Effects.play(level, CustomItems.DEFS.get("laser"), new Vec3(6, e + 2.5, 10), new Vec3(-6, e + 2.5, 10), null);
				Effects.play(level, CustomItems.DEFS.get("zap"), new Vec3(-4, e + 8, 12), new Vec3(-4, y, 12), null);
				Effects.play(level, CustomItems.DEFS.get("black_hole"), new Vec3(2.5, e + 0.5, 9), new Vec3(2.5, e + 0.5, 9), null);
				Effects.play(level, CustomItems.DEFS.get("spiral"), new Vec3(6, e - 1, 8), new Vec3(-6, e - 1.5, 8), null);
				Effects.play(level, CustomItems.DEFS.get("shock"), new Vec3(-1, y + 0.1, 7), new Vec3(-1, y + 0.1, 7), null);
			});
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(15);
			ctx.takeScreenshot("claudecraft-effects");
			ctx.waitTicks(25);
			ctx.takeScreenshot("claudecraft-effects-later");
		}
	}
}
