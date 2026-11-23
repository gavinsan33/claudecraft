package dev.claudecraft;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom ores in the world. Worldgen features are fixed when the world loads, so instead each loaded chunk near a
 * player gets a custom block's veins once, the first time it's seen after the block is defined (new chunks and
 * old ones alike), and the chunk remembers which ores it has (a saved attachment). Veins only replace the
 * blocks they're told to (stone and deepslate by default), so they stay hidden underground like vanilla ores.
 *
 * generate: { replace: ["minecraft:stone", "#minecraft:deepslate_ore_replaceables"], min_y: -64, max_y: 64,
 *   veins_per_chunk: 6, vein_size: 6, biomes: [...], dimension: "minecraft:overworld" }
 */
final class OreGeneration {
	private OreGeneration() {}

	/** Ore keys (id plus a hash of its settings) already generated in a chunk. */
	static final AttachmentType<List<String>> DONE = AttachmentRegistry.create(CustomItems.id("custom_ores"),
		b -> b.persistent(Codec.STRING.listOf()).initializer(List::of));

	private static final int RADIUS = 6, BUDGET = 4, INTERVAL = 5;

	/** Called at startup so the attachment type is registered (by this class's initializer) before worlds load. */
	static void register() {
		ClaudeCraft.LOGGER.debug("Registered {}", DONE);
	}

	static void tick(MinecraftServer server) {
		if (server.getTickCount() % INTERVAL != 0) return;
		List<JsonObject> ores = new ArrayList<>();
		for (JsonObject def : CustomItems.DEFS.values()) {
			if (CustomBlocks.isBlock(def) && CustomItems.props(def).has("generate")) ores.add(def);
		}
		if (ores.isEmpty()) return;
		int budget = BUDGET;
		for (ServerLevel level : server.getAllLevels()) {
			List<JsonObject> here = new ArrayList<>();
			for (JsonObject def : ores) if (dimension(def).equals(level.dimension().identifier().toString())) here.add(def);
			if (here.isEmpty()) continue;
			for (ServerPlayer p : level.players()) {
				int pcx = p.blockPosition().getX() >> 4, pcz = p.blockPosition().getZ() >> 4;
				// Nearest chunks first, so ores appear around the player before farther away.
				for (int r = 0; r <= RADIUS && budget > 0; r++) {
					for (int dx = -r; dx <= r && budget > 0; dx++) for (int dz = -r; dz <= r && budget > 0; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
						LevelChunk chunk = level.getChunkSource().getChunkNow(pcx + dx, pcz + dz);
						if (chunk != null && populate(level, chunk, here)) budget--;
					}
				}
			}
		}
	}

	private static JsonObject spec(JsonObject def) {
		JsonObject p = CustomItems.props(def);
		return p.get("generate").isJsonObject() ? p.getAsJsonObject("generate") : new JsonObject();
	}

	private static String dimension(JsonObject def) {
		JsonObject s = spec(def);
		String d = s.has("dimension") ? s.get("dimension").getAsString() : "minecraft:overworld";
		return d.contains(":") ? d : "minecraft:" + d;
	}

	private static String key(JsonObject def) {
		return def.get("id").getAsString() + "@" + Integer.toHexString(spec(def).toString().hashCode());
	}

	/** Generate whichever of these ores the chunk doesn't have yet. Returns whether it did any work. */
	static boolean populate(ServerLevel level, LevelChunk chunk, List<JsonObject> ores) {
		List<String> done = chunk.getAttachedOrElse(DONE, List.of());
		List<String> now = null;
		for (JsonObject def : ores) {
			String key = key(def);
			if (done.contains(key)) continue;
			if (now == null) now = new ArrayList<>(done);
			now.add(key);
			veins(level, chunk, def);
		}
		if (now == null) return false;
		chunk.setAttached(DONE, List.copyOf(now));
		chunk.markUnsaved();
		return true;
	}

	private static void veins(ServerLevel level, LevelChunk chunk, JsonObject def) {
		JsonObject s = spec(def);
		BlockState ore = CustomBlocks.state(def);
		List<String> replace = CustomItems.strings(s, "replace");
		if (replace.isEmpty()) replace = List.of("#minecraft:stone_ore_replaceables", "#minecraft:deepslate_ore_replaceables");
		List<String> biomes = CustomItems.strings(s, "biomes");
		int minY = Math.max(level.getMinY(), s.has("min_y") ? s.get("min_y").getAsInt() : -64);
		int maxY = Math.min(level.getMaxY(), s.has("max_y") ? s.get("max_y").getAsInt() : 64);
		if (maxY < minY) return;
		int veins = Math.max(0, Math.min(64, s.has("veins_per_chunk") ? s.get("veins_per_chunk").getAsInt() : 6));
		int size = Math.max(1, Math.min(32, s.has("vein_size") ? s.get("vein_size").getAsInt() : 6));
		// The same chunk gets the same veins, whenever it's populated.
		RandomSource random = RandomSource.create(level.getSeed() ^ chunk.getPos().pack() * 31 ^ def.get("id").getAsString().hashCode());
		Direction[] dirs = Direction.values();
		for (int v = 0; v < veins; v++) {
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(chunk.getPos().getMinBlockX() + random.nextInt(16),
				minY + random.nextInt(maxY - minY + 1), chunk.getPos().getMinBlockZ() + random.nextInt(16));
			if (!biomes.isEmpty() && !CreatureSpawning.biomeMatches(level.getBiome(pos), biomes)) continue;
			for (int i = 0; i < size; i++) {
				if ((pos.getX() >> 4) == chunk.getPos().x() && (pos.getZ() >> 4) == chunk.getPos().z() && replaceable(chunk.getBlockState(pos), replace)) {
					level.setBlock(pos, ore, Block.UPDATE_CLIENTS);
				}
				pos.move(dirs[random.nextInt(dirs.length)]);
			}
		}
	}

	private static boolean replaceable(BlockState st, List<String> replace) {
		for (String r : replace) {
			if (r.startsWith("#")) {
				Identifier tag = Identifier.tryParse(r.substring(1));
				if (tag != null && st.is(TagKey.create(net.minecraft.core.registries.Registries.BLOCK, tag))) return true;
			} else if (BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString().equals(r.contains(":") ? r : "minecraft:" + r)) {
				return true;
			}
		}
		return false;
	}
}
