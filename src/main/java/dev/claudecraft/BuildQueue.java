package dev.claudecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Runs BuildJobs one after another, a slice per server tick. Only touch it from the server thread. */
public class BuildQueue {
	private final Config cfg;
	private final Deque<BuildJob> jobs = new ArrayDeque<>();

	public BuildQueue(Config cfg) {
		this.cfg = cfg;
	}

	public CompletableFuture<String> enqueue(ServerLevel level, Map<BlockPos, BlockState> changes, UndoHistory undo, String viewer) {
		int perTick;
		if (!cfg.animateBuilds) {
			perTick = Integer.MAX_VALUE;
		} else {
			// Small builds go at a watchable pace; big ones speed up so none takes longer than maxBuildSeconds.
			int ticks = Math.max(1, cfg.maxBuildSeconds * 20);
			perTick = Math.max(cfg.buildBlocksPerTick, (changes.size() + ticks - 1) / ticks);
		}
		BuildJob job = new BuildJob(level, changes, undo, perTick, cfg.animateBuilds && cfg.buildEffects, viewer);
		jobs.add(job);
		return job.done;
	}

	public void tick() {
		BuildJob job = jobs.peek();
		if (job == null) return;
		if (job.size() == 0 || !cfg.animateBuilds) {
			job.finish(null);
		} else {
			job.tick(Integer.MAX_VALUE);
			if (job.isFinished()) job.finish(null);
		}
		if (job.done.isDone()) jobs.poll();
	}

	public boolean isBusy() {
		return !jobs.isEmpty();
	}

	/** Stop building: the current job keeps what it has placed so far, and queued jobs are dropped. */
	public int cancelAll() {
		int n = jobs.size();
		BuildJob first = jobs.poll();
		if (first != null) first.abort("Stopped early by a player.");
		for (BuildJob j : jobs) j.abort("Cancelled before it started.");
		jobs.clear();
		return n;
	}

	/** Finish everything instantly (server shutting down). */
	public void flushAll() {
		for (BuildJob j : jobs) j.finish(null);
		jobs.clear();
	}
}
