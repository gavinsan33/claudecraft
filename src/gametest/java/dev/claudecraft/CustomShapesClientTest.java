package dev.claudecraft;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;

/** A hammer whose right-click splits the ground with claude shape: an irregular crevasse, different every swing. */
public class CustomShapesClientTest implements FabricClientGameTest {
	private static final String CREVASSE = "claude shape minecraft:air 16 3 8 0 solid && f > 2 && abs(s - noise(f*0.18, seed)*2.5) < ((1-lf)*2.2+0.3)"
		+ "*(0.75+0.5*noise(f*0.5, seed+7)) && -u < (1 - abs(s - noise(f*0.18, seed)*2.5)/((1-lf)*2.2+0.3)) * 7 * (1 - lf*0.8)";

	/** The carved blocks in the stone field, as a set of positions relative to the player. */
	private static java.util.Set<BlockPos> holes(net.minecraft.server.level.ServerLevel level, BlockPos at) {
		java.util.Set<BlockPos> out = new java.util.HashSet<>();
		for (BlockPos p : BlockPos.betweenClosed(at.offset(-6, -9, 0), at.offset(6, -1, 18))) {
			if (level.getBlockState(p).isAir()) out.add(p.subtract(at).immutable());
		}
		return out;
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			BlockPos at = sp.getServer().computeOnServer(server -> {
				var hammer = JsonParser.parseString("""
					{"id":"quake_hammer","name":"Quake Hammer","style":"handheld","model":{"palette":{"g":"#777777"},"layers":[["g"]]},
					 "properties":{"use_commands":["%s"]}}""".formatted(CREVASSE)).getAsJsonObject();
				CustomItems.define(server, hammer);
				var player = server.getPlayerList().getPlayers().getFirst();
				var level = server.overworld();
				int y = level.getChunk(0, 0).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0) + 12;
				var src = server.createCommandSourceStack();
				server.getCommands().performPrefixedCommand(src, "fill -10 " + (y - 10) + " -4 10 " + (y - 1) + " 22 minecraft:stone");
				server.getCommands().performPrefixedCommand(src, "fill -10 " + y + " -4 10 " + (y + 6) + " 22 minecraft:air");
				server.getCommands().performPrefixedCommand(src, "time set noon");
				player.teleportTo(level, 0.5, y, 0.5, java.util.Set.of(), 0, 0, true); // facing south (+z)
				player.getInventory().setItem(player.getInventory().getSelectedSlot(), CustomItems.stack(hammer, 1));
				player.getMainHandItem().use(level, player, InteractionHand.MAIN_HAND);
				return BlockPos.containing(player.position());
			});
			ctx.waitTicks(40);
			var first = sp.getServer().computeOnServer(server -> holes(server.overworld(), at));
			if (first.size() < 40) throw new AssertionError("the crevasse should carve plenty of stone, got " + first.size());
			if (first.stream().noneMatch(p -> p.getZ() > 8)) throw new AssertionError("it should run forward (south) from the player");
			if (first.stream().anyMatch(p -> p.getZ() < -1)) throw new AssertionError("it shouldn't carve behind the player");
			long mirrored = first.stream().filter(p -> first.contains(new BlockPos(-p.getX(), p.getY(), p.getZ()))).count();
			if (mirrored == first.size()) throw new AssertionError("a noisy crevasse shouldn't be perfectly symmetric");
			int deepest = first.stream().mapToInt(BlockPos::getY).min().orElse(0);
			if (deepest > -4) throw new AssertionError("it should split deep into the ground, deepest " + deepest);
			if (first.stream().anyMatch(p -> p.getZ() < 2)) throw new AssertionError("it should start ahead of the player, not under them");
			sp.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
				.teleportTo(server.overworld(), 0.5, at.getY() + 9, -4.5, java.util.Set.of(), 0, 50, true));
			ctx.waitTicks(5);
			ctx.takeScreenshot("claudecraft-crevasse");

			// A second swing from the same spot: refill, swing, and it should come out different.
			var second = sp.getServer().computeOnServer(server -> {
				var level = server.overworld();
				for (BlockPos p : first) level.setBlockAndUpdate(at.offset(p), Blocks.STONE.defaultBlockState());
				var player = server.getPlayerList().getPlayers().getFirst();
				player.teleportTo(level, 0.5, at.getY(), 0.5, java.util.Set.of(), 0, 0, true);
				player.getMainHandItem().use(level, player, InteractionHand.MAIN_HAND);
				return true;
			});
			ctx.waitTicks(40);
			var again = sp.getServer().computeOnServer(server -> holes(server.overworld(), at));
			if (again.equals(first)) throw new AssertionError("each swing should split the ground differently");
			if (!second) throw new AssertionError();
		}
	}
}
