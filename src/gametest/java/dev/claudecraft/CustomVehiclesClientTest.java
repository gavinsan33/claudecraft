package dev.claudecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;

/** A rocket (thrust controls, flame exhaust) and a car (drive controls), driven with real key presses. */
public class CustomVehiclesClientTest implements FabricClientGameTest {
	private static JsonArray box(int w, int h, int d, char c) {
		JsonArray layers = new JsonArray();
		for (int y = 0; y < h; y++) {
			JsonArray rows = new JsonArray();
			for (int z = 0; z < d; z++) rows.add(String.valueOf(c).repeat(w));
			layers.add(rows);
		}
		return layers;
	}

	private static JsonObject part(String role, int x, int y, int z, JsonArray layers) {
		JsonObject p = new JsonObject();
		p.addProperty("role", role);
		p.add("origin", JsonParser.parseString("[" + x + "," + y + "," + z + "]"));
		p.add("layers", layers);
		return p;
	}

	private static JsonObject creature(String id, String palette, JsonArray parts, String vehicle) {
		JsonObject model = new JsonObject();
		model.add("palette", JsonParser.parseString(palette));
		model.add("parts", parts);
		JsonObject d = new JsonObject();
		d.addProperty("id", id);
		d.addProperty("kind", "entity");
		d.addProperty("name", id);
		d.add("model", model);
		JsonObject props = new JsonObject();
		props.add("vehicle", JsonParser.parseString(vehicle));
		props.addProperty("health", 40);
		d.add("properties", props);
		return d;
	}

	private static double[] pos(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			var v = mc.player.getVehicle();
			return v == null ? null : new double[]{v.getX(), v.getY(), v.getZ(), ((CustomEntities.CustomEntity) v).roll};
		});
	}

	/** The floor the test builds: everything stands at this height. */
	private int floor;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(server -> {
				JsonObject flames = JsonParser.parseString("""
					{"id":"rocket_flames","name":"Rocket flames","kind":"effect","duration_seconds":0.3,"parts":[
					 {"type":"particles","texture":"flame","emissive":true,"rate":120,"trail":true,"sizes":[0.5,0.1],"lifetime":[0.4,0.7],
					  "spawn":{"x":"(r1-0.5)*0.4","z":"(r2-0.5)*0.4"},"velocity":{"y":"-6 - r3*3"}},
					 {"type":"particles","texture":"big_smoke","rate":40,"trail":true,"colors":["#C0A0A0A0","#00606060"],"sizes":[0.8,2],"lifetime":[1,2],
					  "velocity":{"x":"(r1-0.5)*2","y":"-2","z":"(r2-0.5)*2"}}]}""").getAsJsonObject();
				if (Effects.validate(flames) != null) throw new AssertionError(Effects.validate(flames));
				CustomItems.define(server, flames);
				JsonArray rocket = new JsonArray();
				rocket.add(part("body", -3, 0, -3, box(6, 22, 6, 'w')));
				rocket.add(part("body", -2, 22, -2, box(4, 4, 4, 'r')));
				rocket.add(part("body", -5, 0, -1, box(10, 5, 2, 'r')));
				CustomItems.define(server, creature("rocket", "{\"w\":\"#E8E8EC\",\"r\":\"#D02828\"}", rocket,
					"{\"controls\":\"thrust\",\"exhaust\":\"rocket_flames\"}"));
				JsonArray car = new JsonArray();
				car.add(part("body", -6, 3, -10, box(12, 6, 20, 'b')));
				for (int[] w : new int[][]{{-7, -8}, {5, -8}, {-7, 4}, {5, 4}}) car.add(part("body", w[0], 0, w[1], box(2, 4, 4, 'k')));
				CustomItems.define(server, creature("car", "{\"b\":\"#2A5BD7\",\"k\":\"#181818\"}", car, "{\"controls\":\"drive\",\"max_speed\":10}"));

				var level = server.overworld();
				int y = level.getChunk(0, 0).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0) + 1;
				floor = y;
				var src = server.createCommandSourceStack();
				server.getCommands().performPrefixedCommand(src, "fill -20 " + y + " -20 20 " + (y + 30) + " 40 air");
				server.getCommands().performPrefixedCommand(src, "fill -20 " + (y - 1) + " -20 20 " + (y - 1) + " 40 stone");
				server.getCommands().performPrefixedCommand(src, "time set noon");
				server.getCommands().performPrefixedCommand(src, "tp @a 0.5 " + y + " 0.5 0 20");
				var player = server.getPlayerList().getPlayers().getFirst();
				var r = CustomEntities.spawn(level, CustomItems.DEFS.get("rocket"), new net.minecraft.world.phys.Vec3(0.5, y, 0.5), 1).getFirst();
				r.snapTo(0.5, y, 0.5, 0, 0);
				player.startRiding(r);
			});
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			ctx.waitTicks(20);
			double[] start = pos(ctx);
			if (start == null) throw new AssertionError("the player should be riding the rocket");

			ctx.getInput().holdKey(o -> o.keyJump);
			ctx.waitTicks(40);
			double[] up = pos(ctx);
			ctx.takeScreenshot("claudecraft-rocket-thrust");
			if (up[1] - start[1] < 3) throw new AssertionError("holding jump should climb, went from " + start[1] + " to " + up[1]);

			ctx.getInput().holdKey(o -> o.keyRight);
			ctx.waitTicks(25);
			double[] side = pos(ctx);
			ctx.takeScreenshot("claudecraft-rocket-tilt");
			ctx.getInput().releaseKey(o -> o.keyRight);
			// Facing south (+z), the player's right is -x; the rocket should drift that way and bank into it.
			if (side[0] > up[0] - 1) throw new AssertionError("holding D should drift right (-x): " + up[0] + " -> " + side[0]);
			if (side[3] < 5) throw new AssertionError("it should lean into the drift, roll " + side[3]);

			ctx.getInput().releaseKey(o -> o.keyJump);
			ctx.waitTicks(40);
			double[] fall = pos(ctx);
			if (fall[1] > side[1] - 2) throw new AssertionError("letting go should sink back: " + side[1] + " -> " + fall[1]);

			// The car: W drives forward along the ground.
			sp.getServer().runOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				player.stopRiding();
				var level = server.overworld();
				var c = CustomEntities.spawn(level, CustomItems.DEFS.get("car"), new net.minecraft.world.phys.Vec3(8.5, floor, 0.5), 1).getFirst();
				c.snapTo(8.5, floor, 0.5, 0, 0);
				player.startRiding(c);
			});
			ctx.waitTicks(20);
			double[] park = pos(ctx);
			ctx.getInput().holdKey(o -> o.keyUp);
			ctx.waitTicks(40);
			double[] drove = pos(ctx);
			ctx.takeScreenshot("claudecraft-car");
			ctx.getInput().releaseKey(o -> o.keyUp);
			if (drove[2] - park[2] < 4) throw new AssertionError("holding W should drive forward (+z): " + park[2] + " -> " + drove[2]);
			if (Math.abs(drove[0] - park[0]) > 1) throw new AssertionError("without A/D it should drive straight: x " + park[0] + " -> " + drove[0]);
			if (Math.abs(drove[1] - park[1]) > 0.6) throw new AssertionError("the car should stay on the ground: " + park[1] + " -> " + drove[1]);
		}
	}
}
