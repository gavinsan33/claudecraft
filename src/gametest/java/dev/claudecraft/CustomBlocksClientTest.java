package dev.claudecraft;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.Collections;

/** Defines a few custom blocks, places them, screenshots them, and checks light, drops and live redefinition. */
public class CustomBlocksClientTest implements FabricClientGameTest {
	private static final Gson GSON = new Gson();

	private static JsonObject cube(String id, String palette, String side, String top, String bottom) {
		JsonObject faces = new JsonObject();
		faces.add("side", JsonParser.parseString(side));
		faces.add("top", JsonParser.parseString(top));
		faces.add("bottom", JsonParser.parseString(bottom));
		JsonObject model = new JsonObject();
		model.add("palette", JsonParser.parseString(palette));
		model.add("faces", faces);
		return block(id, model, new JsonObject());
	}

	private static JsonObject block(String id, JsonObject model, JsonObject props) {
		JsonObject def = new JsonObject();
		def.addProperty("id", id);
		def.addProperty("kind", "block");
		def.addProperty("slot", CustomBlocks.allocateSlot(id));
		def.addProperty("name", id);
		def.addProperty("style", "block");
		def.add("model", model);
		def.add("properties", props);
		return def;
	}

	private static String rows(int n, java.util.function.IntFunction<String> row) {
		JsonArray a = new JsonArray();
		for (int i = 0; i < n; i++) a.add(row.apply(i));
		return a.toString();
	}

	private static void defineAll(MinecraftServer server, String grassTop) {
		String side = rows(16, r -> r < 3 ? grassTop.repeat(16) : (r % 5 == 0 ? "ds".repeat(8) : "d".repeat(16)));
		CustomItems.define(server, cube("moss_dirt", "{\"g\":\"" + (grassTop.equals("g") ? "#4F9A2E" : "#3A6FD8") + "\",\"b\":\"#3A6FD8\",\"d\":\"#7A5230\",\"s\":\"#5E3E22\"}",
			side, GSON.toJson(Collections.nCopies(16, grassTop.repeat(16))), GSON.toJson(Collections.nCopies(16, "d".repeat(16)))));

		// A small lantern: 6x8x6 frame with a glowing core, sitting on the floor.
		JsonArray layers = new JsonArray();
		for (int y = 0; y < 8; y++) {
			JsonArray layer = new JsonArray();
			for (int z = 0; z < 6; z++) {
				StringBuilder row = new StringBuilder();
				for (int x = 0; x < 6; x++) {
					boolean edge = (x == 0 || x == 5) && (z == 0 || z == 5);
					boolean cap = y == 0 || y == 7;
					row.append(cap || edge ? 'i' : (x > 0 && x < 5 && z > 0 && z < 5 ? 'l' : '.'));
				}
				layer.add(row.toString());
			}
			layers.add(layer);
		}
		JsonObject lantern = new JsonObject();
		lantern.add("palette", JsonParser.parseString("{\"i\":\"#2B2B30\",\"l\":\"#FFC94A\"}"));
		lantern.add("layers", layers);
		JsonObject lp = new JsonObject();
		lp.addProperty("light", 14);
		lp.addProperty("sound", "metal");
		CustomItems.define(server, block("lantern", lantern, lp));

		JsonObject glass = new JsonObject();
		glass.add("palette", JsonParser.parseString("{\"f\":\"#FFE0F0FF\",\"g\":\"#5590D0FF\"}"));
		JsonObject gf = new JsonObject();
		gf.add("all", JsonParser.parseString(rows(16, r -> r == 0 || r == 15 ? "f".repeat(16) : "f" + "g".repeat(14) + "f")));
		glass.add("faces", gf);
		CustomItems.define(server, block("blue_glass", glass, new JsonObject()));
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			java.util.concurrent.atomic.AtomicInteger groundY = new java.util.concurrent.atomic.AtomicInteger();
			sp.getServer().runOnServer(server -> {
				defineAll(server, "g");
				ServerLevel level = server.overworld();
				int y = level.getChunk(0, 0).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 4) + 1;
				groundY.set(y);
				for (int x = -2; x <= 2; x++) level.setBlockAndUpdate(new BlockPos(x, y, 4), CustomBlocks.state(CustomItems.DEFS.get("moss_dirt")));
				level.setBlockAndUpdate(new BlockPos(-1, y + 1, 4), CustomBlocks.state(CustomItems.DEFS.get("lantern")));
				level.setBlockAndUpdate(new BlockPos(1, y + 1, 4), CustomBlocks.state(CustomItems.DEFS.get("blue_glass")));
				level.setBlockAndUpdate(new BlockPos(1, y + 2, 4), CustomBlocks.state(CustomItems.DEFS.get("blue_glass")));
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "tp @a 0.5 " + (y + 1) + " 0.5 0 25");
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set night");
			});
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(40);
			ctx.takeScreenshot("claudecraft-blocks");

			String[] state = new String[1];
			int[] check = sp.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				BlockPos lanternPos = new BlockPos(-1, groundY.get() + 1, 4);
				state[0] = level.getBlockState(lanternPos).toString();
				int light = level.getBlockState(lanternPos).getLightEmission();
				double shapeTop = level.getBlockState(lanternPos).getShape(level, lanternPos).max(net.minecraft.core.Direction.Axis.Y);
				level.destroyBlock(lanternPos, true);
				var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(lanternPos).inflate(2));
				boolean named = drops.stream().anyMatch(e -> e.getItem().getHoverName().getString().equals("lantern"));
				state[0] = drops.stream().map(e -> e.getItem() + " " + e.getItem().getHoverName().getString() + " " + e.getItem().getComponents()).toList().toString();
				return new int[]{light, (int) Math.round(shapeTop * 16), named ? 1 : 0};
			});
			if (check[0] != 14) throw new AssertionError("lantern should give light 14, got " + check[0] + " from " + state[0]);
			if (check[1] != 8) throw new AssertionError("lantern's shape should be 8 voxels tall, got " + check[1]);
			if (check[2] != 1) throw new AssertionError("breaking the lantern should drop a lantern item, got " + state[0]);

			// Redefine the grass with a blue top: placed blocks should redraw.
			sp.getServer().runOnServer(server -> defineAll(server, "b"));
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(20);
			ctx.takeScreenshot("claudecraft-blocks-redefined");
		}
	}
}
