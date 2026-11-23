package dev.claudecraft;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Natural spawning for custom creatures. Vanilla's spawn lists are fixed when the world loads, so this runs its own
 * light version: every couple of seconds, near each player, each creature with a "spawning" property may appear
 * in a matching biome, out of sight range (24 to 48 blocks away), up to a cap nearby. Natural spawns despawn when
 * players leave, like vanilla mobs, unless they're tamed or named.
 *
 * spawning: { biomes: ["minecraft:plains", "#minecraft:is_forest"] (default: any overworld biome),
 *   where: ground | water | air (default from movement), time: day | night | any, chance: 0..1 per check
 *   (default 0.1), group: [min, max] (default [1, 3]), max_nearby (default 6) }
 */
final class CreatureSpawning {
	private CreatureSpawning() {}

	private static final int INTERVAL = 40, MIN_DIST = 24, MAX_DIST = 48, NEARBY = 64;
	static final List<String> WHERE = List.of("ground", "water", "air");
	static final List<String> TIMES = List.of("day", "night", "any");

	static void tick(MinecraftServer server) {
		if (server.getTickCount() % INTERVAL != 0) return;
		List<JsonObject> spawners = new ArrayList<>();
		for (JsonObject def : CustomItems.DEFS.values()) {
			if (CustomEntities.isEntity(def) && CustomItems.props(def).has("spawning")) spawners.add(def);
		}
		if (spawners.isEmpty()) return;
		for (ServerLevel level : server.getAllLevels()) {
			if (!level.getGameRules().get(GameRules.SPAWN_MOBS)) continue;
			for (ServerPlayer p : level.players()) {
				if (p.isSpectator()) continue;
				for (JsonObject def : spawners) tryOne(level, p, def);
			}
		}
	}

	static void tryOne(ServerLevel level, ServerPlayer player, JsonObject def) {
		JsonObject p = CustomItems.props(def);
		JsonObject s = p.get("spawning").isJsonObject() ? p.getAsJsonObject("spawning") : new JsonObject();
		RandomSource random = level.getRandom();
		float chance = s.has("chance") ? s.get("chance").getAsFloat() : 0.1f;
		if (random.nextFloat() >= chance) return;
		boolean hostile = p.has("behavior") && p.get("behavior").getAsString().equals("hostile");
		if (hostile && level.getDifficulty() == Difficulty.PEACEFUL) return;
		List<String> biomes = CustomItems.strings(s, "biomes");
		if (biomes.isEmpty() && level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;

		String id = def.get("id").getAsString();
		int max = s.has("max_nearby") ? s.get("max_nearby").getAsInt() : 6;
		AABB around = player.getBoundingBox().inflate(NEARBY);
		int nearby = level.getEntitiesOfClass(CustomEntities.CustomEntity.class, around, e -> e.definitionId().equals(id)).size();
		if (nearby >= max) return;

		String time = s.has("time") ? s.get("time").getAsString() : hostile ? "night" : "any";
		if (time.equals("day") && !level.isBrightOutside() || time.equals("night") && level.isBrightOutside()) return;

		double angle = random.nextDouble() * Math.PI * 2, dist = MIN_DIST + random.nextDouble() * (MAX_DIST - MIN_DIST);
		int x = (int) Math.floor(player.getX() + Math.cos(angle) * dist), z = (int) Math.floor(player.getZ() + Math.sin(angle) * dist);
		if (!level.hasChunk(x >> 4, z >> 4)) return;

		String move = p.has("movement") ? p.get("movement").getAsString() : "walk";
		String where = s.has("where") ? s.get("where").getAsString() : move.equals("swim") ? "water" : move.equals("fly") ? "air" : "ground";
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos pos;
		switch (where) {
			case "water" -> {
				int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
				if (top - floor < 2 || !level.getFluidState(new BlockPos(x, top - 1, z)).is(net.minecraft.tags.FluidTags.WATER)) return;
				pos = new BlockPos(x, floor + 1 + random.nextInt(top - floor - 1), z);
			}
			case "air" -> pos = new BlockPos(x, top + 4 + random.nextInt(10), z);
			default -> {
				pos = new BlockPos(x, top, z);
				if (!level.getBlockState(pos.below()).isSolid() || !level.getFluidState(pos.below()).isEmpty()) return;
			}
		}
		if (!biomeMatches(level.getBiome(pos), biomes)) return;
		// Hostile creatures want the dark, like monsters; others the open sky.
		if (hostile && level.getBrightness(LightLayer.BLOCK, pos) > 0) return;

		int[] group = {1, 3};
		if (s.has("group") && s.get("group").isJsonArray() && s.getAsJsonArray("group").size() == 2) {
			group[0] = Math.max(1, s.getAsJsonArray("group").get(0).getAsInt());
			group[1] = Math.max(group[0], s.getAsJsonArray("group").get(1).getAsInt());
		}
		int n = Math.min(max - nearby, group[0] + random.nextInt(group[1] - group[0] + 1));
		if (n <= 0) return;
		EntityModel m = CustomEntities.model(id);
		if (m != null && !level.noCollision(new AABB(pos.getX() + 0.5 - m.width() / 2, pos.getY(), pos.getZ() + 0.5 - m.width() / 2,
			pos.getX() + 0.5 + m.width() / 2, pos.getY() + m.height(), pos.getZ() + 0.5 + m.width() / 2))) return;
		// A few babies in bigger groups of breedable creatures, like vanilla herds.
		boolean babies = p.has("food") && n > 1;
		for (int i = 0; i < n; i++) {
			CustomEntities.spawn(level, def, new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), 1, babies && random.nextFloat() < 0.2f, true);
		}
	}

	static boolean biomeMatches(Holder<Biome> biome, List<String> wanted) {
		if (wanted.isEmpty()) return true;
		for (String w : wanted) {
			if (w.startsWith("#")) {
				Identifier tag = Identifier.tryParse(w.substring(1));
				if (tag != null && biome.is(TagKey.create(Registries.BIOME, tag))) return true;
			} else {
				Identifier id = Identifier.tryParse(w.contains(":") ? w : "minecraft:" + w);
				if (id != null && biome.is(id)) return true;
			}
		}
		return false;
	}
}
