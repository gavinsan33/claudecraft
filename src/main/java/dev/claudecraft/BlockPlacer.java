package dev.claudecraft;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies a batch of block changes the way structure placement does: place everything first without
 * neighbor reactions, then let every block recompute its shape (fence connections, stair corners,
 * door halves, support checks). The result doesn't depend on placement order.
 */
public final class BlockPlacer {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

	private BlockPlacer() {}

	public static String apply(ServerLevel level, Map<BlockPos, BlockState> changes, UndoHistory undo) {
		List<BlockPos> placed = new ArrayList<>(changes.size());
		int unchanged = 0, outside = 0;

		for (Map.Entry<BlockPos, BlockState> e : changes.entrySet()) {
			BlockPos pos = e.getKey();
			BlockState state = e.getValue();
			if (!level.isInsideBuildHeight(pos.getY())) {
				outside++;
				continue;
			}
			BlockState old = level.getBlockState(pos);
			if (old == state) {
				unchanged++;
				continue;
			}
			if (undo != null) undo.record(level, pos, old);
			BlockEntity be = level.getBlockEntity(pos);
			if (be instanceof Clearable c) c.clearContent(); // don't spill chest contents everywhere
			level.setBlock(pos, state, FLAGS);
			placed.add(pos);
		}

		// Shape pass: connect fences/panes/stairs, and drop blocks that can't survive (e.g. half a door).
		List<String> removed = new ArrayList<>();
		for (BlockPos pos : placed) {
			BlockState st = level.getBlockState(pos);
			BlockState updated = Block.updateFromNeighbourShapes(st, level, pos);
			if (updated != st) {
				level.setBlock(pos, updated, FLAGS);
				if (updated.isAir() && !st.isAir()) {
					removed.add(BlockStateParser.serialize(st) + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
				}
			}
		}
		// Let blocks outside the batch react too (existing fences connecting to new walls, redstone, etc.).
		for (BlockPos pos : placed) {
			BlockState st = level.getBlockState(pos);
			st.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
			level.updateNeighborsAt(pos, st.getBlock());
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Changed ").append(placed.size()).append(" blocks");
		if (unchanged > 0) sb.append(", ").append(unchanged).append(" already matched");
		if (outside > 0) sb.append(", ").append(outside).append(" skipped (outside build height ")
			.append(level.getMinY()).append("..").append(level.getMaxY()).append(")");
		sb.append('.');
		if (!removed.isEmpty()) {
			sb.append("\nWARNING: ").append(removed.size())
				.append(" block(s) could not survive (missing support or missing other half) and became air:");
			for (int i = 0; i < Math.min(15, removed.size()); i++) sb.append("\n - ").append(removed.get(i));
			if (removed.size() > 15) sb.append("\n - ...");
		}
		return sb.toString();
	}
}
