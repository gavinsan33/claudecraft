package dev.claudecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Blocks Claude designs while the game runs. Like custom items there is one registered block,
 * claudecraft:custom_block, but each definition gets a numbered variant (a block state property), so models,
 * shapes, light and drops all work through ordinary block states. Facing and redstone power are state properties
 * too; storage blocks get a block entity only where a definition asks for one.
 */
public final class CustomBlocks {
	private CustomBlocks() {}

	// ponytail: 256 block designs per world; with light, facing and power that's 32768 states.
	public static final int SLOTS = 256;
	public static final IntegerProperty VARIANT = IntegerProperty.create("variant", 0, SLOTS - 1);
	public static final IntegerProperty LIGHT = IntegerProperty.create("light", 0, 15);
	/** Which way the model's front (its south side) faces. Blocks that don't rotate stay south. */
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

	public static Block BLOCK;
	public static Item ITEM;
	public static BlockEntityType<Storage> STORAGE;

	/** Definition id per variant, rebuilt whenever definitions change. */
	private static volatile String[] slotIds = new String[SLOTS];
	private static final Map<String, VoxelShape> SHAPES = new ConcurrentHashMap<>();

	static final List<String> TOOLS = List.of("pickaxe", "axe", "shovel", "hoe");

	static void register() {
		ResourceKey<Block> bk = ResourceKey.create(Registries.BLOCK, CustomItems.id("custom_block"));
		BLOCK = Registry.register(BuiltInRegistries.BLOCK, bk, new CustomBlock(BlockBehaviour.Properties.of().setId(bk)
			.strength(1.5f, 6f).dynamicShape().noOcclusion().lightLevel(s -> s.getValue(LIGHT))));
		ResourceKey<Item> ik = ResourceKey.create(Registries.ITEM, CustomItems.id("custom_block"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, ik, new CustomBlockItem(BLOCK, new Item.Properties().setId(ik).useBlockDescriptionPrefix()));
		STORAGE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, CustomItems.id("storage"), new BlockEntityType<>(Storage::new, Set.of(BLOCK)));
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
		if (!state.is(BLOCK)) return null;
		String id = idForSlot(state.getValue(VARIANT));
		return id == null ? null : CustomItems.DEFS.get(id);
	}

	/** The variant a new block definition should use: its old one when redefining, else a never-used one, else a retired one. */
	static int allocateSlot(String id) {
		JsonObject old = CustomItems.DEFS.get(id);
		if (isBlock(old) && old.has("slot")) return old.get("slot").getAsInt();
		for (int i = 0; i < SLOTS; i++) if (slotIds[i] == null && !CustomItems.RETIRED_SLOTS.contains(i)) return i;
		for (int i = 0; i < SLOTS; i++) if (slotIds[i] == null) return i;
		return -1;
	}

	static int light(JsonObject def) {
		JsonObject p = props(def);
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
		JsonObject p = props(def);
		if (p.has("fuel_seconds")) {
			s.set(DataComponents.COOKING_FUEL, new net.minecraft.world.item.component.CookingFuel(
				new net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt.Constant(Math.max(1, Math.round(p.get("fuel_seconds").getAsFloat() * 20))),
				new net.minecraft.world.level.storage.loot.providers.number.floats.ResolvableFloat.Constant(1f)));
		}
		return s;
	}

	static JsonObject props(JsonObject def) {
		return CustomItems.props(def);
	}

	static boolean rotates(JsonObject def) {
		JsonObject p = props(def);
		return p.has("rotate") && p.get("rotate").getAsBoolean();
	}

	/** Rotate a point in the block (0..1) so the model's south side faces the given way. */
	public static double[] rotate(double x, double z, Direction facing) {
		return switch (facing) {
			case NORTH -> new double[]{1 - x, 1 - z};
			case EAST -> new double[]{z, 1 - x};
			case WEST -> new double[]{1 - z, x};
			default -> new double[]{x, z};
		};
	}

	/** Outline shape from the voxels (merged into row boxes), cached per definition and facing. */
	static VoxelShape shape(JsonObject def, Direction facing) {
		if (def == null) return Shapes.block();
		return SHAPES.computeIfAbsent(def.get("id").getAsString() + "/" + facing.getName(), k -> {
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
					double[] a = rotate((ox + x) / 16, (oz + z) / 16, facing), b = rotate((ox + x1 + 1) / 16, (oz + z + 1) / 16, facing);
					boxes.add(Shapes.box(Math.min(a[0], b[0]), y / 16.0, Math.min(a[1], b[1]), Math.max(a[0], b[0]), (y + 1) / 16.0, Math.max(a[1], b[1])));
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

	/** A vanilla block each tool type is made for, to ask any held item how well it mines like that tool. */
	private static BlockState probe(String tool) {
		return switch (tool) {
			case "axe" -> Blocks.OAK_PLANKS.defaultBlockState();
			case "shovel" -> Blocks.DIRT.defaultBlockState();
			case "hoe" -> Blocks.HAY_BLOCK.defaultBlockState();
			default -> Blocks.STONE.defaultBlockState();
		};
	}

	/** A vanilla block that needs exactly this pickaxe tier to drop. */
	private static BlockState tierProbe(String tier) {
		return switch (tier) {
			case "stone", "copper" -> Blocks.IRON_ORE.defaultBlockState();
			case "iron" -> Blocks.DIAMOND_ORE.defaultBlockState();
			case "diamond", "netherite" -> Blocks.OBSIDIAN.defaultBlockState();
			default -> Blocks.STONE.defaultBlockState();
		};
	}

	/** Is the tool the right kind (and, for pickaxes, tier) for this block? True when the block doesn't care. */
	static boolean correctTool(JsonObject def, ItemStack tool) {
		JsonObject p = props(def);
		if (!p.has("tool")) return true;
		String type = p.get("tool").getAsString();
		if (tool == null || tool.getDestroySpeed(probe(type)) <= 1) return false;
		if (!type.equals("pickaxe") || !p.has("tier")) return true;
		return tool.isCorrectToolForDrops(tierProbe(p.get("tier").getAsString()));
	}

	private static boolean requiresTool(JsonObject def) {
		JsonObject p = props(def);
		return p.has("tool") && (!p.has("requires_tool") || p.get("requires_tool").getAsBoolean());
	}

	static final class CustomBlock extends Block implements EntityBlock {
		/** When each entity last set off a block's step commands, so standing still doesn't run them every tick. */
		private static final Map<Entity, Long> LAST_STEP = new WeakHashMap<>();

		CustomBlock(Properties props) {
			super(props);
			registerDefaultState(stateDefinition.any().setValue(VARIANT, 0).setValue(LIGHT, 0).setValue(FACING, Direction.SOUTH).setValue(POWERED, false));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(VARIANT, LIGHT, FACING, POWERED);
		}

		@Override
		public BlockState getStateForPlacement(BlockPlaceContext ctx) {
			BlockState st = defaultBlockState();
			JsonObject def = CustomItems.definition(ctx.getItemInHand());
			if (def != null && isBlock(def)) {
				st = state(def);
				if (rotates(def)) st = st.setValue(FACING, ctx.getHorizontalDirection().getOpposite());
			}
			return st.setValue(POWERED, ctx.getLevel().hasNeighborSignal(ctx.getClickedPos()));
		}

		@Override
		protected BlockState rotate(BlockState state, Rotation rotation) {
			return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
		}

		@Override
		protected BlockState mirror(BlockState state, Mirror mirror) {
			return state.rotate(mirror.getRotation(state.getValue(FACING)));
		}

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
			JsonObject def = definition(state);
			String collision = props(def).has("collision") ? props(def).get("collision").getAsString() : "model";
			return collision.equals("full") ? Shapes.block() : shape(def, state.getValue(FACING));
		}

		@Override
		protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
			JsonObject def = definition(state);
			if (props(def).has("collision") && props(def).get("collision").getAsString().equals("none")) return Shapes.empty();
			return getShape(state, level, pos, ctx);
		}

		@Override
		protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
			JsonObject def = definition(state);
			JsonObject p = props(def);
			float hardness = p.has("hardness") ? p.get("hardness").getAsFloat() : 1.5f;
			if (hardness < 0) return 0; // unbreakable
			if (hardness == 0) return 1;
			// player.getDestroySpeed sees a plain block (speed 1) plus haste, fatigue, water; scale that by the tool.
			float speed = player.getDestroySpeed(state);
			ItemStack held = player.getMainHandItem();
			boolean correct = correctTool(def, held);
			if (p.has("tool") && correct) {
				speed *= held.getDestroySpeed(probe(p.get("tool").getAsString())) + (float) player.getAttributeValue(Attributes.MINING_EFFICIENCY);
			}
			return speed / hardness / (correct || !requiresTool(def) ? 30f : 100f);
		}

		@Override
		protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
			JsonObject def = definition(state);
			if (def == null) return List.of();
			ItemStack tool = params.getOptionalParameter(LootContextParams.TOOL) instanceof ItemStack s ? s : null;
			if (requiresTool(def) && !correctTool(def, tool)) return List.of();
			Float radius = params.getOptionalParameter(LootContextParams.EXPLOSION_RADIUS);
			ServerLevel level = params.getLevel();
			if (radius != null && radius > 0 && level.getRandom().nextFloat() > 1 / radius) return List.of();
			List<String> drops = CustomItems.strings(props(def), "drops");
			if (drops.isEmpty() || silkTouch(level, tool)) return List.of(stack(def, 1));
			return CustomEntities.rollDrops(drops, level.getRandom());
		}

		private static boolean silkTouch(ServerLevel level, ItemStack tool) {
			if (tool == null || tool.isEmpty()) return false;
			var silk = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(Enchantments.SILK_TOUCH);
			return silk.isPresent() && EnchantmentHelper.getItemEnchantmentLevel(silk.get(), tool) > 0;
		}

		@Override
		protected void spawnAfterBreak(BlockState state, ServerLevel level, BlockPos pos, ItemStack tool, boolean dropExperience) {
			super.spawnAfterBreak(state, level, pos, tool, dropExperience);
			JsonObject def = definition(state);
			JsonObject p = props(def);
			if (!dropExperience || !p.has("xp") || silkTouch(level, tool) || requiresTool(def) && !correctTool(def, tool)) return;
			JsonElement xp = p.get("xp");
			int min, max;
			if (xp.isJsonArray() && xp.getAsJsonArray().size() == 2) {
				min = xp.getAsJsonArray().get(0).getAsInt();
				max = xp.getAsJsonArray().get(1).getAsInt();
			} else {
				min = max = xp.getAsInt();
			}
			int n = min + (max > min ? level.getRandom().nextInt(max - min + 1) : 0);
			if (n > 0) popExperience(level, pos, n);
		}

		@Override
		protected SoundType getSoundType(BlockState state) {
			JsonObject p = props(definition(state));
			return sound(p.has("sound") ? p.get("sound").getAsString() : "stone");
		}

		private static Vec3 center(BlockPos pos) {
			return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		}

		@Override
		protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
			JsonObject def = definition(state);
			JsonObject p = props(def);
			boolean storage = rows(def) > 0;
			if (!p.has("use_commands") && !storage) return InteractionResult.PASS;
			if (!level.isClientSide() && player instanceof ServerPlayer sp) {
				CustomItems.runAsPlayer(sp, CustomItems.strings(p, "use_commands"), center(pos), null);
				if (storage) {
					Storage box = level.getBlockEntity(pos) instanceof Storage s ? s : null;
					if (box == null) { // the definition gained storage after this block was placed
						box = new Storage(pos, state);
						level.setBlockEntity(box);
					}
					sp.openMenu(box);
				}
			}
			return InteractionResult.SUCCESS;
		}

		@Override
		protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
			JsonObject def = definition(state);
			return def == null ? super.getCloneItemStack(level, pos, state, includeData) : stack(def, 1);
		}

		// -------------------------------------------------------- storage

		static int rows(JsonObject def) {
			JsonObject p = props(def);
			return p.has("storage") ? Math.max(0, Math.min(6, p.get("storage").getAsInt())) : 0;
		}

		@Override
		public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return rows(definition(state)) > 0 ? new Storage(pos, state) : null;
		}

		@Override
		protected boolean hasAnalogOutputSignal(BlockState state) {
			return rows(definition(state)) > 0;
		}

		@Override
		protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
			return level.getBlockEntity(pos) instanceof Storage s ? net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromContainer(s) : 0;
		}

		// -------------------------------------------------------- redstone

		@Override
		protected boolean isSignalSource(BlockState state) {
			return power(definition(state)) > 0;
		}

		@Override
		protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
			return power(definition(state));
		}

		static int power(JsonObject def) {
			JsonObject p = props(def);
			return p.has("power") ? Math.max(0, Math.min(15, p.get("power").getAsInt())) : 0;
		}

		@Override
		protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, Orientation orientation, boolean movedByPiston) {
			if (!(level instanceof ServerLevel sl)) return;
			boolean powered = level.hasNeighborSignal(pos);
			if (powered == state.getValue(POWERED)) return;
			level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
			JsonObject p = props(definition(state));
			ClaudeCraft.runAt(sl, CustomItems.strings(p, powered ? "powered_commands" : "unpowered_commands"), center(pos));
		}

		// -------------------------------------------------------- ticking and stepping

		@Override
		protected boolean isRandomlyTicking(BlockState state) {
			return true; // cached per state at startup, before any definition exists; randomTick checks the definition
		}

		@Override
		protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			List<String> cmds = CustomItems.strings(props(definition(state)), "random_tick_commands");
			if (!cmds.isEmpty()) ClaudeCraft.runAt(level, cmds, center(pos));
		}

		@Override
		public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
			super.stepOn(level, pos, state, entity);
			if (!(level instanceof ServerLevel) || entity.isSteppingCarefully()) return;
			List<String> cmds = CustomItems.strings(props(definition(state)), "step_commands");
			if (cmds.isEmpty()) return;
			long now = level.getGameTime();
			Long last = LAST_STEP.get(entity);
			if (last != null && now - last < 20) return;
			LAST_STEP.put(entity, now);
			ClaudeCraft.runAs(entity, cmds, center(pos).add(0, 1, 0), entity);
		}
	}

	/** The inventory of a storage block: a chest-style grid with the definition's number of rows. */
	public static final class Storage extends BaseContainerBlockEntity {
		private NonNullList<ItemStack> items = NonNullList.withSize(54, ItemStack.EMPTY);

		Storage(BlockPos pos, BlockState state) {
			super(STORAGE, pos, state);
		}

		private int rows() {
			int r = CustomBlock.rows(definition(getBlockState()));
			return r > 0 ? r : 3;
		}

		@Override
		protected Component getDefaultName() {
			JsonObject def = definition(getBlockState());
			return Component.literal(def != null ? def.get("name").getAsString() : "Storage");
		}

		@Override
		protected NonNullList<ItemStack> getItems() {
			return items;
		}

		@Override
		protected void setItems(NonNullList<ItemStack> items) {
			this.items = items;
		}

		@Override
		protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
			int rows = rows();
			MenuType<?> type = switch (rows) {
				case 1 -> MenuType.GENERIC_9x1;
				case 2 -> MenuType.GENERIC_9x2;
				case 4 -> MenuType.GENERIC_9x4;
				case 5 -> MenuType.GENERIC_9x5;
				case 6 -> MenuType.GENERIC_9x6;
				default -> MenuType.GENERIC_9x3;
			};
			return new ChestMenu(type, id, inventory, this, rows);
		}

		@Override
		public int getContainerSize() {
			return 54;
		}

		@Override
		protected void loadAdditional(ValueInput in) {
			super.loadAdditional(in);
			items = NonNullList.withSize(54, ItemStack.EMPTY);
			ContainerHelper.loadAllItems(in, items);
		}

		@Override
		protected void saveAdditional(ValueOutput out) {
			super.saveAdditional(out);
			ContainerHelper.saveAllItems(out, items);
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
			CustomItems.description(CustomItems.definition(stack), out);
		}

		@Override
		public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, net.minecraft.world.entity.EquipmentSlot slot) {
			// Blocks mined before drops named their definition: name it now, so commands and recipes can match them.
			if (!stack.has(CustomItems.DEFINITION)) {
				JsonObject def = CustomItems.definition(stack);
				if (def != null) stack.set(CustomItems.DEFINITION, def.get("id").getAsString());
			}
		}
	}
}
