package dev.claudecraft;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * One batch of block changes, placed a few blocks per tick so players can watch it go up.
 * Clearing (changes to air) happens first, top-down; then building goes bottom-up, sweeping each layer
 * row by row. Each placed block plays its own sound and gives off a puff of particles.
 *
 * Shapes are handled in two stages. While animating, blocks connect to already-placed neighbors
 * (fences, panes, stairs), but nothing is removed for lacking support yet, because its support may
 * simply not have been placed. Once every block is down, a final pass does the full structure-style
 * shape update and reports anything that couldn't survive.
 */
public final class BuildJob {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
	private static final int MAX_SOUNDS_PER_TICK = 2;
	private static final int MAX_PARTICLE_BURSTS_PER_TICK = 6;

	private final ServerLevel level;
	private final Map<BlockPos, BlockState> targets;
	private final List<BlockPos> order;
	private final UndoHistory undo;
	private final String viewer;
	private final boolean effects;
	private final int rate;
	final CompletableFuture<String> done = new CompletableFuture<>();

	private final List<BlockPos> placed = new ArrayList<>();
	private final Set<BlockPos> placedSet = new HashSet<>();
	private int index, unchanged, outside, ticks;
	private boolean rushing;

	/**
	 * @param blocksPerTick placement speed; Integer.MAX_VALUE places everything in one tick
	 * @param viewer player who sees the progress in their action bar, may be null
	 */
	public BuildJob(ServerLevel level, Map<BlockPos, BlockState> targets, UndoHistory undo, int blocksPerTick, boolean effects, String viewer) {
		this.level = level;
		this.targets = targets;
		this.undo = undo;
		this.rate = Math.max(1, blocksPerTick);
		this.effects = effects;
		this.viewer = viewer;
		this.order = buildOrder(targets);
	}

	/** Removals top-down, then placements bottom-up; within a layer, a back-and-forth row sweep. */
	private static List<BlockPos> buildOrder(Map<BlockPos, BlockState> targets) {
		List<BlockPos> removals = new ArrayList<>();
		List<BlockPos> placements = new ArrayList<>();
		for (var e : targets.entrySet()) (e.getValue().isAir() ? removals : placements).add(e.getKey());
		Comparator<BlockPos> sweep = Comparator.<BlockPos>comparingInt(BlockPos::getZ)
			.thenComparingInt(p -> (p.getZ() & 1) == 0 ? p.getX() : -p.getX());
		removals.sort(Comparator.<BlockPos>comparingInt(p -> -p.getY()).thenComparing(sweep));
		placements.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparing(sweep));
		removals.addAll(placements);
		return removals;
	}

	public boolean isFinished() {
		return index >= order.size();
	}

	public int size() {
		return order.size();
	}

	/** Place up to {@code rate} blocks (or {@code budget}, if smaller). Runs on the server thread. */
	public void tick(int budget) {
		if (!rushing) ticks++;
		int limit = rushing ? Integer.MAX_VALUE : Math.min(rate, budget);
		List<BlockPos> thisTick = new ArrayList<>();
		List<BlockState> oldStates = new ArrayList<>();
		while (index < order.size() && thisTick.size() < limit) {
			BlockPos pos = order.get(index++);
			BlockState state = targets.get(pos);
			if (!level.isInsideBuildHeight(pos.getY())) {
				outside++;
				continue;
			}
			BlockState old = level.getBlockState(pos);
			if (old == state) {
				unchanged++; // free: doesn't use this tick's budget
				continue;
			}
			if (undo != null) undo.record(level, pos, old);
			BlockEntity be = level.getBlockEntity(pos);
			if (be instanceof Clearable c) c.clearContent(); // don't spill chest contents everywhere
			level.setBlock(pos, state, FLAGS);
			placed.add(pos);
			placedSet.add(pos);
			thisTick.add(pos);
			oldStates.add(old);
			connectLive(pos);
		}
		if (effects && !rushing && !thisTick.isEmpty()) playEffects(thisTick, oldStates);
		if (viewer != null && !rushing && ticks % 10 == 0 && !isFinished()) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayerByName(viewer);
			if (p != null) {
				int pct = (int) (100L * index / Math.max(1, order.size()));
				p.sendSystemMessage(Component.literal("Claude is building... " + pct + "%").withStyle(ChatFormatting.GRAY), true);
			}
		}
	}

	/** Update the shape of a newly placed block and its placed neighbors, but never remove anything yet. */
	private void connectLive(BlockPos pos) {
		reshapeKeepingBlock(pos);
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			if (placedSet.contains(n)) reshapeKeepingBlock(n);
		}
	}

	private void reshapeKeepingBlock(BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		BlockState updated = Block.updateFromNeighbourShapes(st, level, pos);
		if (updated != st && !updated.isAir()) level.setBlock(pos, updated, FLAGS);
	}

	private void playEffects(List<BlockPos> positions, List<BlockState> oldStates) {
		int n = positions.size();
		int sounds = Math.min(MAX_SOUNDS_PER_TICK, n);
		for (int i = 0; i < sounds; i++) {
			int k = (int) ((long) i * n / sounds); // spread across this tick's blocks
			BlockPos pos = positions.get(k);
			BlockState now = level.getBlockState(pos);
			boolean removed = now.isAir();
			BlockState source = removed ? oldStates.get(k) : now;
			if (source.isAir()) continue;
			SoundType st = source.getSoundType();
			float pitch = st.getPitch() * (0.8f + level.getRandom().nextFloat() * 0.3f);
			level.playSound(null, pos, removed ? st.getBreakSound() : st.getPlaceSound(), SoundSource.BLOCKS,
				(st.getVolume() + 1.0f) / 2.0f, pitch);
		}
		int bursts = Math.min(MAX_PARTICLE_BURSTS_PER_TICK, n);
		for (int i = 0; i < bursts; i++) {
			int k = (int) ((long) i * n / bursts);
			BlockPos pos = positions.get(k);
			BlockState now = level.getBlockState(pos);
			BlockState source = now.isAir() ? oldStates.get(k) : now;
			if (source.isAir()) continue;
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, source),
				pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.05);
		}
	}

	/** Place whatever is left immediately, run the final shape pass, and complete the future. */
	public void finish(String note) {
		rushing = true;
		while (!isFinished()) tick(Integer.MAX_VALUE);
		complete(note);
	}

	/** Stop where we are: finalize the blocks placed so far and complete the future. */
	public void abort(String note) {
		index = order.size();
		complete(note);
	}

	private void complete(String note) {
		// Full shape pass: connect everything, drop blocks that can't survive (e.g. half a door).
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
		if (note != null) sb.append(note).append(' ');
		sb.append("Changed ").append(placed.size()).append(" blocks");
		if (unchanged > 0) sb.append(", ").append(unchanged).append(" already matched");
		if (outside > 0) sb.append(", ").append(outside).append(" skipped (outside build height ")
			.append(level.getMinY()).append("..").append(level.getMaxY()).append(")");
		if (ticks > 1) sb.append(String.format(java.util.Locale.ROOT, " (animated over %.1fs)", ticks / 20.0));
		sb.append('.');
		if (!removed.isEmpty()) {
			sb.append("\nWARNING: ").append(removed.size())
				.append(" block(s) could not survive (missing support or missing other half) and became air:");
			for (int i = 0; i < Math.min(15, removed.size()); i++) sb.append("\n - ").append(removed.get(i));
			if (removed.size() > 15) sb.append("\n - ...");
		}
		done.complete(sb.toString());
	}
}
