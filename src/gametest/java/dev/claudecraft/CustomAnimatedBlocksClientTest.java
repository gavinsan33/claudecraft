package dev.claudecraft;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** A shimmering portal in an obsidian frame, a row of flickering fire and a pulsing core, at dusk, a few ticks apart. */
public class CustomAnimatedBlocksClientTest implements FabricClientGameTest {
	private static void define(String json) {
		try {
			ClaudeCraft.tools().runTool("define_block", JsonParser.parseString(json).getAsJsonObject());
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	/** A flame picture: tongues of fire, tallest in the middle, white-yellow at the root and red at the tips. */
	private static String flame(int frame) {
		java.util.Random rnd = new java.util.Random(frame * 31L + 7);
		int[] height = new int[16];
		for (int c = 0; c < 16; c++) {
			double middle = 1 - Math.abs(c - 7.5) / 8;
			height[c] = (int) Math.round(4 + 10 * middle * middle + rnd.nextInt(4) - 1);
		}
		StringBuilder rows = new StringBuilder();
		for (int r = 0; r < 16; r++) {
			int up = 15 - r;
			StringBuilder row = new StringBuilder();
			for (int c = 0; c < 16; c++) {
				int h = height[c];
				char ch = up >= h ? '.' : up < 3 ? 'w' : up < h * 0.45 ? 'y' : up < h * 0.8 ? 'o' : 'r';
				row.append(c == 0 || c == 15 ? '.' : ch);
			}
			rows.append(r > 0 ? "," : "").append('"').append(row).append('"');
		}
		return "{\"faces\":{\"cross\":[" + rows + "]}}";
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(server -> {
				define("{\"id\":\"t_portal\",\"name\":\"Portal\"," + ModdingServerTest.PORTAL_BLOCK
					+ ",\"frame_time\":3,\"properties\":{\"collision\":\"none\",\"glow\":true,\"light\":11,\"particles\":[{\"type\":\"portal\",\"count\":4}]}}");
				define("{\"id\":\"t_fire\",\"name\":\"Fire\",\"palette\":{\"w\":\"#FFF6C0\",\"y\":\"#FFD230\",\"o\":\"#FF8A1C\",\"r\":\"#E8401A\"},\"shape\":\"cross\",\"frames\":["
					+ flame(0) + "," + flame(1) + "," + flame(2) + "," + flame(3) + "],\"frame_time\":2,\"desync\":true,"
					+ "\"properties\":{\"collision\":\"none\",\"burns\":true,\"light\":15,\"glow\":true,\"particles\":[{\"type\":\"flame\",\"where\":\"top\"},{\"type\":\"smoke\",\"where\":\"top\",\"chance\":0.5}]}}");
				// A machine with a still casing and a core that pulses: only the core is redrawn each frame.
				StringBuilder rows = new StringBuilder();
				for (int r = 0; r < 16; r++) {
					StringBuilder row = new StringBuilder();
					for (int c = 0; c < 16; c++) {
						boolean edge = r < 2 || r > 13 || c < 2 || c > 13;
						row.append(edge ? 'k' : (r > 4 && r < 11 && c > 4 && c < 11) ? 'p' : 'g');
					}
					rows.append(r > 0 ? "," : "").append('"').append(row).append('"');
				}
				define("{\"id\":\"t_core\",\"name\":\"Core\",\"palette\":{\"k\":\"#303438\",\"g\":\"#50585E\",\"p\":[\"#204060\",\"#3080C0\",\"#60D0FF\",\"#3080C0\"]},"
					+ "\"faces\":{\"all\":[" + rows + "]},\"frame_time\":4,\"properties\":{\"rotate\":true}}");

				var level = server.overworld();
				int y = level.getChunk(0, 0).getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0) + 1;
				for (int x = -6; x <= 6; x++) for (int z = 2; z <= 10; z++) {
					level.setBlockAndUpdate(new BlockPos(x, y - 1, z), Blocks.STONE_BRICKS.defaultBlockState());
					for (int dy = 0; dy < 8; dy++) level.setBlockAndUpdate(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState());
				}
				var portal = CustomBlocks.state(CustomItems.DEFS.get("t_portal"));
				for (int x = -1; x <= 2; x++) for (int dy = 0; dy < 5; dy++) {
					boolean inside = x > -1 && x < 2 && dy > 0 && dy < 4;
					level.setBlockAndUpdate(new BlockPos(x, y + dy - 1, 8), inside ? portal : Blocks.OBSIDIAN.defaultBlockState());
				}
				var fire = CustomBlocks.state(CustomItems.DEFS.get("t_fire"));
				for (int x = -5; x <= -3; x++) level.setBlockAndUpdate(new BlockPos(x, y, 6), fire);
				var core = CustomBlocks.state(CustomItems.DEFS.get("t_core")).setValue(CustomBlocks.FACING, Direction.NORTH);
				level.setBlockAndUpdate(new BlockPos(4, y, 6), core);
				level.setBlockAndUpdate(new BlockPos(4, y + 1, 6), core);
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "tp @a 0.5 " + (y + 1) + " 1.5 0 15");
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 13000");
			});
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(20);
			ctx.takeScreenshot("claudecraft-animated");
			ctx.waitTicks(3);
			ctx.takeScreenshot("claudecraft-animated-next");
			ctx.waitTicks(3);
			ctx.takeScreenshot("claudecraft-animated-after");
		}
	}
}
