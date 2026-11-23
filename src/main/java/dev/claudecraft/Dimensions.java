package dev.claudecraft;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * Claude's dimensions, created while the game runs. Minecraft builds its worlds once at startup, so this does
 * what that code does for each custom dimension: a ServerLevel with our terrain generator, added to the server's
 * map of levels, where it ticks, saves and closes like any other. Its files live in
 * world/dimensions/claudecraft/<id>. Every server start recreates the defined dimensions before players load in.
 *
 * Players' games only know the dimension types and biomes they were sent when they joined, so a dimension picks
 * its sky from the types this mod ships (daylight, cavern, starry) and its colors from a biome (vanilla, or the
 * mod's alien, crimson, frozen, toxic and golden).
 */
final class Dimensions {
	private Dimensions() {}

	static final List<String> SKIES = List.of("daylight", "cavern", "starry");
	/** What each loaded custom dimension was built with (sky and biome), to know when it must be rebuilt. */
	private static final Map<ResourceKey<Level>, String> BUILT_WITH = new ConcurrentHashMap<>();

	static boolean isDimension(JsonObject def) {
		return def != null && def.has("kind") && def.get("kind").getAsString().equals("dimension");
	}

	static ResourceKey<Level> key(String id) {
		return ResourceKey.create(Registries.DIMENSION, CustomItems.id(id));
	}

	/** A level by name: a custom dimension's id, or a vanilla one like overworld or minecraft:the_nether. */
	static ServerLevel level(MinecraftServer server, String name) {
		if (!name.contains(":") && isDimension(CustomItems.DEFS.get(name))) return server.getLevel(key(name));
		Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
		return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	static Holder<Biome> biome(MinecraftServer server, String name) {
		Registry<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
		List<Identifier> tries = name.contains(":") ? List.of(Identifier.parse(name))
			: List.of(Identifier.fromNamespaceAndPath("claudecraft", name), Identifier.withDefaultNamespace(name));
		for (Identifier id : tries) {
			var b = biomes.get(ResourceKey.create(Registries.BIOME, id));
			if (b.isPresent()) return b.get();
		}
		throw new IllegalArgumentException("Unknown biome " + name);
	}

	static Holder<DimensionType> sky(MinecraftServer server, String name) {
		if (!SKIES.contains(name)) throw new IllegalArgumentException("sky must be one of " + String.join(", ", SKIES));
		return server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)
			.get(ResourceKey.create(Registries.DIMENSION_TYPE, CustomItems.id(name)))
			.orElseThrow(() -> new IllegalArgumentException("The " + name + " sky is missing from this game; is the mod's data pack disabled?"));
	}

	private static String skyOf(JsonObject def) {
		return def.has("sky") ? def.get("sky").getAsString() : "daylight";
	}

	private static String biomeOf(JsonObject def) {
		return def.has("biome") ? def.get("biome").getAsString() : "plains";
	}

	// ------------------------------------------------------------ the server's private parts

	@SuppressWarnings("unchecked")
	private static Map<ResourceKey<Level>, ServerLevel> levels(MinecraftServer server) {
		return (Map<ResourceKey<Level>, ServerLevel>) field(server, "levels");
	}

	private static Object field(MinecraftServer server, String name) {
		try {
			Field f = MinecraftServer.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.get(server);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Can't reach MinecraftServer." + name + " to create a dimension", e);
		}
	}

	// ------------------------------------------------------------ creating and removing

	/** Make sure a dimension is loaded and built from its current sky and biome. Server thread. */
	static ServerLevel ensure(MinecraftServer server, JsonObject def) {
		String id = def.get("id").getAsString();
		ResourceKey<Level> key = key(id);
		String with = skyOf(def) + "|" + biomeOf(def);
		ServerLevel existing = server.getLevel(key);
		if (existing != null && with.equals(BUILT_WITH.get(key))) return existing;
		List<ServerPlayer> moved = existing == null ? List.of() : unload(server, existing);
		ServerLevel level = create(server, def);
		for (ServerPlayer p : moved) visit(p, level, null);
		return level;
	}

	private static ServerLevel create(MinecraftServer server, JsonObject def) {
		String id = def.get("id").getAsString();
		ResourceKey<Level> key = key(id);
		Holder<DimensionType> type = sky(server, skyOf(def));
		Holder<Biome> biome = biome(server, biomeOf(def));
		long seed = server.overworld().getSeed() ^ ((long) id.hashCode() << 16);
		var generator = new CustomChunkGenerator(new FixedBiomeSource(biome), id, seed, type.value().minY(), type.value().height());
		var worldData = server.getWorldData();
		ServerLevel level = new ServerLevel(server, (Executor) field(server, "executor"), (LevelStorageSource.LevelStorageAccess) field(server, "storageSource"),
			new DerivedLevelData(worldData, worldData.overworldData()), key, new LevelStem(type, generator), false,
			BiomeManager.obfuscateSeed(seed), List.of(), false);
		level.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
		server.getPlayerList().addWorldborderListener(level);
		levels(server).put(key, level);
		BUILT_WITH.put(key, skyOf(def) + "|" + biomeOf(def));
		ServerLevelEvents.LOAD.invoker().onLevelLoad(server, level);
		return level;
	}

	/** Save and close a custom dimension, sending anyone in it to the overworld. Returns who was moved. */
	static List<ServerPlayer> unload(MinecraftServer server, ServerLevel level) {
		List<ServerPlayer> moved = new ArrayList<>(level.players());
		for (ServerPlayer p : moved) visit(p, server.overworld(), null);
		ServerLevelEvents.UNLOAD.invoker().onLevelUnload(server, level);
		level.save(null, true, false);
		try {
			level.close();
		} catch (IOException e) {
			ClaudeCraft.LOGGER.warn("Closing dimension {} failed", level.dimension().identifier(), e);
		}
		levels(server).remove(level.dimension());
		BUILT_WITH.remove(level.dimension());
		return moved;
	}

	/** Unload a dimension and delete its saved chunks, so it generates fresh from its current design. */
	static ServerLevel reset(MinecraftServer server, JsonObject def) throws IOException {
		ResourceKey<Level> key = key(def.get("id").getAsString());
		ServerLevel old = server.getLevel(key);
		List<ServerPlayer> moved = old == null ? List.of() : unload(server, old);
		Path dir = ((LevelStorageSource.LevelStorageAccess) field(server, "storageSource")).getDimensionPath(key);
		if (Files.exists(dir)) {
			try (Stream<Path> walk = Files.walk(dir)) {
				for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
			}
		}
		ServerLevel level = create(server, def);
		for (ServerPlayer p : moved) visit(p, level, null);
		return level;
	}

	/** Recreate every defined dimension. At server start, before players join. */
	static void loadAll(MinecraftServer server) {
		for (JsonObject def : List.copyOf(CustomItems.DEFS.values())) {
			if (!isDimension(def)) continue;
			try {
				ensure(server, def);
			} catch (RuntimeException e) {
				ClaudeCraft.LOGGER.error("Couldn't load dimension {}", def.get("id").getAsString(), e);
			}
		}
	}

	// ------------------------------------------------------------ visiting

	/** Send a player to a level, at a safe spot near a column (default: the level's spawn or 0, 0). Returns where. */
	static Vec3 visit(ServerPlayer player, ServerLevel level, BlockPos near) {
		if (near == null) {
			near = level.dimension() == Level.OVERWORLD ? level.getRespawnData().pos() : BlockPos.ZERO;
		}
		Vec3 at = safeSpot(level, near.getX(), near.getZ());
		player.teleport(new TeleportTransition(level, at, Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.DO_NOTHING));
		return at;
	}

	/**
	 * A place to stand near (x, z): open ground under the sky, or in a dimension with a ceiling a pocket of air
	 * with a floor. Searches outward; if there's no ground at all (the void, between islands), builds a small
	 * platform.
	 */
	static Vec3 safeSpot(ServerLevel level, int x, int z) {
		boolean ceiling = level.dimensionType().hasCeiling();
		for (int r = 0; r <= 64; r += 4) {
			for (int dx = -r; dx <= r; dx += 4) for (int dz = -r; dz <= r; dz += 4) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
				int cx = x + dx, cz = z + dz;
				level.getChunk(cx >> 4, cz >> 4); // generate it
				if (ceiling) {
					for (int y = level.getMinY() + level.getLogicalHeight() - 3; y > level.getMinY() + 1; y--) {
						if (standable(level, new BlockPos(cx, y, cz))) return Vec3.atBottomCenterOf(new BlockPos(cx, y, cz));
					}
				} else {
					int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz); // the ground, not treetops
					BlockPos feet = new BlockPos(cx, y, cz);
					if (y > level.getMinY() && standable(level, feet)) return Vec3.atBottomCenterOf(feet);
				}
			}
		}
		// Nothing to stand on: make a little platform.
		int y = Math.max(level.getMinY() + 2, Math.min(level.getMinY() + level.getLogicalHeight() - 4, 64));
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
			level.setBlockAndUpdate(new BlockPos(x + dx, y - 1, z + dz), Blocks.SMOOTH_STONE.defaultBlockState());
			for (int dy = 0; dy < 3; dy++) level.setBlockAndUpdate(new BlockPos(x + dx, y + dy, z + dz), Blocks.AIR.defaultBlockState());
		}
		return Vec3.atBottomCenterOf(new BlockPos(x, y, z));
	}

	static boolean standable(ServerLevel level, BlockPos feet) {
		var below = level.getBlockState(feet.below());
		return below.isSolid() && level.getFluidState(feet.below()).isEmpty()
			&& level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getFluidState(feet).isEmpty()
			&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty() && level.getFluidState(feet.above()).isEmpty();
	}
}
