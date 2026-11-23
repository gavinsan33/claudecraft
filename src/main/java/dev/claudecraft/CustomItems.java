package dev.claudecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.item.v1.EnchantingContext;
import net.fabricmc.fabric.api.item.v1.EnchantmentEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.Consumables;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.Enchantable;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Repairable;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.providers.number.floats.ResolvableFloat;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Items Claude designs while the game runs. Minecraft can't register new items after startup, so there is one
 * registered item, claudecraft:custom_item, and each stack names its definition in the claudecraft:definition
 * component. Definitions (name, voxel model, behavior) are saved with the world and sent to every client, which
 * draws the voxel model. This is why every player needs the mod. Creatures get spawn eggs the same way, through
 * claudecraft:custom_spawn_egg.
 */
public final class CustomItems {
	private CustomItems() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int MAX_PAYLOAD = 8 * 1024 * 1024;
	/** Save-file key for block slots that deleted definitions used (ids can't contain '$'). */
	private static final String RETIRED_KEY = "$retired_block_slots";

	public static DataComponentType<String> DEFINITION;
	public static Item ITEM;
	public static Item SPAWN_EGG;

	/**
	 * Definitions by id. The server fills it from the world save and clients from the server's packets. It's one
	 * shared map because in singleplayer both sides run in one process and hold the same data anyway.
	 */
	public static final Map<String, JsonObject> DEFS = new ConcurrentHashMap<>();
	/** Block slots once used by deleted block definitions; reused only when no fresh slot is left. */
	static final Set<Integer> RETIRED_SLOTS = ConcurrentHashMap.newKeySet();
	/**
	 * Called on the client thread whenever definitions change, so the client can drop cached meshes. The argument
	 * says whether any block definitions changed (placed blocks then need redrawing).
	 */
	public static volatile Consumer<Boolean> onClientChange = blocks -> {};
	/** Called on the server thread after definitions change (recipes, ore generation...). */
	static volatile Runnable onServerChange = () -> {};

	/** A freshly built stack per definition, whose behavior components existing copies are brought in line with. */
	private static final Map<String, ItemStack> TEMPLATES = new ConcurrentHashMap<>();

	private static Path saveFile;

	/** Sent to clients: a JSON array of definitions, either everything (replace) or just the changed ones. */
	public record DefinitionsPayload(String json, boolean replaceAll, List<String> removed) implements CustomPacketPayload {
		public static final Type<DefinitionsPayload> TYPE = new Type<>(id("item_definitions"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DefinitionsPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(MAX_PAYLOAD), DefinitionsPayload::json,
			ByteBufCodecs.BOOL, DefinitionsPayload::replaceAll,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), DefinitionsPayload::removed,
			DefinitionsPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(ClaudeCraft.MOD_ID, path);
	}

	/** Registration, from the common initializer (before registries freeze). */
	static void register() {
		DEFINITION = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id("definition"),
			DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id("custom_item"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, key, new CustomItem(new Item.Properties().setId(key)));
		CustomBlocks.register();
		CustomEntities.register();
		CustomProjectiles.register();
		Effects.register();
		ResourceKey<Item> eggKey = ResourceKey.create(Registries.ITEM, id("custom_spawn_egg"));
		SPAWN_EGG = Registry.register(BuiltInRegistries.ITEM, eggKey, new CustomSpawnEgg(new Item.Properties().setId(eggKey)));
		PayloadTypeRegistry.clientboundPlay().registerLarge(DefinitionsPayload.TYPE, DefinitionsPayload.CODEC, MAX_PAYLOAD);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sender.sendPacket(payload(DEFS.values(), true, List.of())));
		EnchantmentEvents.ALLOW_ENCHANTING.register(CustomItems::allowEnchanting);
		CustomCreativeTab.register();
	}

	/** Load this world's definitions. Server thread, at startup. */
	static void load(MinecraftServer server) {
		saveFile = server.getWorldPath(LevelResource.ROOT).resolve("claudecraft").resolve("definitions.json");
		DEFS.clear();
		RETIRED_SLOTS.clear();
		try {
			if (Files.exists(saveFile)) {
				for (var e : JsonParser.parseString(Files.readString(saveFile)).getAsJsonObject().entrySet()) {
					if (e.getKey().equals(RETIRED_KEY)) {
						for (JsonElement s : e.getValue().getAsJsonArray()) RETIRED_SLOTS.add(s.getAsInt());
					} else {
						DEFS.put(e.getKey(), e.getValue().getAsJsonObject());
					}
				}
			}
		} catch (Exception e) {
			ClaudeCraft.LOGGER.error("Couldn't read custom item definitions from {}", saveFile, e);
		}
		changed();
	}

	/** Add or replace a definition, save it, and send it to everyone online. Server thread. */
	static void define(MinecraftServer server, JsonObject def) {
		String id = def.get("id").getAsString();
		DEFS.put(id, def);
		if (CustomBlocks.isBlock(def)) RETIRED_SLOTS.remove(def.get("slot").getAsInt());
		changed();
		save();
		if (CustomEntities.isEntity(def)) {
			// Creatures already in the world take on the new size and stats.
			for (var level : server.getAllLevels()) for (var e : level.getAllEntities()) {
				if (e instanceof CustomEntities.CustomEntity ce && ce.definitionId().equals(id)) ce.applyDefinition(false);
			}
		}
		broadcast(server, payload(List.of(def), false, List.of()));
		onServerChange.run();
	}

	/** Delete a definition. Existing copies stay but show as missing until it's defined again. Server thread. */
	static void remove(MinecraftServer server, String id) {
		JsonObject old = DEFS.remove(id);
		if (old == null) return;
		if (CustomBlocks.isBlock(old)) RETIRED_SLOTS.add(old.get("slot").getAsInt());
		changed();
		save();
		broadcast(server, payload(List.of(), false, List.of(id)));
		onServerChange.run();
	}

	private static void save() {
		JsonObject all = new JsonObject();
		DEFS.forEach(all::add);
		JsonArray retired = new JsonArray();
		RETIRED_SLOTS.stream().sorted().forEach(retired::add);
		if (!retired.isEmpty()) all.add(RETIRED_KEY, retired);
		try {
			Files.createDirectories(saveFile.getParent());
			Files.writeString(saveFile, GSON.toJson(all));
		} catch (Exception e) {
			ClaudeCraft.LOGGER.error("Couldn't save custom item definitions to {}", saveFile, e);
		}
	}

	private static void broadcast(MinecraftServer server, DefinitionsPayload p) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) ServerPlayNetworking.send(player, p);
	}

	private static void changed() {
		TEMPLATES.clear();
		CustomBlocks.definitionsChanged();
		CustomEntities.definitionsChanged();
	}

	private static DefinitionsPayload payload(Collection<JsonObject> defs, boolean replaceAll, List<String> removed) {
		JsonArray arr = new JsonArray();
		defs.forEach(arr::add);
		return new DefinitionsPayload(arr.toString(), replaceAll, removed);
	}

	/** Apply a payload on the client. */
	public static void receive(DefinitionsPayload p) {
		Set<String> ids = new HashSet<>();
		boolean blocks = p.replaceAll();
		for (JsonElement e : JsonParser.parseString(p.json()).getAsJsonArray()) {
			JsonObject def = e.getAsJsonObject();
			ids.add(def.get("id").getAsString());
			DEFS.put(def.get("id").getAsString(), def);
			blocks |= CustomBlocks.isBlock(def);
		}
		for (String id : p.removed()) {
			JsonObject old = DEFS.remove(id);
			blocks |= CustomBlocks.isBlock(old);
		}
		// Not clear() first: in singleplayer the server reads this same map from its own thread.
		if (p.replaceAll()) DEFS.keySet().retainAll(ids);
		changed();
		onClientChange.accept(blocks);
	}

	/** The definition behind a custom item, custom block item or spawn egg stack, or null. */
	public static JsonObject definition(ItemStack stack) {
		String id = stack.get(DEFINITION);
		if (id == null && stack.getItem() == CustomBlocks.ITEM) {
			// Older block drops carry only their block state (variant), not the definition id.
			var state = stack.get(DataComponents.BLOCK_STATE);
			String variant = state == null ? null : state.properties().get("variant");
			if (variant != null) id = CustomBlocks.idForSlot(Integer.parseInt(variant));
		}
		return id == null ? null : DEFS.get(id);
	}

	static JsonObject props(JsonObject def) {
		return def != null && def.has("properties") ? def.getAsJsonObject("properties") : new JsonObject();
	}

	static List<String> strings(JsonObject p, String key) {
		List<String> out = new ArrayList<>();
		if (p.has(key) && p.get(key).isJsonArray()) for (JsonElement e : p.getAsJsonArray(key)) out.add(e.getAsString());
		return out;
	}

	/** A stack of a definition: an item with its behavior components, a block, or a creature's spawn egg. */
	static ItemStack stack(JsonObject def, int count) {
		if (CustomBlocks.isBlock(def)) return CustomBlocks.stack(def, count);
		if (CustomEntities.isEntity(def)) return spawnEgg(def, count);
		ItemStack s = new ItemStack(ITEM);
		s.set(DEFINITION, def.get("id").getAsString());
		applyComponents(s, def);
		s.setCount(Math.max(1, Math.min(s.getMaxStackSize(), count)));
		return s;
	}

	static ItemStack spawnEgg(JsonObject def, int count) {
		ItemStack s = new ItemStack(SPAWN_EGG, Math.max(1, Math.min(64, count)));
		String id = def.get("id").getAsString();
		s.set(DEFINITION, id);
		CompoundTag data = new CompoundTag();
		data.putString("Definition", id);
		s.set(DataComponents.ENTITY_DATA, TypedEntityData.of(CustomEntities.TYPE, data));
		return s;
	}

	/** Every component a definition's properties control; refreshed on existing copies when it's redefined. */
	private static final List<DataComponentType<?>> MANAGED = List.of(DataComponents.MAX_STACK_SIZE, DataComponents.MAX_DAMAGE,
		DataComponents.FOOD, DataComponents.CONSUMABLE, DataComponents.ATTRIBUTE_MODIFIERS, DataComponents.WEAPON,
		DataComponents.ENCHANTMENT_GLINT_OVERRIDE, DataComponents.RARITY, DataComponents.USE_COOLDOWN, DataComponents.TOOL,
		DataComponents.EQUIPPABLE, DataComponents.DYED_COLOR, DataComponents.COOKING_FUEL, DataComponents.REPAIRABLE,
		DataComponents.ENCHANTABLE);

	static final List<String> TOOL_TYPES = List.of("pickaxe", "axe", "shovel", "hoe");
	static final List<String> TIERS = List.of("wood", "stone", "copper", "iron", "gold", "diamond", "netherite");
	static final List<String> ARMOR_SLOTS = List.of("head", "chest", "legs", "feet");
	static final List<String> ARMOR_LOOKS = List.of("model", "leather", "chainmail", "copper", "iron", "gold", "diamond", "netherite", "turtle_scute");

	static ToolMaterial tier(String name) {
		return switch (name) {
			case "wood" -> ToolMaterial.WOOD;
			case "stone" -> ToolMaterial.STONE;
			case "copper" -> ToolMaterial.COPPER;
			case "gold" -> ToolMaterial.GOLD;
			case "diamond" -> ToolMaterial.DIAMOND;
			case "netherite" -> ToolMaterial.NETHERITE;
			default -> ToolMaterial.IRON;
		};
	}

	static TagKey<Block> mineable(String toolType) {
		return switch (toolType) {
			case "axe" -> BlockTags.MINEABLE_WITH_AXE;
			case "shovel" -> BlockTags.MINEABLE_WITH_SHOVEL;
			case "hoe" -> BlockTags.MINEABLE_WITH_HOE;
			default -> BlockTags.MINEABLE_WITH_PICKAXE;
		};
	}

	private static EquipmentSlot armorSlot(String name) {
		return switch (name) {
			case "head" -> EquipmentSlot.HEAD;
			case "legs" -> EquipmentSlot.LEGS;
			case "feet" -> EquipmentSlot.FEET;
			default -> EquipmentSlot.CHEST;
		};
	}

	/** Set every behavior component the definition's properties call for, and clear the ones they don't. */
	static void applyComponents(ItemStack s, JsonObject def) {
		for (DataComponentType<?> t : MANAGED) s.remove(t);
		String id = def.get("id").getAsString();
		JsonObject p = props(def);
		int maxStack = p.has("max_stack") ? Math.max(1, Math.min(99, p.get("max_stack").getAsInt())) : 64;
		boolean durable = p.has("durability");
		if (durable) {
			maxStack = 1;
			s.set(DataComponents.MAX_DAMAGE, Math.max(1, p.get("durability").getAsInt()));
		}
		s.set(DataComponents.MAX_STACK_SIZE, maxStack);
		if (p.has("food")) {
			JsonObject f = p.getAsJsonObject("food");
			s.set(DataComponents.FOOD, new FoodProperties(f.has("nutrition") ? f.get("nutrition").getAsInt() : 4,
				f.has("saturation") ? f.get("saturation").getAsFloat() : 2.4f, f.has("always_edible") && f.get("always_edible").getAsBoolean()));
			s.set(DataComponents.CONSUMABLE, Consumables.DEFAULT_FOOD);
		}

		ItemAttributeModifiers.Builder mods = ItemAttributeModifiers.builder();
		boolean anyMods = false;
		if (p.has("attack_damage")) {
			// Players have 1 base damage and 4 base attack speed; vanilla tools store the difference.
			mods.add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID,
				p.get("attack_damage").getAsDouble() - 1, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
			anyMods = true;
		}
		if (p.has("attack_speed")) {
			mods.add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID,
				p.get("attack_speed").getAsDouble() - 4, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
			anyMods = true;
		}
		if ((p.has("attack_damage") || p.has("attack_speed")) && durable) s.set(DataComponents.WEAPON, new Weapon(1)); // wears down when it hits

		if (p.has("tool")) {
			JsonObject t = p.getAsJsonObject("tool");
			String type = t.has("type") ? t.get("type").getAsString() : "pickaxe";
			ToolMaterial m = tier(t.has("tier") ? t.get("tier").getAsString() : "iron");
			float speed = t.has("speed") ? t.get("speed").getAsFloat() : m.speed();
			s.set(DataComponents.TOOL, new Tool(List.of(
				Tool.Rule.deniesDrops(BuiltInRegistries.BLOCK.getOrThrow(m.incorrectBlocksForDrops())),
				Tool.Rule.minesAndDrops(BuiltInRegistries.BLOCK.getOrThrow(mineable(type)), speed)), 1.0f, durable ? 1 : 0, true));
			if (!p.has("enchantability")) s.set(DataComponents.ENCHANTABLE, new Enchantable(m.enchantmentValue()));
		}

		if (p.has("armor")) {
			JsonObject a = p.getAsJsonObject("armor");
			EquipmentSlot slot = armorSlot(a.has("slot") ? a.get("slot").getAsString() : "chest");
			String look = a.has("look") ? a.get("look").getAsString() : slot == EquipmentSlot.HEAD ? "model" : "leather";
			Equippable.Builder eq = Equippable.builder(slot).setEquipSound(SoundEvents.ARMOR_EQUIP_GENERIC);
			if (!look.equals("model")) {
				eq.setAsset(ResourceKey.create(EquipmentAssets.ROOT_ID, Identifier.withDefaultNamespace(look)));
				if (look.equals("leather")) {
					int color = a.has("color") ? VoxelModel.color(a.get("color").getAsString()) : averageColor(def);
					s.set(DataComponents.DYED_COLOR, new DyedItemColor(color & 0xFFFFFF));
				}
			}
			s.set(DataComponents.EQUIPPABLE, eq.build());
			EquipmentSlotGroup group = EquipmentSlotGroup.bySlot(slot);
			String n = "armor." + slot.getName();
			if (a.has("armor")) mods.add(Attributes.ARMOR, new AttributeModifier(id(n), a.get("armor").getAsDouble(), AttributeModifier.Operation.ADD_VALUE), group);
			if (a.has("toughness")) mods.add(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(id(n + ".toughness"), a.get("toughness").getAsDouble(), AttributeModifier.Operation.ADD_VALUE), group);
			if (a.has("knockback_resistance")) mods.add(Attributes.KNOCKBACK_RESISTANCE, new AttributeModifier(id(n + ".knockback"), a.get("knockback_resistance").getAsDouble(), AttributeModifier.Operation.ADD_VALUE), group);
			anyMods = true;
			maxStack = 1;
			s.set(DataComponents.MAX_STACK_SIZE, 1);
		}
		if (anyMods) s.set(DataComponents.ATTRIBUTE_MODIFIERS, mods.build());

		if (p.has("glint") && p.get("glint").getAsBoolean()) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		if (p.has("rarity")) s.set(DataComponents.RARITY, Rarity.valueOf(p.get("rarity").getAsString().toUpperCase(Locale.ROOT)));
		if (p.has("cooldown_seconds")) {
			s.set(DataComponents.USE_COOLDOWN, new UseCooldown(p.get("cooldown_seconds").getAsFloat(), Optional.of(id("item/" + id))));
		}
		if (p.has("fuel_seconds")) {
			s.set(DataComponents.COOKING_FUEL, new CookingFuel(new ResolvableInt.Constant(Math.max(1, Math.round(p.get("fuel_seconds").getAsFloat() * 20))),
				new ResolvableFloat.Constant(1f)));
		}
		if (p.has("repair_with")) {
			HolderSet<Item> items = repairItems(strings(p, "repair_with"));
			if (items != null) s.set(DataComponents.REPAIRABLE, new Repairable(items));
		}
		if (p.has("enchantability")) s.set(DataComponents.ENCHANTABLE, new Enchantable(Math.max(1, p.get("enchantability").getAsInt())));
	}

	/** Items or #tags that repair an item in an anvil. custom:<id> allows custom items in general. */
	private static HolderSet<Item> repairItems(List<String> names) {
		List<Holder<Item>> holders = new ArrayList<>();
		for (String n : names) {
			if (n.startsWith("#")) {
				Identifier tag = Identifier.tryParse(n.substring(1));
				if (tag == null) continue;
				BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, tag)).ifPresent(set -> set.forEach(holders::add));
			} else if (n.startsWith("custom:")) {
				holders.add(BuiltInRegistries.ITEM.wrapAsHolder(ITEM));
			} else {
				Identifier key = Identifier.tryParse(n);
				if (key != null) BuiltInRegistries.ITEM.get(key).ifPresent(holders::add);
			}
		}
		return holders.isEmpty() ? null : HolderSet.direct(holders);
	}

	static int averageColor(JsonObject def) {
		try {
			return VoxelModel.parse(def.getAsJsonObject("model")).averageColor();
		} catch (RuntimeException e) {
			return 0x808080;
		}
	}

	/** Bring a copy's behavior components in line with its (possibly redefined) definition. */
	static void refresh(ItemStack s) {
		JsonObject def = definition(s);
		if (def == null || CustomBlocks.isBlock(def) || CustomEntities.isEntity(def)) return;
		ItemStack template = TEMPLATES.computeIfAbsent(def.get("id").getAsString(), k -> {
			ItemStack t = new ItemStack(ITEM);
			t.set(DEFINITION, k);
			applyComponents(t, def);
			return t;
		});
		for (DataComponentType<?> t : MANAGED) copy(t, template, s);
	}

	private static <T> void copy(DataComponentType<T> type, ItemStack from, ItemStack to) {
		T want = from.get(type);
		if (Objects.equals(want, to.get(type))) return;
		if (want == null) to.remove(type);
		else to.set(type, want);
	}

	/**
	 * Custom items can take the enchantments of the vanilla items they behave like: a weapon those of a sword, a
	 * pickaxe those of a pickaxe, a helmet those of a helmet, anything with durability Unbreaking and Mending.
	 */
	private static TriState allowEnchanting(Holder<Enchantment> ench, ItemStack stack, EnchantingContext ctx) {
		if (stack.getItem() != ITEM) return TriState.DEFAULT;
		JsonObject def = definition(stack);
		if (def == null || !stack.has(DataComponents.ENCHANTABLE) && ctx == EnchantingContext.PRIMARY) return TriState.DEFAULT;
		for (ItemStack like : lookalikes(props(def))) {
			if (ctx == EnchantingContext.PRIMARY ? ench.value().isPrimaryItem(like) : ench.value().isSupportedItem(like)) return TriState.TRUE;
		}
		return TriState.DEFAULT;
	}

	private static List<ItemStack> lookalikes(JsonObject p) {
		List<ItemStack> out = new ArrayList<>();
		if (p.has("attack_damage")) out.add(new ItemStack(Items.DIAMOND_SWORD));
		if (p.has("tool")) {
			JsonObject t = p.getAsJsonObject("tool");
			out.add(new ItemStack(switch (t.has("type") ? t.get("type").getAsString() : "pickaxe") {
				case "axe" -> Items.DIAMOND_AXE;
				case "shovel" -> Items.DIAMOND_SHOVEL;
				case "hoe" -> Items.DIAMOND_HOE;
				default -> Items.DIAMOND_PICKAXE;
			}));
		}
		if (p.has("armor")) {
			JsonObject a = p.getAsJsonObject("armor");
			out.add(new ItemStack(switch (a.has("slot") ? a.get("slot").getAsString() : "chest") {
				case "head" -> Items.DIAMOND_HELMET;
				case "legs" -> Items.DIAMOND_LEGGINGS;
				case "feet" -> Items.DIAMOND_BOOTS;
				default -> Items.DIAMOND_CHESTPLATE;
			}));
		}
		if (p.has("shoot")) out.add(new ItemStack(Items.BOW));
		if (p.has("durability")) out.add(new ItemStack(Items.FLINT_AND_STEEL)); // Unbreaking, Mending
		return out;
	}

	/** An item argument for commands with every component, e.g. claudecraft:custom_item[claudecraft:definition="ruby",...]. */
	static String itemArgument(ItemStack s, HolderLookup.Provider registries) {
		StringBuilder sb = new StringBuilder(BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
		var ops = registries.createSerializationContext(NbtOps.INSTANCE);
		List<String> parts = new ArrayList<>();
		for (var c : s.getComponentsPatch().split().added()) {
			Tag tag = encode(c.type(), c.value(), ops);
			if (tag != null) parts.add(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(c.type()) + "=" + tag);
		}
		if (!parts.isEmpty()) sb.append('[').append(String.join(",", parts)).append(']');
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private static <T> Tag encode(DataComponentType<T> type, Object value, com.mojang.serialization.DynamicOps<Tag> ops) {
		Codec<T> codec = type.codec();
		return codec == null ? null : codec.encodeStart(ops, (T) value).result().orElse(null);
	}

	/** How custom:<id> reads in commands. */
	static CommandRewriter.Items commandItems(MinecraftServer server) {
		return new CommandRewriter.Items() {
			@Override
			public String stack(JsonObject def) {
				return itemArgument(CustomItems.stack(def, 1), server.registryAccess());
			}

			@Override
			public String predicate(JsonObject def) {
				Item item = CustomBlocks.isBlock(def) ? CustomBlocks.ITEM : CustomEntities.isEntity(def) ? SPAWN_EGG : ITEM;
				return BuiltInRegistries.ITEM.getKey(item) + "[claudecraft:definition=\"" + def.get("id").getAsString() + "\"]";
			}

			@Override
			public String blockState(JsonObject def) {
				return net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(CustomBlocks.state(def));
			}
		};
	}

	// ---------------------------------------------------------------- running commands

	/** Commands run as the player, with @target standing for the entity involved (by UUID). */
	static void runAsPlayer(ServerPlayer player, List<String> cmds, Vec3 at, Entity target) {
		if (cmds.isEmpty()) return;
		List<String> out = new ArrayList<>();
		// Plain decimals: far from spawn, Double.toString switches to 1.2E7, which commands can't read.
		String prefix = at == null ? "" : String.format(Locale.ROOT, "execute positioned %.4f %.4f %.4f run ", at.x, at.y, at.z);
		for (String c : cmds) out.add(prefix + withTarget(c.replaceFirst("^/", ""), target));
		ClaudeCraft.runItemCommands(player, out);
	}

	static String withTarget(String cmd, Entity target) {
		return target == null ? cmd : cmd.replace("@target", target.getStringUUID());
	}

	/** The one registered custom item. Its name, tooltip and behavior come from the stack's definition. */
	static final class CustomItem extends Item {
		CustomItem(Properties props) {
			super(props);
		}

		@Override
		public Component getName(ItemStack stack) {
			JsonObject def = definition(stack);
			if (def == null) return super.getName(stack);
			return Component.literal(def.get("name").getAsString());
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
			description(definition(stack), out);
		}

		@Override
		public InteractionResult use(Level level, Player player, InteractionHand hand) {
			ItemStack stack = player.getItemInHand(hand);
			JsonObject def = definition(stack);
			if (def == null) return super.use(level, player, hand);
			JsonObject p = props(def);
			// Food is eaten the vanilla way; its commands run once it's finished (see finishUsingItem).
			// Armor is put on the vanilla way too, unless it also does something on right-click.
			if (p.has("food")) return super.use(level, player, hand);
			List<String> cmds = strings(p, "use_commands");
			boolean shoots = p.has("shoot");
			if (cmds.isEmpty() && !shoots) return super.use(level, player, hand);
			if (!level.isClientSide() && player instanceof ServerPlayer sp) {
				if (shoots && !CustomProjectiles.shootFromItem(sp, stack, p.getAsJsonObject("shoot"))) return InteractionResult.FAIL;
				ClaudeCraft.runItemCommands(sp, cmds); // the use_cooldown component starts the cooldown
			}
			return InteractionResult.SUCCESS;
		}

		@Override
		public InteractionResult useOn(UseOnContext ctx) {
			JsonObject def = definition(ctx.getItemInHand());
			List<String> cmds = strings(props(def), "use_on_block_commands");
			if (cmds.isEmpty()) return super.useOn(ctx);
			if (!ctx.getLevel().isClientSide() && ctx.getPlayer() instanceof ServerPlayer sp) {
				BlockPos pos = ctx.getClickedPos();
				runAsPlayer(sp, cmds, Vec3.atBottomCenterOf(pos), null);
			}
			return InteractionResult.SUCCESS;
		}

		@Override
		public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
			List<String> cmds = strings(props(definition(stack)), "use_on_entity_commands");
			if (cmds.isEmpty()) return super.interactLivingEntity(stack, player, target, hand);
			if (player instanceof ServerPlayer sp) runAsPlayer(sp, cmds, target.position(), target);
			return InteractionResult.SUCCESS;
		}

		@Override
		public void postHurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
			super.postHurtEnemy(stack, target, attacker);
			List<String> cmds = strings(props(definition(stack)), "hit_commands");
			if (attacker instanceof ServerPlayer sp) runAsPlayer(sp, cmds, target.position(), target);
		}

		@Override
		public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
			JsonObject def = definition(stack); // read before eating shrinks the stack
			ItemStack result = super.finishUsingItem(stack, level, entity);
			if (def != null && !level.isClientSide() && entity instanceof ServerPlayer sp) ClaudeCraft.runItemCommands(sp, strings(props(def), "use_commands"));
			return result;
		}

		@Override
		public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
			refresh(stack);
			if (!(owner instanceof ServerPlayer sp) || slot == null || sp.tickCount % 20 != 0) return;
			JsonObject p = props(definition(stack));
			// Armor counts as worn only in the slot it's made for.
			Equippable eq = stack.get(DataComponents.EQUIPPABLE);
			boolean worn = slot.isArmor() && eq != null && eq.slot() == slot;
			boolean held = slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND;
			if (worn) ClaudeCraft.runItemCommands(sp, strings(p, "worn_commands"));
			if (held) ClaudeCraft.runItemCommands(sp, strings(p, "held_commands"));
		}
	}

	static void description(JsonObject def, Consumer<Component> out) {
		if (def != null && def.has("description")) {
			for (String line : def.get("description").getAsString().split("\n")) out.accept(Component.literal(line).withStyle(ChatFormatting.GRAY));
		}
	}

	/** Spawn eggs for custom creatures: a vanilla spawn egg whose entity data names the definition. */
	static final class CustomSpawnEgg extends SpawnEggItem {
		CustomSpawnEgg(Properties props) {
			super(props);
		}

		@Override
		public Component getName(ItemStack stack) {
			JsonObject def = definition(stack);
			return def == null ? super.getName(stack) : Component.literal(def.get("name").getAsString() + " Spawn Egg");
		}
	}
}
