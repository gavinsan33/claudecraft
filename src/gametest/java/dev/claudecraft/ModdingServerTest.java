package dev.claudecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Headless server tests for the modding features: run with ./gradlew runGameTest. Each test defines its own
 * custom things through the same tool handlers Claude uses, so the validation is exercised too.
 */
public class ModdingServerTest {
	private static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	private static Object tool(String name, String args) {
		try {
			return ClaudeCraft.tools().runTool(name, json(args));
		} catch (Exception e) {
			throw new AssertionError(name + " failed: " + e.getMessage(), e);
		}
	}

	private static String toolError(String name, String args) {
		try {
			ClaudeCraft.tools().runTool(name, json(args));
		} catch (GameTools.ToolError e) {
			return e.getMessage();
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		throw new AssertionError(name + " should have been rejected: " + args);
	}

	private static final String PIXELS = "\"palette\":{\"a\":\"#C02030\",\"b\":\"#601018\"},\"pixels\":[\"ab\",\"ba\"]";

	private static void item(String id, String props) {
		tool("define_item", "{\"id\":\"" + id + "\",\"name\":\"" + id + "\"," + PIXELS + ",\"properties\":" + props + "}");
	}

	/** A block whose model is an L shape, so turning it changes its outline. */
	private static void block(String id, String props) {
		tool("define_block", "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"palette\":{\"s\":\"#808080\"},"
			+ "\"layers\":[[\"ss\",\"s.\"]],\"properties\":" + props + "}");
	}

	private static void creature(String id, String props) {
		tool("define_entity", "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"palette\":{\"w\":\"#E0E0E0\",\"k\":\"#202020\"},"
			+ "\"parts\":[{\"role\":\"body\",\"origin\":[-3,2,-4],\"layers\":[[\"wwwwww\",\"wwwwww\",\"wwwwww\",\"kkkkkk\"],[\"wwwwww\",\"wwwwww\",\"wwwwww\",\"wwwwww\"]]}],"
			+ "\"properties\":" + props + "}");
	}

	private static JsonObject def(String id) {
		return CustomItems.DEFS.get(id);
	}

	@GameTest
	public void itemsGetToolArmorFuelAndRefresh(GameTestHelper h) {
		item("t_pick", "{\"tool\":{\"type\":\"pickaxe\",\"tier\":\"diamond\"},\"durability\":100}");
		ItemStack pick = CustomItems.stack(def("t_pick"), 1);
		h.assertTrue(pick.isCorrectToolForDrops(Blocks.OBSIDIAN.defaultBlockState()), "a diamond-tier pickaxe should mine obsidian");
		h.assertTrue(!pick.isCorrectToolForDrops(Blocks.OAK_LOG.defaultBlockState()) || pick.getDestroySpeed(Blocks.OAK_LOG.defaultBlockState()) <= 1, "a pickaxe isn't an axe");

		item("t_crown", "{\"armor\":{\"slot\":\"head\",\"armor\":3}}");
		var crown = CustomItems.stack(def("t_crown"), 1).get(DataComponents.EQUIPPABLE);
		h.assertTrue(crown != null && crown.slot() == EquipmentSlot.HEAD && crown.assetId().isEmpty(), "a crown is worn on the head as its own model");
		item("t_boots", "{\"armor\":{\"slot\":\"feet\",\"armor\":2,\"look\":\"leather\",\"color\":\"#3050FF\"}}");
		ItemStack boots = CustomItems.stack(def("t_boots"), 1);
		h.assertTrue(boots.get(DataComponents.DYED_COLOR) != null && boots.get(DataComponents.DYED_COLOR).rgb() == 0x3050FF, "leather-look boots are dyed");

		item("t_coal", "{\"fuel_seconds\":10}");
		h.assertTrue(CustomItems.stack(def("t_coal"), 1).has(DataComponents.COOKING_FUEL), "fuel_seconds makes it fuel");

		item("t_stack", "{\"max_stack\":16}");
		ItemStack held = CustomItems.stack(def("t_stack"), 1);
		item("t_stack", "{\"max_stack\":8}");
		CustomItems.refresh(held);
		h.assertValueEqual(held.getMaxStackSize(), 8, "max stack after redefining");

		String err = toolError("define_item", "{\"id\":\"t_bad\",\"name\":\"x\"," + PIXELS + ",\"properties\":{\"tool\":{\"type\":\"spoon\"}}}");
		h.assertTrue(err.contains("tool.type"), "a bad tool type is rejected: " + err);
		h.succeed();
	}

	@GameTest
	public void commandsUnderstandCustomIds(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		item("c_gem", "{\"max_stack\":16}");
		block("c_block", "{}");
		creature("c_mob", "{\"health\":30}");
		BlockPos stone = h.absolutePos(new BlockPos(1, 1, 1));
		BlockPos chest = h.absolutePos(new BlockPos(3, 1, 1));
		BlockPos mob = h.absolutePos(new BlockPos(5, 1, 5));
		h.setBlock(new BlockPos(3, 1, 1), Blocks.CHEST);
		ClaudeCraft.runAt(level, List.of(
			"setblock " + stone.getX() + " " + stone.getY() + " " + stone.getZ() + " custom:c_block",
			"item replace block " + chest.getX() + " " + chest.getY() + " " + chest.getZ() + " container.0 with custom:c_gem 5",
			"summon custom:c_mob " + mob.getX() + " " + mob.getY() + " " + mob.getZ() + " {CustomName:\"Bob\"}"), Vec3.atCenterOf(stone));

		h.assertTrue("c_block".equals(CustomBlocks.idForSlot(level.getBlockState(stone).getValue(CustomBlocks.VARIANT))) && level.getBlockState(stone).is(CustomBlocks.BLOCK),
			"setblock custom:c_block places the custom block");
		ItemStack inChest = ((ChestBlockEntity) level.getBlockEntity(chest)).getItem(0);
		h.assertTrue("c_gem".equals(inChest.get(CustomItems.DEFINITION)) && inChest.getCount() == 5 && inChest.getMaxStackSize() == 16,
			"item replace ... with custom:c_gem gives the real item with its components, got " + inChest);
		var mobs = level.getEntitiesOfClass(CustomEntities.CustomEntity.class, new AABB(mob).inflate(2), e -> e.definitionId().equals("c_mob"));
		h.assertTrue(mobs.size() == 1, "summon custom:c_mob spawns it");
		h.assertTrue(mobs.getFirst().getHealth() == 30, "summoned creatures start at full health, got " + mobs.getFirst().getHealth());
		h.assertTrue(mobs.getFirst().getCustomName() != null, "summon keeps the extra NBT");

		ClaudeCraft.runAt(level, List.of("kill @e[type=custom:c_mob]"), Vec3.atCenterOf(mob));
		h.runAfterDelay(2, () -> {
			h.assertTrue(level.getEntitiesOfClass(CustomEntities.CustomEntity.class, new AABB(mob).inflate(2), e -> e.definitionId().equals("c_mob") && e.isAlive()).isEmpty(),
				"kill @e[type=custom:c_mob] finds it");
			h.succeed();
		});
	}

	@GameTest
	public void spawnEggsSpawnTheirCreature(GameTestHelper h) {
		creature("e_mob", "{\"health\":24}");
		ItemStack egg = CustomItems.stack(def("e_mob"), 1);
		h.assertTrue(egg.getItem() == CustomItems.SPAWN_EGG, "give_item for a creature gives its spawn egg");
		h.assertTrue(egg.getHoverName().getString().equals("e_mob Spawn Egg"), "egg name: " + egg.getHoverName().getString());
		var e = CustomEntities.TYPE.spawn(h.getLevel(), egg, null, h.absolutePos(new BlockPos(2, 1, 2)), EntitySpawnReason.SPAWN_ITEM_USE, true, false);
		h.assertTrue(e != null && e.definitionId().equals("e_mob"), "the egg spawns the creature");
		h.assertTrue(e.getHealth() == 24 && e.getMaxHealth() == 24, "spawned at full health, got " + e.getHealth() + "/" + e.getMaxHealth());
		h.assertTrue(e.entityTags().contains("custom.e_mob"), "creatures carry their selector tag");
		h.succeed();
	}

	@GameTest
	public void blocksNeedToolsDropLootAndRotate(GameTestHelper h) {
		item("b_gem", "{}");
		block("b_ore", "{\"tool\":\"pickaxe\",\"tier\":\"iron\",\"drops\":[\"custom:b_gem 2\"],\"xp\":[1,3]}");
		BlockPos pos = h.absolutePos(new BlockPos(1, 1, 1));
		BlockState ore = CustomBlocks.state(def("b_ore"));
		h.setBlock(new BlockPos(1, 1, 1), ore);
		List<ItemStack> withDiamond = Block.getDrops(ore, h.getLevel(), pos, null, null, new ItemStack(Items.DIAMOND_PICKAXE));
		h.assertTrue(withDiamond.size() == 1 && "b_gem".equals(withDiamond.getFirst().get(CustomItems.DEFINITION)) && withDiamond.getFirst().getCount() == 2,
			"a diamond pickaxe gets the gems, got " + withDiamond);
		h.assertTrue(Block.getDrops(ore, h.getLevel(), pos, null, null, new ItemStack(Items.WOODEN_PICKAXE)).isEmpty(), "a wooden pickaxe gets nothing");
		h.assertTrue(Block.getDrops(ore, h.getLevel(), pos, null, null, ItemStack.EMPTY).isEmpty(), "bare hands get nothing");

		block("b_plain", "{}");
		List<ItemStack> plain = Block.getDrops(CustomBlocks.state(def("b_plain")), h.getLevel(), pos, null, null, ItemStack.EMPTY);
		h.assertTrue(plain.size() == 1 && "b_plain".equals(plain.getFirst().get(CustomItems.DEFINITION)), "plain blocks drop themselves, named");

		block("b_turn", "{\"rotate\":true}");
		// The L's missing corner is at +x +z facing south, and at -x -z once turned to face north.
		var corner = net.minecraft.world.phys.shapes.Shapes.box(8.4 / 16, 0.4 / 16, 8.4 / 16, 8.6 / 16, 0.6 / 16, 8.6 / 16);
		var opposite = net.minecraft.world.phys.shapes.Shapes.box(7.4 / 16, 0.4 / 16, 7.4 / 16, 7.6 / 16, 0.6 / 16, 7.6 / 16);
		var and = net.minecraft.world.phys.shapes.BooleanOp.AND;
		var south = CustomBlocks.shape(def("b_turn"), Direction.SOUTH);
		var north = CustomBlocks.shape(def("b_turn"), Direction.NORTH);
		h.assertTrue(!net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(south, corner, and) && net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(south, opposite, and),
			"facing south, the L's gap is at +x +z");
		h.assertTrue(net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(north, corner, and) && !net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(north, opposite, and),
			"facing north, the gap turns to -x -z");
		h.succeed();
	}

	@GameTest
	public void blocksReactToRedstoneAndStoreItems(GameTestHelper h) {
		block("r_bell", "{\"powered_commands\":[\"setblock ~ ~1 ~ minecraft:gold_block\"]}");
		block("r_box", "{\"storage\":2}");
		block("r_source", "{\"power\":15}");
		h.setBlock(new BlockPos(1, 1, 1), CustomBlocks.state(def("r_bell")));
		h.setBlock(new BlockPos(2, 1, 1), CustomBlocks.state(def("r_source")));
		h.setBlock(new BlockPos(4, 1, 1), CustomBlocks.state(def("r_box")));
		h.assertTrue(h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(4, 1, 1))) instanceof CustomBlocks.Storage, "storage blocks get an inventory");
		h.assertTrue(h.getLevel().getSignal(h.absolutePos(new BlockPos(3, 1, 1)), Direction.EAST) == 15 || h.getLevel().hasNeighborSignal(h.absolutePos(new BlockPos(1, 1, 1))),
			"a powered block gives off redstone");
		// The source next to the bell is placed after it, so the bell hears the change.
		h.succeedWhen(() -> h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(1, 2, 1))).is(Blocks.GOLD_BLOCK), "powered_commands ran"));
	}

	@GameTest(maxTicks = 100)
	public void creaturesTameBreedAndShoot(GameTestHelper h) {
		creature("m_pet", "{\"food\":[\"minecraft:wheat\"],\"tame\":{\"items\":[\"minecraft:bone\"],\"chance\":1},"
			+ "\"behavior\":\"neutral\",\"ranged\":{\"projectile\":\"minecraft:snowball\",\"damage\":2}}");
		ServerLevel level = h.getLevel();
		Vec3 at = h.absoluteVec(new Vec3(3, 1, 3));
		var two = CustomEntities.spawn(level, def("m_pet"), at, 2);
		var a = two.get(0);
		var b = two.get(1);
		ServerPlayer player = h.makeMockServerPlayerInLevel();
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BONE));
		a.mobInteract(player, InteractionHand.MAIN_HAND);
		h.assertTrue(a.isTame() && a.isOwnedBy(player), "feeding a bone with chance 1 tames it");

		a.spawnChildFromBreeding(level, b);
		var babies = level.getEntitiesOfClass(CustomEntities.CustomEntity.class, new AABB(BlockPos.containing(at)).inflate(4), e -> e.isBaby() && e.definitionId().equals("m_pet"));
		h.assertTrue(babies.size() == 1, "breeding makes a baby of the same kind, got " + babies.size());
		h.assertTrue(babies.getFirst().getBbHeight() < a.getBbHeight(), "babies are smaller");

		a.performRangedAttack(b, 1);
		var shots = level.getEntitiesOfClass(CustomProjectiles.Thrown.class, new AABB(BlockPos.containing(at)).inflate(4));
		h.assertTrue(shots.size() == 1 && shots.getFirst().getItem().is(Items.SNOWBALL), "ranged attack throws the projectile item");
		h.succeed();
	}

	@GameTest
	public void hitCommandsTargetTheVictim(GameTestHelper h) {
		item("h_wand", "{\"attack_damage\":3,\"hit_commands\":[\"tag @target add zapped\"]}");
		creature("h_dummy", "{}");
		var target = CustomEntities.spawn(h.getLevel(), def("h_dummy"), h.absoluteVec(new Vec3(2, 1, 2)), 1).getFirst();
		ServerPlayer player = h.makeMockServerPlayerInLevel();
		ItemStack wand = CustomItems.stack(def("h_wand"), 1);
		wand.getItem().postHurtEnemy(wand, target, player);
		h.assertTrue(target.entityTags().contains("zapped"), "@target in hit_commands is the creature that was hit");
		h.succeed();
	}

	@GameTest
	public void recipesMatchByDefinition(GameTestHelper h) {
		item("p_gem", "{}");
		item("p_other", "{}");
		item("p_sword", "{\"attack_damage\":7,\"recipes\":[{\"type\":\"shaped\",\"pattern\":[\"G\",\"G\",\"S\"],\"key\":{\"G\":\"custom:p_gem\",\"S\":\"minecraft:stick\"}}]}");
		var server = h.getLevel().getServer();
		JsonObject recipeJson = CustomRecipes.toJson(server, def("p_sword"), CustomItems.props(def("p_sword")).getAsJsonArray("recipes").get(0).getAsJsonObject());
		Recipe<?> recipe = Recipe.CODEC.parse(server.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), recipeJson).getOrThrow().value();
		ItemStack gem = CustomItems.stack(def("p_gem"), 1), other = CustomItems.stack(def("p_other"), 1), stick = new ItemStack(Items.STICK);
		CraftingInput good = CraftingInput.of(1, 3, List.of(gem, gem.copy(), stick));
		CraftingInput bad = CraftingInput.of(1, 3, List.of(other, other.copy(), stick));
		CraftingRecipe crafting = (CraftingRecipe) recipe;
		h.assertTrue(crafting.matches(good, h.getLevel()), "two gems and a stick make the sword");
		h.assertTrue(!crafting.matches(bad, h.getLevel()), "other custom items don't count as gems");
		ItemStack out = crafting.assemble(good);
		h.assertTrue("p_sword".equals(out.get(CustomItems.DEFINITION)) && out.has(DataComponents.ATTRIBUTE_MODIFIERS), "the result is the real sword: " + out);

		String err = toolError("define_item", "{\"id\":\"p_bad\",\"name\":\"x\"," + PIXELS + ",\"properties\":{\"recipes\":[{\"type\":\"shapeless\",\"ingredients\":[\"custom:nope\"]}]}}");
		h.assertTrue(err.contains("Recipe 1"), "a recipe with an unknown ingredient is rejected: " + err);
		h.succeed();
	}

	@GameTest(maxTicks = 400)
	public void recipesLoadIntoTheGame(GameTestHelper h) {
		item("q_gem", "{}");
		item("q_ring", "{\"recipes\":[{\"type\":\"shapeless\",\"ingredients\":[\"custom:q_gem\",\"minecraft:gold_ingot\"]},"
			+ "{\"type\":\"smelting\",\"ingredient\":\"minecraft:gold_block\",\"xp\":1}]}");
		var server = h.getLevel().getServer();
		var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, CustomItems.id("q_ring_2"));
		// The data pack reload finishes on a later tick.
		h.succeedWhen(() -> h.assertTrue(server.getRecipeManager().byKey(key).isPresent(), "the smelting recipe is loaded"));
	}

	@GameTest
	public void creaturesSpawnNaturally(GameTestHelper h) {
		creature("n_bunny", "{\"spawning\":{\"chance\":1,\"where\":\"air\",\"group\":[1,1],\"max_nearby\":3}}");
		ServerLevel level = h.getLevel();
		ServerPlayer player = h.makeMockServerPlayerInLevel();
		Vec3 here = h.absoluteVec(new Vec3(4, 1, 4));
		player.snapTo(here.x, here.y, here.z);
		// Keep the chunks around the test loaded, as a real player would.
		var center = net.minecraft.world.level.ChunkPos.containing(BlockPos.containing(here));
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) level.getChunk(center.x() + dx, center.z() + dz);
		// Places 24 to 48 blocks away may not be loaded around a test, so give it several tries.
		for (int i = 0; i < 60; i++) CreatureSpawning.tryOne(level, player, def("n_bunny"));
		var spawned = level.getEntitiesOfClass(CustomEntities.CustomEntity.class, player.getBoundingBox().inflate(64), e -> e.definitionId().equals("n_bunny"));
		h.assertTrue(!spawned.isEmpty(), "natural spawning placed some");
		h.assertTrue(spawned.size() <= 3, "but no more than max_nearby, got " + spawned.size());
		h.assertTrue(spawned.stream().allMatch(e -> e.natural && e.removeWhenFarAway(10000)), "natural spawns despawn when players leave");
		spawned.forEach(e -> e.discard());
		h.succeed();
	}

	@GameTest
	public void oresGenerateOncePerChunk(GameTestHelper h) {
		BlockPos base = h.absolutePos(new BlockPos(0, 1, 0));
		block("o_ore", "{\"generate\":{\"replace\":[\"minecraft:air\"],\"min_y\":" + (base.getY() + 2) + ",\"max_y\":" + (base.getY() + 6)
			+ ",\"veins_per_chunk\":8,\"vein_size\":6}}");
		ServerLevel level = h.getLevel();
		var chunk = level.getChunkAt(base);
		h.assertTrue(OreGeneration.populate(level, chunk, List.of(def("o_ore"))), "the first visit generates veins");
		h.assertTrue(!OreGeneration.populate(level, chunk, List.of(def("o_ore"))), "the second visit does nothing");
		int found = 0;
		var p = new BlockPos.MutableBlockPos();
		for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = base.getY() + 1; y <= base.getY() + 7; y++) {
			p.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
			BlockState st = level.getBlockState(p);
			if (st.is(CustomBlocks.BLOCK) && "o_ore".equals(CustomBlocks.idForSlot(st.getValue(CustomBlocks.VARIANT)))) found++;
		}
		h.assertTrue(found > 0, "veins were placed");
		h.succeed();
	}

	@GameTest
	public void deleteAndLibraryRoundTrip(GameTestHelper h) {
		block("l_lamp", "{\"light\":12}");
		tool("library", "{\"action\":\"save\",\"id\":\"l_lamp\"}");
		tool("delete_definition", "{\"id\":\"l_lamp\"}");
		h.assertTrue(def("l_lamp") == null, "deleted");
		String listed = (String) tool("list_definitions", "{}");
		h.assertTrue(listed.contains("l_lamp"), "the library lists it: " + listed);
		tool("library", "{\"action\":\"load\",\"id\":\"l_lamp\"}");
		h.assertTrue(CustomBlocks.isBlock(def("l_lamp")) && CustomBlocks.light(def("l_lamp")) == 12, "loaded back from the library");
		h.succeed();
	}

	@GameTest(maxTicks = 400)
	public void dimensionsAreCreatedVisitedAndRemoved(GameTestHelper h) {
		var server = h.getLevel().getServer();
		tool("define_dimension", "{\"id\":\"g_moon\",\"name\":\"Moon\",\"sky\":\"starry\",\"biome\":\"alien\",\"reset\":true,"
			+ "\"terrain\":{\"style\":\"hills\",\"surface\":\"minecraft:snow_block\",\"under\":\"minecraft:andesite\",\"sea_level\":-100,\"decorate\":false}}");
		ServerLevel moon = server.getLevel(Dimensions.key("g_moon"));
		h.assertTrue(moon != null, "the dimension exists right away");
		h.assertTrue(moon.getBiome(new BlockPos(0, 64, 0)).is(CustomItems.id("alien")), "it uses the chosen biome");

		ServerPlayer player = h.makeMockServerPlayerInLevel();
		// Every test's mock player is called test-mock-player, so send this one directly rather than by name.
		Dimensions.visit(player, moon, null);
		h.assertTrue(player.level() == moon, "visiting takes the player there");
		h.assertTrue(toolError("visit_dimension", "{\"id\":\"nowhere\"}").contains("No dimension"), "unknown dimensions are rejected");
		BlockPos under = player.blockPosition().below();
		h.assertTrue(moon.getBlockState(under).is(Blocks.SNOW_BLOCK), "they land on the designed surface, got " + moon.getBlockState(under));

		ClaudeCraft.runAt(h.getLevel(), List.of("execute in custom:g_moon run setblock 3 220 3 minecraft:gold_block"), Vec3.ZERO);
		h.assertTrue(moon.getBlockState(new BlockPos(3, 220, 3)).is(Blocks.GOLD_BLOCK), "execute in custom:<id> reaches the dimension");

		// Redesign as floating islands and regenerate: the player comes back to the new ground.
		tool("define_dimension", "{\"id\":\"g_moon\",\"name\":\"Moon\",\"sky\":\"starry\",\"biome\":\"alien\",\"reset\":true,"
			+ "\"terrain\":{\"style\":\"islands\",\"surface\":\"minecraft:end_stone\"}}");
		ServerLevel islands = server.getLevel(Dimensions.key("g_moon"));
		h.assertTrue(islands != moon && player.level() == islands, "reset rebuilds the dimension and brings the player back");
		h.assertTrue(islands.getBlockState(new BlockPos(3, 220, 3)).isAir(), "the old terrain is gone");

		tool("define_dimension", "{\"id\":\"g_cave\",\"name\":\"Underdark\",\"sky\":\"cavern\",\"biome\":\"crimson\",\"terrain\":{\"style\":\"caves\"}}");
		ServerLevel cave = server.getLevel(Dimensions.key("g_cave"));
		Vec3 inCave = Dimensions.safeSpot(cave, 0, 0);
		BlockPos feet = BlockPos.containing(inCave);
		h.assertTrue(cave.getBlockState(feet.below()).isSolid() && cave.getBlockState(feet).isAir() && feet.getY() < 250,
			"a cavern landing is a pocket of air under the ceiling, got " + feet);

		tool("define_dimension", "{\"id\":\"g_void\",\"name\":\"Void\",\"terrain\":{\"style\":\"void\"}}");
		Vec3 inVoid = Dimensions.safeSpot(server.getLevel(Dimensions.key("g_void")), 0, 0);
		h.assertTrue(server.getLevel(Dimensions.key("g_void")).getBlockState(BlockPos.containing(inVoid).below()).is(Blocks.SMOOTH_STONE),
			"the void gets a platform to stand on");

		String err = toolError("define_dimension", "{\"id\":\"g_bad\",\"name\":\"x\",\"terrain\":{\"height_expression\":\"64 + (\"}}");
		h.assertTrue(!err.isEmpty() && CustomItems.DEFS.get("g_bad") == null, "a broken expression is rejected: " + err);
		h.assertTrue(toolError("define_dimension", "{\"id\":\"g_bad\",\"name\":\"x\",\"biome\":\"nowhere\"}").contains("biome"), "unknown biomes are rejected");

		tool("delete_definition", "{\"id\":\"g_moon\"}");
		h.assertTrue(server.getLevel(Dimensions.key("g_moon")) == null && player.level() == server.overworld(),
			"deleting unloads it and sends players home");
		tool("delete_definition", "{\"id\":\"g_cave\"}");
		tool("delete_definition", "{\"id\":\"g_void\"}");
		h.succeed();
	}

	@GameTest
	public void solidCustomBlocksHideFacesAndBlockLight(GameTestHelper h) {
		// A painted cube like a flag block: every face full of opaque pixels.
		StringBuilder rows = new StringBuilder();
		for (int r = 0; r < 16; r++) rows.append(r > 0 ? "," : "").append('"').append(r % 4 < 2 ? "wwwwwwwwwwwwwwww" : "bbbbbbbbbbbbbbbb").append('"');
		tool("define_block", "{\"id\":\"z_flag\",\"name\":\"Flag\",\"palette\":{\"w\":\"#FFFFFF\",\"b\":\"#0038B8\"},\"faces\":{\"all\":[" + rows + "]}}");
		tool("define_block", "{\"id\":\"z_glass\",\"name\":\"Glass\",\"palette\":{\"g\":\"#6080C0FF\"},\"faces\":{\"all\":["
			+ rows.toString().replace('w', 'g').replace('b', 'g') + "]}}");
		block("z_lamp", "{}");
		BlockState flag = CustomBlocks.state(def("z_flag")), lamp = CustomBlocks.state(def("z_lamp")), glass = CustomBlocks.state(def("z_glass"));
		h.assertTrue(flag.getFaceOcclusionShape(Direction.UP) == net.minecraft.world.phys.shapes.Shapes.block(), "a solid design hides the faces next to it");
		h.assertTrue(!Block.shouldRenderFace(flag, flag, Direction.EAST), "the face between two flag blocks isn't drawn");
		h.assertTrue(!Block.shouldRenderFace(Blocks.STONE.defaultBlockState(), flag, Direction.EAST), "nor a stone face against one");
		h.assertTrue(flag.getLightDampening() == 15 && !flag.propagatesSkylightDown(), "and it blocks light like stone");
		h.assertTrue(Block.shouldRenderFace(flag, lamp, Direction.EAST) && lamp.propagatesSkylightDown(), "small shapes like lamps don't hide anything");
		h.assertTrue(!Block.shouldRenderFace(glass, glass, Direction.EAST) && Block.shouldRenderFace(flag, glass, Direction.EAST),
			"see-through cubes hide faces only between their own kind, like glass");
		h.succeed();
	}
}
