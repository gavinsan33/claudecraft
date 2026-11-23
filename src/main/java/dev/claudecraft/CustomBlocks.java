package dev.claudecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Blocks Claude designs while the game runs. Like custom items there is one registered block,
 * claudecraft:custom_block, but instead of a block entity each definition gets a numbered variant (a block
 * state property), so models, shapes, light and drops all work through ordinary block states.
 */
public final class CustomBlocks {
	private CustomBlocks() {}

	// ponytail: 256 block designs per world; raise SLOTS (4096 states now) if worlds run out.
	public static final int SLOTS = 256;
	public static final IntegerProperty VARIANT = IntegerProperty.create("variant", 0, SLOTS - 1);
	public static final IntegerProperty LIGHT = IntegerProperty.create("light", 0, 15);

	public static Block BLOCK;
	public static Item ITEM;

	/** Definition id per variant, rebuilt whenever definitions change. */
	private static volatile String[] slotIds = new String[SLOTS];
	private static final Map<String, VoxelShape> SHAPES = new ConcurrentHashMap<>();

	static void register() {
		ResourceKey<Block> bk = ResourceKey.create(Registries.BLOCK, CustomItems.id("custom_block"));
		BLOCK = Registry.register(BuiltInRegistries.BLOCK, bk, new CustomBlock(BlockBehaviour.Properties.of().setId(bk)
			.strength(1.5f, 6f).dynamicShape().noOcclusion().lightLevel(s -> s.getValue(LIGHT))));
		ResourceKey<Item> ik = ResourceKey.create(Registries.ITEM, CustomItems.id("custom_block"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, ik, new CustomBlockItem(BLOCK, new Item.Properties().setId(ik).useBlockDescriptionPrefix()));
	}

	static boolean isBlock(JsonObject def) {
		return def != null && def.has("kind") && def.get("kind").getAsString().equals("block");
	}

	/** Rebuild the variant table and drop cached shapes. Called whenever definitions change, on either side. */
	static void definitionsChanged() {
		String[] ids = new String[SLOTS];
		for (JsonObject def : CustomItems.DEFS.values()) {
			if (isBlock(def) && def.has("slot")) ids[def.get("slot").getAsInt()] = def.get("id").getAsString();
		}
		slotIds = ids;
		SHAPES.clear();
	}

	public static String idForSlot(int slot) {
		return slot >= 0 && slot < SLOTS ? slotIds[slot] : null;
	}

	public static JsonObject definition(BlockState state) {
		String id = idForSlot(state.getValue(VARIANT));
		return id == null ? null : CustomItems.DEFS.get(id);
	}

	/** The variant a new block definition should use: its old one when redefining, else the first free one. */
	static int allocateSlot(String id) {
		JsonObject old = CustomItems.DEFS.get(id);
		if (isBlock(old) && old.has("slot")) return old.get("slot").getAsInt();
		for (int i = 0; i < SLOTS; i++) if (slotIds[i] == null) return i;
		return -1;
	}

	static int light(JsonObject def) {
		JsonObject p = def.has("properties") ? def.getAsJsonObject("properties") : new JsonObject();
		return p.has("light") ? Math.max(0, Math.min(15, p.get("light").getAsInt())) : 0;
	}

	/** The block state that places this definition. */
	public static BlockState state(JsonObject def) {
		return BLOCK.defaultBlockState().setValue(VARIANT, def.get("slot").getAsInt()).setValue(LIGHT, light(def));
	}

	static ItemStack stack(JsonObject def, int count) {
		ItemStack s = new ItemStack(ITEM, Math.max(1, Math.min(64, count)));
		s.set(CustomItems.DEFINITION, def.get("id").getAsString());
		s.set(DataComponents.BLOCK_STATE, new BlockItemStateProperties(Map.of(
			"variant", String.valueOf(def.get("slot").getAsInt()), "light", String.valueOf(light(def)))));
		return s;
	}

	private static JsonObject props(JsonObject def) {
		return def != null && def.has("properties") ? def.getAsJsonObject("properties") : new JsonObject();
	}

	/** Outline shape from the voxels (merged into row boxes), cached per definition. */
	static VoxelShape shape(JsonObject def) {
		if (def == null) return Shapes.block();
		return SHAPES.computeIfAbsent(def.get("id").getAsString(), k -> {
			VoxelModel m;
			try {
				m = VoxelModel.parse(def.getAsJsonObject("model"));
			} catch (RuntimeException e) {
				return Shapes.block();
			}
			// Same placement as the mesh: centered in x and z, sitting on the floor.
			double ox = (16 - m.sx) / 2.0, oz = (16 - m.sz) / 2.0;
			List<VoxelShape> boxes = new ArrayList<>();
			for (int y = 0; y < Math.min(16, m.sy); y++) for (int z = 0; z < m.sz; z++) {
				for (int x = 0; x < m.sx; x++) {
					if (m.at(x, y, z) == 0) continue;
					int x1 = x;
					while (x1 + 1 < m.sx && m.at(x1 + 1, y, z) != 0) x1++;
					boxes.add(Block.box(ox + x, y, oz + z, ox + x1 + 1, y + 1, oz + z + 1));
					x = x1;
				}
			}
			if (boxes.isEmpty()) return Shapes.block();
			return Shapes.or(boxes.getFirst(), boxes.subList(1, boxes.size()).toArray(VoxelShape[]::new)).optimize();
		});
	}

	static SoundType sound(String name) {
		return switch (name) {
			case "wood" -> SoundType.WOOD;
			case "glass" -> SoundType.GLASS;
			case "metal" -> SoundType.METAL;
			case "grass" -> SoundType.GRASS;
			case "wool" -> SoundType.WOOL;
			case "sand" -> SoundType.SAND;
			case "gravel" -> SoundType.GRAVEL;
			case "snow" -> SoundType.SNOW;
			case "amethyst" -> SoundType.AMETHYST;
			case "slime" -> SoundType.SLIME_BLOCK;
			case "bone" -> SoundType.BONE_BLOCK;
			case "copper" -> SoundType.COPPER;
			case "deepslate" -> SoundType.DEEPSLATE;
			default -> SoundType.STONE;
		};
	}

	static final List<String> SOUNDS = List.of("stone", "wood", "glass", "metal", "grass", "wool", "sand", "gravel", "snow",
		"amethyst", "slime", "bone", "copper", "deepslate");

	static final class CustomBlock extends Block {
		CustomBlock(Properties props) {
			super(props);
			registerDefaultState(stateDefinition.any().setValue(VARIANT, 0).setValue(LIGHT, 0));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(VARIANT, LIGHT);
		}

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
			JsonObject def = definition(state);
			String collision = props(def).has("collision") ? props(def).get("collision").getAsString() : "model";
			return collision.equals("full") ? Shapes.block() : shape(def);
		}

		@Override
		protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
			JsonObject def = definition(state);
			if (props(def).has("collision") && props(def).get("collision").getAsString().equals("none")) return Shapes.empty();
			return getShape(state, level, pos, ctx);
		}

		@Override
		protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
			JsonObject p = props(definition(state));
			float hardness = p.has("hardness") ? p.get("hardness").getAsFloat() : 1.5f;
			if (hardness < 0) return 0; // unbreakable
			if (hardness == 0) return 1;
			return player.getDestroySpeed(state) / hardness / 30f; // like any block that needs no special tool
		}

		@Override
		protected SoundType getSoundType(BlockState state) {
			JsonObject p = props(definition(state));
			return sound(p.has("sound") ? p.get("sound").getAsString() : "stone");
		}

		@Override
		protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
			JsonObject p = props(definition(state));
			if (!p.has("use_commands")) return InteractionResult.PASS;
			if (!level.isClientSide() && player instanceof ServerPlayer sp) {
				List<String> cmds = new ArrayList<>();
				// Run at the block, so ~ ~ ~ is the block while @s is still the player.
				String at = "execute positioned " + (pos.getX() + 0.5) + " " + pos.getY() + " " + (pos.getZ() + 0.5) + " run ";
				for (JsonElement e : p.getAsJsonArray("use_commands")) cmds.add(at + e.getAsString().replaceFirst("^/", ""));
				ClaudeCraft.runItemCommands(sp, cmds);
			}
			return InteractionResult.SUCCESS;
		}

		@Override
		protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
			JsonObject def = definition(state);
			return def == null ? super.getCloneItemStack(level, pos, state, includeData) : stack(def, 1);
		}
	}

	/** The custom block's item: named and described by its definition (from the stack, or its variant). */
	static final class CustomBlockItem extends BlockItem {
		CustomBlockItem(Block block, Properties props) {
			super(block, props);
		}

		@Override
		public Component getName(ItemStack stack) {
			JsonObject def = CustomItems.definition(stack);
			return def == null ? super.getName(stack) : Component.literal(def.get("name").getAsString());
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
			JsonObject def = CustomItems.definition(stack);
			if (def != null && def.has("description")) {
				for (String line : def.get("description").getAsString().split("\n")) out.accept(Component.literal(line).withStyle(ChatFormatting.GRAY));
			}
		}
	}
}
