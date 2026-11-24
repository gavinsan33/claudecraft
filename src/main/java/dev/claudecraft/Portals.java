package dev.claudecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Custom blocks with a "portal" property work like nether portals: stand in one and it takes you to its
 * dimension, or back to the overworld if you're already there. Vanilla does the waiting, the swirling screen and
 * the cooldown (CustomBlock implements Portal); this works out where you arrive.
 *
 * You arrive beside a portal of the same design near the same x and z on the other side. If there isn't one, a
 * copy of the portal you walked through, frame and all, is built there, so there's always a way back. Built
 * portals break like nether portals: break a frame block or any pane and every pane goes. Portals players build
 * themselves are left alone.
 */
final class Portals {
	private Portals() {}

	/** How far (in x and z) from the entry point to look for a portal to arrive at. */
	static final int SEARCH = 16;
	/** Portals bigger than this aren't copied whole. */
	private static final int MAX_BLOCKS = 512, MAX_SPAN = 32;

	record Arrival(Vec3 pos, float yaw) {}

	// ------------------------------------------------------------ built portals breaking together

	/** Panes of built portals, per dimension: position (BlockPos.asLong) to the axis the portal is thin along. */
	private static Map<String, Map<Long, String>> built;
	private static MinecraftServer builtFor;

	private static Path builtFile(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("claudecraft").resolve("built_portals.json");
	}

	private static Map<Long, String> built(ServerLevel level) {
		MinecraftServer server = level.getServer();
		if (builtFor != server) {
			builtFor = server;
			built = new HashMap<>();
			try {
				Path f = builtFile(server);
				if (Files.exists(f)) {
					for (var dim : JsonParser.parseString(Files.readString(f)).getAsJsonObject().entrySet()) {
						Map<Long, String> panes = new HashMap<>();
						for (var e : dim.getValue().getAsJsonObject().entrySet()) panes.put(Long.parseLong(e.getKey()), e.getValue().getAsString());
						built.put(dim.getKey(), panes);
					}
				}
			} catch (Exception e) {
				ClaudeCraft.LOGGER.error("Couldn't read built portals", e);
			}
		}
		return built.computeIfAbsent(level.dimension().identifier().toString(), k -> new HashMap<>());
	}

	// ponytail: rewrites the whole file per change; fine while portals number in the dozens, SavedData if it grows.
	private static void saveBuilt(MinecraftServer server) {
		JsonObject all = new JsonObject();
		built.forEach((dim, panes) -> {
			JsonObject o = new JsonObject();
			panes.forEach((pos, axis) -> o.addProperty(Long.toString(pos), axis));
			all.add(dim, o);
		});
		try {
			Files.createDirectories(builtFile(server).getParent());
			Files.writeString(builtFile(server), all.toString());
		} catch (Exception e) {
			ClaudeCraft.LOGGER.error("Couldn't save built portals", e);
		}
	}

	/**
	 * Whether a built portal's pane should vanish now that its neighbor in `dir` became `neighbor`: a frame block or
	 * pane beside it in the portal's plane is gone. Each pane that goes updates the next, so the whole portal goes.
	 */
	static boolean breaks(ServerLevel level, BlockPos pos, BlockState state, Direction dir, BlockState neighbor) {
		String thin = built(level).get(pos.asLong());
		if (thin == null || dir.getAxis().getName().equals(thin)) return false;
		if (matches(neighbor, state.getValue(CustomBlocks.VARIANT))) return false;
		return neighbor.isAir() || neighbor.canBeReplaced();
	}

	/** Stop treating pos as a built pane once its block is gone. */
	static void forget(ServerLevel level, BlockPos pos, int variant) {
		if (matches(level.getBlockState(pos), variant)) return;
		if (built(level).remove(pos.asLong()) != null) saveBuilt(level.getServer());
	}

	/** Where an entity standing in the portal block at `entry` goes, or null if it goes nowhere. Server thread. */
	static TeleportTransition destination(ServerLevel level, Entity entity, BlockPos entry) {
		BlockState state = level.getBlockState(entry);
		JsonObject def = CustomBlocks.definition(state);
		String target = def == null ? null : CustomBlocks.portalTarget(def);
		if (target == null) return null;
		MinecraftServer server = level.getServer();
		ServerLevel there = Dimensions.level(server, target);
		if (there == null) return null; // the dimension was deleted
		ServerLevel dest = there != level ? there : level.dimension() != Level.OVERWORLD ? server.overworld() : null;
		if (dest == null) return null;
		int variant = state.getValue(CustomBlocks.VARIANT);
		BlockPos found = find(dest, variant, entry);
		Arrival a = found != null ? beside(dest, variant, found, entity.getYRot()) : build(level, variant, entry, dest);
		return new TeleportTransition(dest, a.pos(), Vec3.ZERO, a.yaw(), entity.getXRot(),
			TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET));
	}

	private static boolean matches(BlockState st, int variant) {
		return st.is(CustomBlocks.BLOCK) && st.getValue(CustomBlocks.VARIANT) == variant;
	}

	/** The nearest block of this design within SEARCH of `near`'s column, at any height. Generates those chunks. */
	static BlockPos find(ServerLevel level, int variant, BlockPos near) {
		BlockPos best = null;
		long bestDist = Long.MAX_VALUE;
		for (int cx = (near.getX() - SEARCH) >> 4; cx <= (near.getX() + SEARCH) >> 4; cx++) {
			for (int cz = (near.getZ() - SEARCH) >> 4; cz <= (near.getZ() + SEARCH) >> 4; cz++) {
				LevelChunk chunk = level.getChunk(cx, cz);
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection sec = sections[i];
					if (sec.hasOnlyAir() || !sec.maybeHas(st -> matches(st, variant))) continue;
					int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
					for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
						if (!matches(sec.getBlockState(x, y, z), variant)) continue;
						long dx = cx * 16L + x - near.getX(), dy = baseY + y - near.getY(), dz = cz * 16L + z - near.getZ();
						if (Math.abs(dx) > SEARCH || Math.abs(dz) > SEARCH) continue;
						long d = dx * dx + dz * dz + dy * dy / 4; // height matters less than distance across
						if (d < bestDist) {
							bestDist = d;
							best = new BlockPos(cx * 16 + x, baseY + y, cz * 16 + z);
						}
					}
				}
			}
		}
		return best;
	}

	/** A spot to stand next to the bottom of the portal containing p, facing it; inside it if there's no room. */
	static Arrival beside(ServerLevel level, int variant, BlockPos p, float yaw) {
		BlockPos bottom = p;
		for (int i = 0; i < 64 && matches(level.getBlockState(bottom.below()), variant); i++) bottom = bottom.below();
		for (int dist = 1; dist <= 2; dist++) {
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos feet = bottom.relative(d, dist);
				if (!matches(level.getBlockState(feet), variant) && Dimensions.standable(level, feet)) {
					return new Arrival(Vec3.atBottomCenterOf(feet), d.getOpposite().toYRot());
				}
			}
		}
		return new Arrival(Vec3.atBottomCenterOf(bottom), yaw);
	}

	/**
	 * Copy the portal at `entry` (its connected blocks of the same design, and the frame around them in the
	 * portal's plane) to a safe spot near the same x and z in `dest`, standing two blocks in front of where the
	 * traveller arrives.
	 */
	static Arrival build(ServerLevel src, int variant, BlockPos entry, ServerLevel dest) {
		Set<BlockPos> portal = connected(src, variant, entry);
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos q : portal) {
			minX = Math.min(minX, q.getX());
			minY = Math.min(minY, q.getY());
			minZ = Math.min(minZ, q.getZ());
			maxX = Math.max(maxX, q.getX());
			maxY = Math.max(maxY, q.getY());
			maxZ = Math.max(maxZ, q.getZ());
		}
		int ex = maxX - minX + 1, ey = maxY - minY + 1, ez = maxZ - minZ + 1;
		// The axis the portal is thin along: you walk through it that way. A lone block counts as facing z.
		Direction.Axis thin = ez <= ex && ez <= ey ? Direction.Axis.Z : ex <= ey ? Direction.Axis.X : Direction.Axis.Y;

		// The frame: whatever solid is next to the portal in its own plane.
		Map<BlockPos, BlockState> copy = new HashMap<>();
		int gx = thin == Direction.Axis.X ? 0 : 1, gy = thin == Direction.Axis.Y ? 0 : 1, gz = thin == Direction.Axis.Z ? 0 : 1;
		for (int x = minX - gx; x <= maxX + gx; x++) for (int y = minY - gy; y <= maxY + gy; y++) for (int z = minZ - gz; z <= maxZ + gz; z++) {
			BlockPos q = new BlockPos(x, y, z);
			BlockState st = src.getBlockState(q);
			if (portal.contains(q) || !st.isAir() && !st.canBeReplaced()) copy.put(q, st);
		}

		Vec3 at = Dimensions.safeSpot(dest, entry.getX(), entry.getZ());
		BlockPos feet = BlockPos.containing(at);
		BlockPos shift;
		Direction forward;
		switch (thin) {
			case Z -> { // standing up, facing north-south: put it two blocks south of the traveller
				forward = Direction.SOUTH;
				shift = new BlockPos(feet.getX() - (minX + maxX) / 2, feet.getY() - minY, feet.getZ() + 2 - minZ);
			}
			case X -> {
				forward = Direction.EAST;
				shift = new BlockPos(feet.getX() + 2 - minX, feet.getY() - minY, feet.getZ() - (minZ + maxZ) / 2);
			}
			default -> { // lying flat like a pool: set into the ground just east of the traveller
				forward = Direction.EAST;
				shift = new BlockPos(feet.getX() + 2 - minX, feet.getY() - 1 - minY, feet.getZ() - (minZ + maxZ) / 2);
			}
		}
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
		for (var e : copy.entrySet()) dest.setBlock(e.getKey().offset(shift), e.getValue(), flags);
		// Clear the ground in front of and behind the portal, so it isn't buried in a hillside.
		for (BlockPos q : portal) {
			BlockPos placed = q.offset(shift);
			for (Direction d : thin == Direction.Axis.Y ? new Direction[]{Direction.UP} : new Direction[]{forward, forward.getOpposite()}) {
				BlockPos side = placed.relative(d);
				if (!copy.containsKey(side.subtract(shift)) && !dest.getBlockState(side).isAir()) dest.setBlock(side, Blocks.AIR.defaultBlockState(), flags);
			}
		}
		Map<Long, String> panes = built(dest);
		for (BlockPos q : portal) panes.put(q.offset(shift).asLong(), thin.getName());
		saveBuilt(dest.getServer());
		return new Arrival(at, forward.toYRot());
	}

	/** The blocks of this design touching `start`, as one portal. */
	private static Set<BlockPos> connected(ServerLevel level, int variant, BlockPos start) {
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> todo = new ArrayDeque<>();
		seen.add(start.immutable());
		todo.add(start.immutable());
		while (!todo.isEmpty() && seen.size() < MAX_BLOCKS) {
			BlockPos p = todo.poll();
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (Math.abs(n.getX() - start.getX()) > MAX_SPAN || Math.abs(n.getY() - start.getY()) > MAX_SPAN || Math.abs(n.getZ() - start.getZ()) > MAX_SPAN) continue;
				if (!seen.contains(n) && matches(level.getBlockState(n), variant)) {
					seen.add(n);
					todo.add(n);
				}
			}
		}
		return seen;
	}
}
