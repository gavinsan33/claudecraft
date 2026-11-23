package dev.claudecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Runs BuildJobs one after another, a slice per server tick. Only touch it from the server thread,
 * except {@link #idle()}, which any thread may call.
 */
public class BuildQueue {
	private final Config cfg;
	private final Deque<BuildJob> jobs = new ArrayDeque<>();
	/** Jobs run in order, so the newest job finishing means the queue is empty. */
	private volatile CompletableFuture<String> last;

	public BuildQueue(Config cfg) {
		this.cfg = cfg;
	}

	/**
	 * @param seconds how long the animation should take; 0 or less uses the configured pacing
	 * @param onFinish gets the result before the returned future (or {@link #idle()}) completes, may be null
	 * @param mask only place blocks where this holds at placement time, may be null
	 */
	public CompletableFuture<String> enqueue(ServerLevel level, Map<BlockPos, BlockState> changes, UndoHistory undo, double seconds,
			java.util.function.Consumer<String> onFinish, java.util.function.Predicate<BlockPos> mask) {
		double perTick;
		if (!cfg.animateBuilds) {
			perTick = Integer.MAX_VALUE;
		} else if (seconds > 0) {
			perTick = changes.size() / (Math.min(seconds, 120) * 20);
		} else {
			// Small builds go at a watchable pace; big ones speed up so none takes longer than maxBuildSeconds.
			int ticks = Math.max(1, cfg.maxBuildSeconds * 20);
			perTick = Math.max(cfg.buildBlocksPerTick, (changes.size() + ticks - 1) / ticks);
		}
		BuildJob job = new BuildJob(level, changes, undo, perTick, cfg.animateBuilds && cfg.buildEffects, onFinish, mask);
		jobs.add(job);
		last = job.done;
		return job.done;
	}

	public void tick() {
		BuildJob job = jobs.peek();
		if (job == null) return;
		if (job.size() == 0 || !cfg.animateBuilds) {
			job.finish(null);
		} else {
			job.tick();
			if (job.isFinished()) job.finish(null);
		}
		if (job.done.isDone()) jobs.poll();
	}

	public boolean isBusy() {
		return !jobs.isEmpty();
	}

	public int blocksLeft() {
		return jobs.stream().mapToInt(BuildJob::remaining).sum();
	}

	public int secondsLeft() {
		return (int) Math.ceil(jobs.stream().mapToDouble(BuildJob::ticksLeft).sum() / 20);
	}

	/** Completes once everything queued so far has been placed (or cancelled). */
	public CompletableFuture<Void> idle() {
		CompletableFuture<String> l = last;
		return l == null ? CompletableFuture.completedFuture(null) : l.handle((r, e) -> null);
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
