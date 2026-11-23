package dev.claudecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Remembers the original block states Claude overwrote, grouped per chat turn. */
public class UndoHistory {
	private static final int MAX_ENTRIES = 50;

	public static final class Entry {
		public final Map<ResourceKey<Level>, LinkedHashMap<BlockPos, BlockState>> originals = new LinkedHashMap<>();

		int size() {
			return originals.values().stream().mapToInt(Map::size).sum();
		}
	}

	private final Deque<Entry> stack = new ArrayDeque<>();
	private Entry current;

	public synchronized void record(ServerLevel level, BlockPos pos, BlockState original) {
		if (current == null) current = new Entry();
		current.originals.computeIfAbsent(level.dimension(), k -> new LinkedHashMap<>()).putIfAbsent(pos.immutable(), original);
	}

	/** Seal the changes made so far into one undo step. */
	public synchronized void commit() {
		if (current != null && current.size() > 0) {
			stack.push(current);
			while (stack.size() > MAX_ENTRIES) stack.removeLast();
		}
		current = null;
	}

	public synchronized List<Entry> pop(int steps) {
		commit();
		List<Entry> out = new ArrayList<>();
		for (int i = 0; i < steps && !stack.isEmpty(); i++) out.add(stack.pop());
		return out;
	}

	public synchronized int depth() {
		return stack.size() + (current != null && current.size() > 0 ? 1 : 0);
	}

	public synchronized void clear() {
		stack.clear();
		current = null;
	}
}
