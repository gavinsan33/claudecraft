package dev.claudecraft;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** claude explode: a crater with flying debris, harmless knockback, and sparing whoever set it off. */
public class ExplosionsClientTest implements FabricClientGameTest {
	private int floor;

	private static long count(net.minecraft.server.level.ServerLevel level, BlockPos a, BlockPos b, net.minecraft.world.level.block.Block block) {
		long n = 0;
		for (BlockPos p : BlockPos.betweenClosed(a, b)) if (level.getBlockState(p).is(block)) n++;
		return n;
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				floor = level.getChunk(0, 0).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0) + 20;
				var src = server.createCommandSourceStack();
				server.getCommands().performPrefixedCommand(src, "fill -30 " + (floor - 8) + " -30 30 " + (floor - 1) + " 30 minecraft:stone");
				server.getCommands().performPrefixedCommand(src, "fill -30 " + floor + " -30 30 " + (floor + 12) + " 30 minecraft:air");
				server.getCommands().performPrefixedCommand(src, "time set noon");
				server.getPlayerList().getPlayers().getFirst().teleportTo(level, 0.5, floor + 22, -12.5, java.util.Set.of(), 0, 55, true);
			});
			// Debris: a crater, and blocks flying out of it (still in the air a few ticks later, landing around it later).
			int thrown = sp.getServer().computeOnServer(s -> Explosions.explode(s.overworld(), new net.minecraft.world.phys.Vec3(0.5, floor, 0.5), 6, "debris", false, false, 1, true, null));
			ctx.waitTicks(4);
			int flying = sp.getServer().computeOnServer(s -> s.overworld().getEntitiesOfClass(FallingBlockEntity.class, new AABB(-40, -64, -40, 40, 320, 40)).size());
			ctx.takeScreenshot("claudecraft-explosion-debris");
			long crater = sp.getServer().computeOnServer(s -> count(s.overworld(), new BlockPos(-4, floor - 5, -4), new BlockPos(4, floor - 1, 4), Blocks.AIR));
			if (crater < 20) throw new AssertionError("the blast should leave a crater, got " + crater + " air blocks");
			if (thrown < 10 || flying < 5) throw new AssertionError("debris mode should throw blocks: " + thrown + " thrown, " + flying + " still flying");
			ctx.waitTicks(60);
			long landed = sp.getServer().computeOnServer(s -> count(s.overworld(), new BlockPos(-20, floor, -20), new BlockPos(20, floor + 3, 20), Blocks.STONE));
			if (landed < 3) throw new AssertionError("debris should land as blocks around the crater, got " + landed);

			// Harmless knockback, and the one who set it off is spared.
			String result = sp.getServer().computeOnServer(server -> {
				var level = server.overworld();
				server.getPlayerList().getPlayers().getFirst().teleportTo(level, 16.5, floor + 6, 10.5, java.util.Set.of(), 0, 30, true);
				var a = EntityTypes.PIG.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				var b = EntityTypes.PIG.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				a.snapTo(15.5, floor, 15.5, 0, 0);
				b.snapTo(17.5, floor, 15.5, 0, 0);
				for (var z : new net.minecraft.world.entity.Mob[]{a, b}) {
					z.setNoAi(true);
					level.addFreshEntity(z);
				}
				var src = server.createCommandSourceStack();
				long stoneBefore = count(level, new BlockPos(12, floor - 3, 12), new BlockPos(20, floor - 1, 20), Blocks.STONE);
				server.getCommands().performPrefixedCommand(src, "execute positioned 16.5 " + floor + " 15.5 run claude explode 4 keep harmless knockback=2");
				long stoneAfter = count(level, new BlockPos(12, floor - 3, 12), new BlockPos(20, floor - 1, 20), Blocks.STONE);
				StringBuilder bad = new StringBuilder();
				if (a.getHealth() < a.getMaxHealth() || b.getHealth() < b.getMaxHealth()) bad.append("harmless hurt something; ");
				if (a.getDeltaMovement().lengthSqr() < 0.01) bad.append("harmless should still knock back; ");
				if (stoneAfter != stoneBefore) bad.append("keep shouldn't break blocks; ");
				// Now one set off by zombie a: it's spared, b isn't.
				server.getCommands().performPrefixedCommand(src, "execute as " + a.getStringUUID() + " positioned 16.5 " + floor + " 15.5 run claude explode 3 keep");
				if (a.getHealth() < a.getMaxHealth()) bad.append("whoever sets it off should be spared; ");
				if (b.getHealth() >= b.getMaxHealth()) bad.append("others nearby should be hurt; ");
				return bad.toString();
			});
			if (!result.isEmpty()) throw new AssertionError(result);

		}
	}
}
