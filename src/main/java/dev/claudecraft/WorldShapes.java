package dev.claudecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * claude shape: a math-shaped change to the world, in front of whoever runs it, for items', blocks' and creatures'
 * commands (a hammer that splits the ground, a staff that raises a jagged wall, a meteor's crater). The expression is
 * true where blocks change, over the facing-aligned box f (blocks forward), s (blocks to the right), u (blocks up),
 * with noise() for natural edges and a fresh seed each time, so no two look the same. It rips outward from the start
 * over a moment with block sounds and particles, separately from Claude's own builds, and isn't part of undo. It never
 * changes unbreakable blocks or blocks that hold items.
 */
final class WorldShapes {
	private WorldShapes() {}

	static final List<String> VARS = List.of("f", "s", "u", "d", "seed", "solid", "air", "lf", "ls", "lu");
	static final int MAX_SIDE = 48, MAX_BLOCKS = 20000;

	private static final List<BuildJob> JOBS = new ArrayList<>();

	/** Animate every running shape a step. Server thread. */
	static void tick() {
		for (Iterator<BuildJob> it = JOBS.iterator(); it.hasNext(); ) {
			BuildJob job = it.next();
			if (job.size() == 0) job.finish(null);
			else {
				job.tick();
				if (job.isFinished()) job.finish(null);
			}
			if (job.done.isDone()) it.remove();
		}
	}

	/**
	 * Change the blocks where expr holds in the box forward 0..forward, s -side..side, u -down..up from origin,
	 * facing yaw (degrees). Returns how many blocks change. Server thread.
	 */
	static int shape(ServerLevel level, Vec3 origin, float yaw, GameTools.Pattern block, int forward, int side, int down, int up, Shapes.Expr expr) {
		double rad = Math.toRadians(yaw);
		double fx = -Math.sin(rad), fz = Math.cos(rad), rx = -Math.cos(rad), rz = -Math.sin(rad); // forward, and to the right
		BlockPos o = BlockPos.containing(origin);
		int reach = (int) Math.ceil(Math.hypot(forward, side)) + 1;
		double[] v = new double[VARS.size()];
		v[4] = ThreadLocalRandom.current().nextDouble() * 1000;
		Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
		List<BlockPos> hits = new ArrayList<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dy = -down; dy <= up; dy++) {
			for (int dx = -reach; dx <= reach; dx++) {
				for (int dz = -reach; dz <= reach; dz++) {
					double f = dx * fx + dz * fz, s = dx * rx + dz * rz;
					if (f < -0.5 || f > forward + 0.5 || Math.abs(s) > side + 0.5) continue;
					p.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
					BlockState here = level.getBlockState(p);
					// Never bedrock and other unbreakables, or anything holding items (a chest's contents would vanish).
					if (here.getDestroySpeed(level, p) < 0 || here.hasBlockEntity()) continue;
					v[0] = f;
					v[1] = s;
					v[2] = dy;
					v[3] = Math.sqrt(f * f + s * s + dy * dy);
					v[5] = here.isAir() || here.canBeReplaced() ? 0 : 1;
					v[6] = here.isAir() ? 1 : 0;
					v[7] = forward == 0 ? 0 : f / forward; // 0 to 1 along it
					v[8] = side == 0 ? 0 : s / side;      // -1 to 1 across it
					v[9] = dy >= 0 ? (up == 0 ? 0 : (double) dy / up) : (down == 0 ? 0 : (double) dy / down); // -1 to 1
					double r = expr.eval(v);
					if (r == 0 || Double.isNaN(r)) continue;
					hits.add(p.immutable());
					if (hits.size() > MAX_BLOCKS) return -1;
				}
			}
		}
		// Rip outward from the start: nearest first.
		hits.sort(Comparator.comparingDouble(q -> q.distSqr(o)));
		for (BlockPos q : hits) changes.put(q, block.pick());
		double seconds = Math.min(1.5, 0.25 + hits.size() / 1500.0);
		BuildJob job = new BuildJob(level, changes, null, Math.max(1, hits.size() / (seconds * 20)), true, null, null);
		JOBS.add(job);
		return hits.size();
	}
}
