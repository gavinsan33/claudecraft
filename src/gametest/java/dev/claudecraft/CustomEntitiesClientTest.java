package dev.claudecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Defines two creatures, spawns them facing the camera, and checks size, health, names, commands, drops and redefinition. */
public class CustomEntitiesClientTest implements FabricClientGameTest {
	/** A solid box of one palette character, as layers. */
	private static JsonArray box(int w, int h, int d, char c) {
		JsonArray layers = new JsonArray();
		for (int y = 0; y < h; y++) {
			JsonArray rows = new JsonArray();
			for (int z = 0; z < d; z++) rows.add(String.valueOf(c).repeat(w));
			layers.add(rows);
		}
		return layers;
	}

	private static JsonObject part(String name, String role, int[] origin, float[] pivot, JsonArray layers) {
		JsonObject p = new JsonObject();
		p.addProperty("name", name);
		p.addProperty("role", role);
		p.add("origin", JsonParser.parseString(java.util.Arrays.toString(origin)));
		if (pivot != null) p.add("pivot", JsonParser.parseString(java.util.Arrays.toString(pivot)));
		p.add("layers", layers);
		return p;
	}

	private static void define(MinecraftServer server, String bodyColor) {
		// Fox: white body, head at the front (+z) with an orange snout, four legs, a tail at the back.
		JsonArray head = box(6, 5, 5, 'w');
		JsonArray snoutLayer = head.get(1).getAsJsonArray();
		snoutLayer.set(4, new com.google.gson.JsonPrimitive("wwoooww".substring(0, 6)));
		JsonArray parts = new JsonArray();
		parts.add(part("body", "body", new int[]{-3, 5, -6}, null, box(6, 5, 12, 'w')));
		parts.add(part("head", "head", new int[]{-3, 7, 6}, new float[]{0, 8, 6}, head));
		parts.add(part("leg_fl", "leg", new int[]{-3, 0, 3}, null, box(2, 5, 2, 'g')));
		parts.add(part("leg_fr", "leg", new int[]{1, 0, 3}, null, box(2, 5, 2, 'g')));
		parts.add(part("leg_bl", "leg", new int[]{1, 0, -5}, null, box(2, 5, 2, 'g')));
		parts.add(part("leg_br", "leg", new int[]{-3, 0, -5}, null, box(2, 5, 2, 'g')));
		parts.add(part("tail", "tail", new int[]{-1, 7, -12}, new float[]{0, 8, -6}, box(2, 2, 6, 'o')));
		JsonObject model = new JsonObject();
		model.add("palette", JsonParser.parseString("{\"w\":\"" + bodyColor + "\",\"o\":\"#E8732A\",\"g\":\"#9AA0A6\"}"));
		model.add("parts", parts);
		JsonObject def = new JsonObject();
		def.addProperty("id", "frost_fox");
		def.addProperty("kind", "entity");
		def.addProperty("name", "Frost Fox");
		def.addProperty("scale", 16);
		def.add("model", model);
		def.add("properties", JsonParser.parseString(
			"{\"health\":16,\"speed\":0.3,\"sounds\":\"fox\",\"drops\":[\"minecraft:snowball 2\"],\"interact_commands\":[\"tag @s add petted\"]}"));
		CustomItems.define(server, def);

		JsonArray bird = new JsonArray();
		bird.add(part("body", "body", new int[]{-2, 4, -3}, null, box(4, 4, 6, 'b')));
		bird.add(part("head", "head", new int[]{-2, 8, 1}, new float[]{0, 8, 2}, box(4, 3, 3, 'b')));
		bird.add(part("wing_l", "wing", new int[]{2, 6, -2}, new float[]{2, 7, 0}, box(7, 1, 4, 'y')));
		bird.add(part("wing_r", "wing", new int[]{-9, 6, -2}, new float[]{-2, 7, 0}, box(7, 1, 4, 'y')));
		JsonObject bm = new JsonObject();
		bm.add("palette", JsonParser.parseString("{\"b\":\"#3060C0\",\"y\":\"#F2C230\"}"));
		bm.add("parts", bird);
		JsonObject bd = new JsonObject();
		bd.addProperty("id", "sky_finch");
		bd.addProperty("kind", "entity");
		bd.addProperty("name", "Sky Finch");
		bd.add("model", bm);
		bd.add("properties", JsonParser.parseString("{\"movement\":\"fly\",\"health\":4}"));
		CustomItems.define(server, bd);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(server -> {
				define(server, "#F4F6FA");
				ServerLevel level = server.overworld();
				int y = level.getChunk(0, 0).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0) + 1;
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "tp @a 0.5 " + y + " 0.5 0 20");
				for (var spec : new Object[][]{{"frost_fox", -1.0, 0.0}, {"sky_finch", 1.5, 1.2}}) {
					var e = CustomEntities.spawn(level, CustomItems.DEFS.get((String) spec[0]), new Vec3(0.5 + (double) spec[1], y + (double) spec[2], 4.5), 1).getFirst();
					e.setNoAi(true);
					e.setYRot(180); // face north, toward the camera
					e.yBodyRot = e.yBodyRotO = e.yHeadRot = e.yHeadRotO = 180;
				}
				// A baby fox beside them, and the fox's spawn egg in hand.
				var baby = CustomEntities.spawn(level, CustomItems.DEFS.get("frost_fox"), new Vec3(-0.8, y, 3.2), 1, true, false).getFirst();
				baby.setNoAi(true);
				baby.setYRot(180);
				baby.yBodyRot = baby.yBodyRotO = baby.yHeadRot = baby.yHeadRotO = 180;
				server.getPlayerList().getPlayers().getFirst().getInventory().setItem(8, CustomItems.stack(CustomItems.DEFS.get("frost_fox"), 1));
			});
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(30);
			ctx.takeScreenshot("claudecraft-creatures");

			String result = sp.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				var fox = level.getEntitiesOfClass(CustomEntities.CustomEntity.class, new AABB(-20, -100, -20, 20, 100, 20), e -> e.definitionId().equals("frost_fox")).getFirst();
				StringBuilder bad = new StringBuilder();
				if (Math.abs(fox.getBbHeight() - 12 / 16f) > 1e-4) bad.append("height ").append(fox.getBbHeight()).append("; ");
				if (Math.abs(fox.getBbWidth() - 24 / 16f) > 1e-4) bad.append("width ").append(fox.getBbWidth()).append("; ");
				if (fox.getMaxHealth() != 16) bad.append("health ").append(fox.getMaxHealth()).append("; ");
				if (!fox.getName().getString().equals("Frost Fox")) bad.append("name ").append(fox.getName().getString()).append("; ");
				var player = server.getPlayerList().getPlayers().getFirst();
				fox.interact(player, net.minecraft.world.InteractionHand.MAIN_HAND, fox.position());
				if (!player.entityTags().contains("petted")) bad.append("interact command didn't run; ");
				fox.kill(level);
				var drops = level.getEntitiesOfClass(ItemEntity.class, fox.getBoundingBox().inflate(3));
				if (drops.stream().noneMatch(d -> d.getItem().is(net.minecraft.world.item.Items.SNOWBALL) && d.getItem().getCount() == 2)) bad.append("no snowball drop; ");
				return bad.toString();
			});
			if (!result.isEmpty()) throw new AssertionError(result);

			// Redefine the finch bigger: the spawned one should grow.
			boolean grew = sp.getServer().computeOnServer(server -> {
				JsonObject bd = CustomItems.DEFS.get("sky_finch").deepCopy();
				bd.addProperty("scale", 8);
				CustomItems.define(server, bd);
				var finch = server.overworld().getEntitiesOfClass(CustomEntities.CustomEntity.class, new AABB(-20, -100, -20, 20, 100, 20), e -> e.definitionId().equals("sky_finch")).getFirst();
				return Math.abs(finch.getBbHeight() - 11 / 8f) < 1e-4;
			});
			if (!grew) throw new AssertionError("redefining should resize spawned creatures");
			ctx.waitTicks(20);
			ctx.takeScreenshot("claudecraft-creatures-redefined");
		}
	}
}
