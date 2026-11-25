package dev.claudecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * A tornado effect: a dust funnel and dirt debris (particles parts whose flow swirls them up a widening funnel) plus
 * a field that pulls things in. Plays it 40 blocks away, past where effects used to vanish, and screenshots it.
 */
public class CustomParticlesClientTest implements FabricClientGameTest {
	/** Swirl around the y axis at 9 blocks/s, rise, and pull toward a funnel that widens with height. */
	private static final String FLOW = """
		{"x":"(-z*9 + x*(0.6 + y*0.35 - hypot(x,z))*2) / (hypot(x,z) + 0.01)",
		 "y":"2.5",
		 "z":"(x*9 + z*(0.6 + y*0.35 - hypot(x,z))*2) / (hypot(x,z) + 0.01)"}""";

	private static final String TORNADO = """
		{"id":"tornado","name":"Tornado","duration_seconds":20,"parts":[
		 {"type":"particles","texture":"big_smoke","colors":["#706458","#706458","#605448","#504438"],"opacity":[0,0.75,0.63,0],
		  "sizes":[1.2,3],"lifetime":[2.5,4],"rate":200,
		  "spawn":{"x":"cos(r1*2*pi)*(0.6 + r2^1.5*18*0.35)","y":"r2^1.5*18","z":"sin(r1*2*pi)*(0.6 + r2^1.5*18*0.35)"},
		  "flow":%s},
		 {"type":"particles","texture":"block:minecraft:dirt","sizes":[0.25],"lifetime":[2,3],"rate":30,"spin":1,"gravity":4,
		  "spawn":{"x":"cos(r1*2*pi)*2","z":"sin(r1*2*pi)*2"},"velocity":{"y":"2 + r2*4"},"flow":%s}],
		 "field":{"radius":8,"pull":1}}""".formatted(FLOW, FLOW);

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			String summary = sp.getServer().computeOnServer(server -> {
				JsonObject def = JsonParser.parseString(TORNADO).getAsJsonObject();
				def.addProperty("kind", "effect");
				// CSS-style #RRGGBBAA would read as a different, solid color (it's how fountains came out green): refused.
				JsonObject css = def.deepCopy();
				css.getAsJsonArray("parts").get(0).getAsJsonObject().add("colors", JsonParser.parseString("[\"#F0F8FF60\"]"));
				if (Effects.validate(css) == null || !Effects.validate(css).contains("opacity")) throw new AssertionError("8-digit particle colors should be refused");
				// opacity fades a particle: white at full, then half, then gone.
				var spec = new CustomParticles.Spec(JsonParser.parseString("{\"colors\":[\"#FFFFFF\"],\"opacity\":[1,0],\"count\":1}").getAsJsonObject());
				if (spec.color(0) != 0xFFFFFFFF || (spec.color(0.5f) >>> 24) != 128 || (spec.color(1) >>> 24) != 0 || (spec.color(0.5f) & 0xFFFFFF) != 0xFFFFFF) {
					throw new AssertionError("opacity should fade white out without changing its color");
				}
				String bad = Effects.validate(def);
				if (bad != null) throw new AssertionError(bad);
				CustomItems.define(server, def);
				int y = server.overworld().getChunk(0, 2).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 40) + 1;
				var src = server.createCommandSourceStack();
				server.getCommands().performPrefixedCommand(src, "tp @a 0.5 " + (y + 2) + " 0.5 0 -12");
				server.getCommands().performPrefixedCommand(src, "time set noon");
				server.getCommands().performPrefixedCommand(src, "claude effect tornado 0.5 " + y + " 40.5");
				return Effects.particleSummary(def);
			});
			System.out.println("[claudecraft-gametest]" + summary);
			if (!summary.contains("Part 1 dry run") || !summary.contains("y 0.0..")) throw new AssertionError("dust should start at the ground: " + summary);

			ctx.waitTicks(80);
			String count = ctx.computeOnClient(mc -> mc.particleEngine.countParticles());
			System.out.println("[claudecraft-gametest] particles: " + count);
			if (Integer.parseInt(count.replaceAll(".* ", "")) < 500) throw new AssertionError("expected a tornado's worth of particles, got " + count);
			boolean shown = ctx.computeOnClient(mc -> mc.level.getEntitiesOfClass(Effects.EffectEntity.class, new net.minecraft.world.phys.AABB(-5, -64, 30, 5, 320, 50))
				.stream().allMatch(e -> e.shouldRender(mc.gameRenderer.mainCamera().position().x, mc.gameRenderer.mainCamera().position().y, mc.gameRenderer.mainCamera().position().z)));
			if (!shown) throw new AssertionError("an effect 40 blocks away should still render");
			ctx.takeScreenshot("claudecraft-tornado");

			// Claude's own sprite: a two-frame snowflake falling and twinkling in front of the camera.
			String fallen = sp.getServer().computeOnServer(server -> {
				JsonObject snow = JsonParser.parseString("""
					{"id":"snowfall","name":"Snowfall","kind":"effect","duration_seconds":10,"parts":[
					 {"type":"particles","rate":60,"sizes":[0.5],"lifetime":[3,4],"emissive":true,"spin":0.3,
					  "sprite":{"palette":{"w":"#FFFFFF","b":"#9CD4FF"},"frames":[
					   ["..w.w..","w.www.w",".wwbww.","wwbbbww",".wwbww.","w.www.w","..w.w.."],
					   ["...w...","..www..",".wbwbw.","wwbbbww",".wbwbw.","..www..","...w..."]]},
					  "spawn":{"x":"(r1-0.5)*8","y":"4 + r2*2","z":"(r3-0.5)*3"},"velocity":{"y":"-1"}}]}""").getAsJsonObject();
				if (Effects.validate(snow) != null) throw new AssertionError(Effects.validate(snow));
				CustomItems.define(server, snow);
				var player = server.getPlayerList().getPlayers().getFirst();
				var src = server.createCommandSourceStack();
				server.getCommands().performPrefixedCommand(src, String.format(java.util.Locale.ROOT, "claude effect snowfall %.1f %.1f %.1f",
					player.getX(), player.getY(), player.getZ() + 6));
				// A falling block written the natural way (properties inline) should stay that block, not turn into sand.
				String cmd = CommandRewriter.rewrite(String.format(java.util.Locale.ROOT,
					"summon falling_block %.1f %.1f %.1f {BlockState:{Name:\"minecraft:oak_log[axis=x]\"},NoGravity:1b,Time:1}",
					player.getX() + 2, player.getY() + 3, player.getZ() + 5), CustomItems.DEFS::get, CustomItems.commandItems(server));
				server.getCommands().performPrefixedCommand(src, cmd);
				var blocks = server.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class, player.getBoundingBox().inflate(10));
				return blocks.isEmpty() ? "none" : blocks.getFirst().getBlockState().toString();
			});
			if (!fallen.contains("oak_log") || !fallen.contains("axis=x")) throw new AssertionError("falling block should be an oak log on its side, got " + fallen);
			ctx.runOnClient(mc -> mc.player.setXRot(0));
			ctx.waitTicks(60);
			ctx.takeScreenshot("claudecraft-snowflakes");
		}
	}
}
