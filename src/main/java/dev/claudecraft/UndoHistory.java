package dev.claudecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers the original block states Claude overwrote, grouped per chat turn. Each concurrent Claude session
 * collects its own turn's changes, so two sessions working at once still get separate undo steps.
 */
public class UndoHistory {
	private static final int MAX_ENTRIES = 50;

	public static final class Entry {
		public final Map<ResourceKey<Level>, LinkedHashMap<BlockPos, BlockState>> originals = new LinkedHashMap<>();

		int size() {
			return originals.values().stream().mapToInt(Map::size).sum();
		}
	}

	/** Where a build records what it overwrote. */
	@FunctionalInterface
	public interface Recorder {
		void record(ServerLevel level, BlockPos pos, BlockState original);
	}

	private final Deque<Entry> stack = new ArrayDeque<>();
	/** The in-progress turn of each Claude session. */
	private final Map<Integer, Entry> current = new HashMap<>();

	public Recorder recorder(int session) {
		return (level, pos, original) -> record(session, level, pos, original);
	}

	public synchronized void record(int session, ServerLevel level, BlockPos pos, BlockState original) {
		current.computeIfAbsent(session, k -> new Entry())
			.originals.computeIfAbsent(level.dimension(), k -> new LinkedHashMap<>()).putIfAbsent(pos.immutable(), original);
	}

	/** Seal a session's changes so far into one undo step. */
	public synchronized void commit(int session) {
		Entry e = current.remove(session);
		if (e != null && e.size() > 0) {
			stack.push(e);
			while (stack.size() > MAX_ENTRIES) stack.removeLast();
		}
	}

	/**
	 * @param session whose in-progress changes to seal first; -1 seals every session's (a player running /claude undo)
	 */
	public synchronized List<Entry> pop(int steps, int session) {
		if (session >= 0) commit(session);
		else for (Integer s : new ArrayList<>(current.keySet())) commit(s);
		List<Entry> out = new ArrayList<>();
		for (int i = 0; i < steps && !stack.isEmpty(); i++) out.add(stack.pop());
		return out;
	}

	public synchronized int depth() {
		return stack.size() + (int) current.values().stream().filter(e -> e.size() > 0).count();
	}

	public synchronized void clear() {
		stack.clear();
		current.clear();
	}
}
