package dev.claudecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.Consumables;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Items Claude designs while the game runs. Minecraft can't register new items after startup, so there is one
 * registered item, claudecraft:custom_item, and each stack names its definition in the claudecraft:definition
 * component. Definitions (name, voxel model, behavior) are saved with the world and sent to every client, which
 * draws the voxel model. This is why every player needs the mod.
 */
public final class CustomItems {
	private CustomItems() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int MAX_PAYLOAD = 8 * 1024 * 1024;

	public static DataComponentType<String> DEFINITION;
	public static Item ITEM;

	/**
	 * Definitions by id. The server fills it from the world save and clients from the server's packets. It's one
	 * shared map because in singleplayer both sides run in one process and hold the same data anyway.
	 */
	public static final Map<String, JsonObject> DEFS = new ConcurrentHashMap<>();
	/** Called on the client thread whenever definitions change, so the client can drop cached meshes. */
	public static volatile Runnable onClientChange = () -> {};

	private static Path saveFile;

	/** Sent to clients: a JSON array of definitions, either everything (replace) or just the changed ones. */
	public record DefinitionsPayload(String json, boolean replaceAll) implements CustomPacketPayload {
		public static final Type<DefinitionsPayload> TYPE = new Type<>(id("item_definitions"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DefinitionsPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(MAX_PAYLOAD), DefinitionsPayload::json,
			ByteBufCodecs.BOOL, DefinitionsPayload::replaceAll,
			DefinitionsPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(ClaudeCraft.MOD_ID, path);
	}

	/** Registration, from the common initializer (before registries freeze). */
	static void register() {
		DEFINITION = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id("definition"),
			DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id("custom_item"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, key, new CustomItem(new Item.Properties().setId(key)));
		PayloadTypeRegistry.clientboundPlay().registerLarge(DefinitionsPayload.TYPE, DefinitionsPayload.CODEC, MAX_PAYLOAD);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sender.sendPacket(payload(DEFS.values(), true)));
	}

	/** Load this world's definitions. Server thread, at startup. */
	static void load(MinecraftServer server) {
		saveFile = server.getWorldPath(LevelResource.ROOT).resolve("claudecraft").resolve("items.json");
		DEFS.clear();
		try {
			if (Files.exists(saveFile)) {
				for (var e : JsonParser.parseString(Files.readString(saveFile)).getAsJsonObject().entrySet()) {
					DEFS.put(e.getKey(), e.getValue().getAsJsonObject());
				}
			}
		} catch (Exception e) {
			ClaudeCraft.LOGGER.error("Couldn't read custom item definitions from {}", saveFile, e);
		}
	}

	/** Add or replace a definition, save it, and send it to everyone online. Server thread. */
	static void define(MinecraftServer server, JsonObject def) {
		DEFS.put(def.get("id").getAsString(), def);
		JsonObject all = new JsonObject();
		DEFS.forEach(all::add);
		try {
			Files.createDirectories(saveFile.getParent());
			Files.writeString(saveFile, GSON.toJson(all));
		} catch (Exception e) {
			ClaudeCraft.LOGGER.error("Couldn't save custom item definitions to {}", saveFile, e);
		}
		DefinitionsPayload p = payload(List.of(def), false);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) ServerPlayNetworking.send(player, p);
	}

	private static DefinitionsPayload payload(java.util.Collection<JsonObject> defs, boolean replaceAll) {
		JsonArray arr = new JsonArray();
		defs.forEach(arr::add);
		return new DefinitionsPayload(arr.toString(), replaceAll);
	}

	/** Apply a payload on the client. */
	public static void receive(DefinitionsPayload p) {
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (JsonElement e : JsonParser.parseString(p.json()).getAsJsonArray()) {
			JsonObject def = e.getAsJsonObject();
			ids.add(def.get("id").getAsString());
			DEFS.put(def.get("id").getAsString(), def);
		}
		// Not clear() first: in singleplayer the server reads this same map from its own thread.
		if (p.replaceAll()) DEFS.keySet().retainAll(ids);
		onClientChange.run();
	}

	public static JsonObject definition(ItemStack stack) {
		String id = stack.get(DEFINITION);
		return id == null ? null : DEFS.get(id);
	}

	private static JsonObject props(JsonObject def) {
		return def.has("properties") ? def.getAsJsonObject("properties") : new JsonObject();
	}

	private static List<String> commands(JsonObject def) {
		List<String> out = new ArrayList<>();
		JsonObject p = props(def);
		if (p.has("use_commands")) for (JsonElement e : p.getAsJsonArray("use_commands")) out.add(e.getAsString());
		return out;
	}

	/** A stack of a defined item with its behavior components (stack size, food, damage, durability...). */
	static ItemStack stack(JsonObject def, int count) {
		String id = def.get("id").getAsString();
		JsonObject p = props(def);
		ItemStack s = new ItemStack(ITEM);
		s.set(DEFINITION, id);
		int maxStack = p.has("max_stack") ? Math.max(1, Math.min(99, p.get("max_stack").getAsInt())) : 64;
		if (p.has("durability")) {
			maxStack = 1;
			s.set(DataComponents.MAX_DAMAGE, Math.max(1, p.get("durability").getAsInt()));
			s.set(DataComponents.DAMAGE, 0);
		}
		s.set(DataComponents.MAX_STACK_SIZE, maxStack);
		if (p.has("food")) {
			JsonObject f = p.getAsJsonObject("food");
			s.set(DataComponents.FOOD, new FoodProperties(f.has("nutrition") ? f.get("nutrition").getAsInt() : 4,
				f.has("saturation") ? f.get("saturation").getAsFloat() : 2.4f, f.has("always_edible") && f.get("always_edible").getAsBoolean()));
			s.set(DataComponents.CONSUMABLE, Consumables.DEFAULT_FOOD);
		}
		if (p.has("attack_damage") || p.has("attack_speed")) {
			ItemAttributeModifiers.Builder mods = ItemAttributeModifiers.builder();
			// Players have 1 base damage and 4 base attack speed; vanilla tools store the difference.
			if (p.has("attack_damage")) mods.add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID,
				p.get("attack_damage").getAsDouble() - 1, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
			if (p.has("attack_speed")) mods.add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID,
				p.get("attack_speed").getAsDouble() - 4, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
			s.set(DataComponents.ATTRIBUTE_MODIFIERS, mods.build());
			if (p.has("durability")) s.set(DataComponents.WEAPON, new Weapon(1)); // wears down when it hits
		}
		if (p.has("glint") && p.get("glint").getAsBoolean()) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		if (p.has("rarity")) s.set(DataComponents.RARITY, Rarity.valueOf(p.get("rarity").getAsString().toUpperCase(Locale.ROOT)));
		if (p.has("cooldown_seconds")) {
			s.set(DataComponents.USE_COOLDOWN, new UseCooldown(p.get("cooldown_seconds").getAsFloat(), Optional.of(id("item/" + id))));
		}
		s.setCount(Math.max(1, Math.min(maxStack, count)));
		return s;
	}

	/** The one registered custom item. Its name, tooltip and right-click behavior come from the stack's definition. */
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
			JsonObject def = definition(stack);
			if (def != null && def.has("description")) {
				for (String line : def.get("description").getAsString().split("\n")) out.accept(Component.literal(line).withStyle(ChatFormatting.GRAY));
			}
		}

		@Override
		public InteractionResult use(Level level, Player player, InteractionHand hand) {
			ItemStack stack = player.getItemInHand(hand);
			JsonObject def = definition(stack);
			// Food is eaten the vanilla way; its commands run once it's finished (see finishUsingItem).
			if (def == null || props(def).has("food") || commands(def).isEmpty()) return super.use(level, player, hand);
			if (!level.isClientSide() && player instanceof ServerPlayer sp) ClaudeCraft.runItemCommands(sp, commands(def));
			return InteractionResult.SUCCESS;
		}

		@Override
		public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
			JsonObject def = definition(stack); // read before eating shrinks the stack
			ItemStack result = super.finishUsingItem(stack, level, entity);
			if (def != null && !level.isClientSide() && entity instanceof ServerPlayer sp) ClaudeCraft.runItemCommands(sp, commands(def));
			return result;
		}
	}
}
