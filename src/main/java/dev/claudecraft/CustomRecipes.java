package dev.claudecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.recipe.v1.ingredient.DefaultCustomIngredients;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Recipes for custom things. The game only loads recipes from data packs, so the definitions' recipes are written
 * as a data pack in the world's datapacks folder (claudecraft_custom) and the server reloads its data, like /reload.
 * They're then ordinary recipes: crafting table, furnaces, stonecutter and the recipe book all know them. Custom
 * ingredients match by definition id (a Fabric components ingredient), so a ruby is not just any custom item.
 *
 * Each recipe makes the definition it belongs to:
 * shaped: { type, pattern: ["RR", "RR"], key: {"R": "custom:ruby"}, count }
 * shapeless: { type, ingredients: ["custom:ruby", "minecraft:stick"], count }
 * smelting / blasting / smoking / campfire_cooking: { type, ingredient, xp, seconds }
 * stonecutting: { type, ingredient, count }
 */
final class CustomRecipes {
	private CustomRecipes() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	static final String PACK = "claudecraft_custom";
	static final List<String> TYPES = List.of("shaped", "shapeless", "smelting", "blasting", "smoking", "campfire_cooking", "stonecutting");

	static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> award(server, List.of(handler.player)));
	}

	private static Path packDir(MinecraftServer server) {
		return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK);
	}

	/** An ingredient's JSON: a vanilla item or #tag as is, custom:<id> matched by definition. */
	static JsonElement ingredient(MinecraftServer server, String name) throws IllegalArgumentException {
		name = name.trim();
		if (name.startsWith("custom:")) {
			JsonObject def = CustomItems.DEFS.get(name.substring(7));
			if (def == null) throw new IllegalArgumentException("No custom item " + name);
			ItemStack s = CustomItems.stack(def, 1);
			Ingredient ing = DefaultCustomIngredients.components(Ingredient.of(s.getItem()), b -> b.set(CustomItems.DEFINITION, def.get("id").getAsString()));
			return Ingredient.CODEC.encodeStart(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), ing).getOrThrow(IllegalArgumentException::new);
		}
		if (name.startsWith("#")) {
			Identifier tag = Identifier.tryParse(name.substring(1));
			if (tag == null || BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, tag)).isEmpty()) throw new IllegalArgumentException("Unknown item tag " + name);
			return new com.google.gson.JsonPrimitive("#" + tag);
		}
		Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
		Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		if (item == null || item == net.minecraft.world.item.Items.AIR) throw new IllegalArgumentException("Unknown item " + name);
		return new com.google.gson.JsonPrimitive(id.toString());
	}

	private static JsonElement result(MinecraftServer server, JsonObject def, int count) {
		ItemStack s = CustomItems.stack(def, 1);
		JsonObject r = ItemStackTemplate.CODEC.encodeStart(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),
			ItemStackTemplate.fromNonEmptyStack(s)).getOrThrow(IllegalArgumentException::new).getAsJsonObject();
		if (count > 1) r.addProperty("count", Math.min(64, count));
		return r;
	}

	/** One recipe spec as vanilla recipe JSON making def. Throws IllegalArgumentException with a readable problem. */
	static JsonObject toJson(MinecraftServer server, JsonObject def, JsonObject spec) {
		String type = spec.has("type") ? spec.get("type").getAsString() : spec.has("pattern") ? "shaped" : "shapeless";
		int count = spec.has("count") ? spec.get("count").getAsInt() : 1;
		JsonObject out = new JsonObject();
		switch (type) {
			case "shaped" -> {
				out.addProperty("type", "minecraft:crafting_shaped");
				out.addProperty("category", "misc");
				JsonArray pattern = spec.has("pattern") ? spec.getAsJsonArray("pattern") : null;
				if (pattern == null || pattern.isEmpty() || pattern.size() > 3) throw new IllegalArgumentException("shaped recipes need a pattern of 1 to 3 rows");
				JsonObject keys = new JsonObject();
				JsonObject given = spec.has("key") ? spec.getAsJsonObject("key") : new JsonObject();
				for (JsonElement row : pattern) {
					String r = row.getAsString();
					if (r.length() > 3) throw new IllegalArgumentException("pattern rows are at most 3 wide");
					for (char c : r.toCharArray()) {
						if (c == ' ' || keys.has(String.valueOf(c))) continue;
						if (!given.has(String.valueOf(c))) throw new IllegalArgumentException("pattern uses '" + c + "' but key doesn't define it");
						keys.add(String.valueOf(c), ingredient(server, given.get(String.valueOf(c)).getAsString()));
					}
				}
				out.add("key", keys);
				out.add("pattern", pattern);
			}
			case "shapeless" -> {
				out.addProperty("type", "minecraft:crafting_shapeless");
				out.addProperty("category", "misc");
				JsonArray ings = new JsonArray();
				for (String s : CustomItems.strings(spec, "ingredients")) ings.add(ingredient(server, s));
				if (ings.isEmpty() || ings.size() > 9) throw new IllegalArgumentException("shapeless recipes need 1 to 9 ingredients");
				out.add("ingredients", ings);
			}
			case "smelting", "blasting", "smoking", "campfire_cooking" -> {
				out.addProperty("type", "minecraft:" + type);
				if (!spec.has("ingredient")) throw new IllegalArgumentException(type + " recipes need an ingredient");
				out.add("ingredient", ingredient(server, spec.get("ingredient").getAsString()));
				int defaultTicks = type.equals("smelting") ? 200 : type.equals("campfire_cooking") ? 600 : 100;
				out.addProperty("cookingtime", spec.has("seconds") ? Math.max(1, Math.round(spec.get("seconds").getAsFloat() * 20)) : defaultTicks);
				out.addProperty("experience", spec.has("xp") ? spec.get("xp").getAsFloat() : 0.1f);
				count = 1;
			}
			case "stonecutting" -> {
				out.addProperty("type", "minecraft:stonecutting");
				if (!spec.has("ingredient")) throw new IllegalArgumentException("stonecutting recipes need an ingredient");
				out.add("ingredient", ingredient(server, spec.get("ingredient").getAsString()));
			}
			default -> throw new IllegalArgumentException("Unknown recipe type " + type + " (use " + String.join(", ", TYPES) + ")");
		}
		out.add("result", result(server, def, count));
		// Let the game's own parser have the last word, so a bad recipe fails here and not silently on reload.
		Recipe.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), out).getOrThrow(IllegalArgumentException::new);
		return out;
	}

	/** Every definition's recipes as file name -> JSON. Recipes that no longer parse (a deleted ingredient) are skipped. */
	private static Map<String, String> files(MinecraftServer server) {
		Map<String, String> out = new TreeMap<>();
		for (JsonObject def : CustomItems.DEFS.values()) {
			JsonObject p = CustomItems.props(def);
			if (!p.has("recipes") || !p.get("recipes").isJsonArray()) continue;
			int i = 0;
			for (JsonElement e : p.getAsJsonArray("recipes")) {
				i++;
				try {
					out.put(def.get("id").getAsString() + "_" + i + ".json", GSON.toJson(toJson(server, def, e.getAsJsonObject())));
				} catch (RuntimeException ex) {
					ClaudeCraft.LOGGER.warn("Skipping recipe {} of {}: {}", i, def.get("id").getAsString(), ex.getMessage());
				}
			}
		}
		return out;
	}

	/** Bring the data pack in line with the definitions, reloading the server's data if anything changed. Server thread. */
	static void sync(MinecraftServer server) {
		Map<String, String> want = files(server);
		Path dir = packDir(server);
		Path recipes = dir.resolve("data").resolve("claudecraft").resolve("recipe");
		try {
			Map<String, String> have = new TreeMap<>();
			if (Files.isDirectory(recipes)) {
				try (Stream<Path> s = Files.list(recipes)) {
					for (Path f : s.toList()) have.put(f.getFileName().toString(), Files.readString(f));
				}
			}
			boolean packExists = Files.exists(dir.resolve("pack.mcmeta"));
			if (want.equals(have) && (packExists || want.isEmpty())) return;
			Files.createDirectories(recipes);
			for (String f : have.keySet()) if (!want.containsKey(f)) Files.delete(recipes.resolve(f));
			for (var e : want.entrySet()) if (!e.getValue().equals(have.get(e.getKey()))) Files.writeString(recipes.resolve(e.getKey()), e.getValue());
			int format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major();
			JsonObject pack = new JsonObject();
			pack.addProperty("description", "Recipes for ClaudeCraft's custom items, blocks and creatures");
			pack.addProperty("min_format", format);
			pack.addProperty("max_format", format);
			JsonObject meta = new JsonObject();
			meta.add("pack", pack);
			Files.writeString(dir.resolve("pack.mcmeta"), GSON.toJson(meta));
		} catch (Exception e) {
			ClaudeCraft.LOGGER.error("Couldn't write custom recipes to {}", dir, e);
			return;
		}
		reload(server);
	}

	/** Like /reload, also turning on our pack the first time it appears. */
	private static void reload(MinecraftServer server) {
		var repo = server.getPackRepository();
		repo.reload();
		List<String> selected = new ArrayList<>(repo.getSelectedIds());
		Collection<String> disabled = server.getWorldData().getDataConfiguration().dataPacks().getDisabled();
		for (String id : repo.getAvailableIds()) if (!disabled.contains(id) && !selected.contains(id)) selected.add(id);
		server.reloadResources(selected).thenRunAsync(() -> award(server, server.getPlayerList().getPlayers()), server).exceptionally(t -> {
			ClaudeCraft.LOGGER.warn("Reloading data for custom recipes failed", t);
			return null;
		});
	}

	/** Put our recipes in players' recipe books. */
	static void award(MinecraftServer server, Collection<ServerPlayer> players) {
		List<RecipeHolder<?>> ours = new ArrayList<>();
		for (RecipeHolder<?> r : server.getRecipeManager().getRecipes()) if (r.id().identifier().getNamespace().equals("claudecraft")) ours.add(r);
		if (ours.isEmpty()) return;
		for (ServerPlayer p : players) p.awardRecipes(ours);
	}

	/** Human-readable recipes of a definition, for list_definitions. */
	static List<String> describe(JsonObject def) {
		List<String> out = new ArrayList<>();
		JsonObject p = CustomItems.props(def);
		if (p.has("recipes") && p.get("recipes").isJsonArray()) for (JsonElement e : p.getAsJsonArray("recipes")) out.add(e.toString());
		return out;
	}
}
