package dev.claudecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Storage everywhere: a 5-slot pouch (contents kept in the item, no bags in bags, the open bag locked in place), a
 * 12-slot crate block, a car's trunk opened with the inventory key while riding, and sneak + right-click commands.
 */
public class CustomStorageClientTest implements FabricClientGameTest {
	private static JsonObject parse(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(server -> {
				var pouch = parse("""
					{"id":"pouch","name":"Pouch","style":"item","model":{"palette":{"b":"#8B5A2B"},"layers":[["bb"]]},"properties":{"storage":{"slots":5}}}""");
				CustomItems.define(server, pouch);
				var player = server.getPlayerList().getPlayers().getFirst();
				player.getInventory().setSelectedSlot(0);
				player.getInventory().setItem(0, CustomItems.stack(pouch, 1));
				player.getInventory().setItem(1, CustomItems.stack(pouch, 1));
				player.getInventory().setItem(3, new ItemStack(Items.DIAMOND, 64));
				player.getInventory().setItem(4, new ItemStack(Items.DIAMOND, 64));
				for (int i = 9; i < 15; i++) player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
				ItemStack bag = player.getMainHandItem();
				if (bag.getMaxStackSize() != 1) throw new AssertionError("a bag should be unstackable");
				bag.use(player.level(), player, InteractionHand.MAIN_HAND);
				if (!(player.containerMenu instanceof StorageMenus.Menu menu)) throw new AssertionError("right-click should open the pouch, got " + player.containerMenu);
				if (menu.count != 5 || menu.getSlot(5).isActive() || menu.getSlot(5).mayPlace(new ItemStack(Items.DIAMOND))) throw new AssertionError("only 5 slots should be usable");
				// Shift-click everything: only 5 stacks go in, none into the switched-off squares.
				for (var s : java.util.List.copyOf(menu.slots)) if (s.container == player.getInventory() && s.hasItem()) menu.quickMoveStack(player, s.index);
				var held = bag.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItemCopyStream().toList();
				if (held.size() != 5) throw new AssertionError("the pouch should hold exactly 5 stacks, got " + held);
				for (int i = 5; i < 9; i++) if (menu.getSlot(i).hasItem()) throw new AssertionError("switched-off slot " + i + " got an item");
				if (menu.getSlot(0).mayPlace(player.getInventory().getItem(1))) throw new AssertionError("a bag shouldn't fit in a bag");
				if (menu.getSlot(0).mayPlace(new ItemStack(Items.SHULKER_BOX))) throw new AssertionError("a shulker box shouldn't fit in a bag");
				int bagMenuSlot = -1;
				for (var s : menu.slots) if (s.container == player.getInventory() && s.getContainerSlot() == 0) bagMenuSlot = s.index;
				if (menu.getSlot(bagMenuSlot).mayPickup(player)) throw new AssertionError("the open bag should be locked in place");
				menu.clicked(2, 0, ContainerInput.SWAP, player);
				if (player.getInventory().getItem(0) != bag) throw new AssertionError("swapping with the open bag's hotbar slot should do nothing");
				player.closeContainer();
				bag.use(player.level(), player, InteractionHand.MAIN_HAND);
				if (!player.containerMenu.getSlot(0).hasItem()) throw new AssertionError("reopening should show the contents again");
				player.closeContainer();

				// Sneak + right-click does something else.
				var wand = parse("""
					{"id":"wand","name":"Wand","style":"item","model":{"palette":{"w":"#FFFFFF"},"layers":[["w"]]},
					 "properties":{"use_commands":["tag @s add zapped"],"sneak_use_commands":["tag @s add switched"]}}""");
				CustomItems.define(server, wand);
				player.getInventory().setItem(0, CustomItems.stack(wand, 1));
				player.setShiftKeyDown(true);
				player.getMainHandItem().use(player.level(), player, InteractionHand.MAIN_HAND);
				player.setShiftKeyDown(false);
				player.getMainHandItem().use(player.level(), player, InteractionHand.MAIN_HAND);
				if (!player.entityTags().contains("switched") || !player.entityTags().contains("zapped")) throw new AssertionError("use and sneak use should run their own commands: " + player.entityTags());

				// A crate block with 12 slots.
				var crate = parse("{\"id\":\"crate\",\"kind\":\"block\",\"name\":\"Crate\",\"style\":\"block\",\"properties\":{\"storage\":{\"slots\":12}}}");
				crate.addProperty("slot", CustomBlocks.allocateSlot("crate"));
				crate.add("model", parse("{\"palette\":{\"w\":\"#9C6B3A\"},\"faces\":{\"all\":" + new com.google.gson.Gson().toJson(java.util.Collections.nCopies(16, "w".repeat(16))) + "}}"));
				CustomItems.define(server, crate);
				var level = server.overworld();
				BlockPos at = player.blockPosition().offset(2, 0, 2);
				level.setBlockAndUpdate(at, CustomBlocks.state(crate));
				level.getBlockState(at).useWithoutItem(level, player, new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(at), net.minecraft.core.Direction.UP, at, false));
				if (!(player.containerMenu instanceof StorageMenus.Menu cm) || cm.count != 12 || cm.getSlot(12).isActive()) throw new AssertionError("the crate should open with 12 slots: " + player.containerMenu);
			});
			ctx.waitTicks(10);
			ctx.takeScreenshot("claudecraft-storage-crate");

			// A car with a trunk: riding it, the inventory key opens the trunk; a mount without storage keeps the player's own inventory.
			sp.getServer().runOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				player.closeContainer();
				for (String id : new String[]{"car", "horse"}) {
					var d = parse("""
						{"id":"%s","name":"%s","kind":"entity","model":{"palette":{"b":"#2A5BD7"},"parts":[{"origin":[-6,0,-8],"layers":[["bbbbbbbbbbbb"]]}]},
						 "properties":{"vehicle":{"controls":"drive"}}}""".formatted(id, id));
					if (id.equals("car")) d.getAsJsonObject("properties").add("storage", parse("{\"slots\":18}"));
					CustomItems.define(server, d);
				}
				var car = CustomEntities.spawn(server.overworld(), CustomItems.DEFS.get("car"), player.position().add(0, 0, 3), 1).getFirst();
				player.startRiding(car);
			});
			ctx.waitTicks(10);
			ctx.getInput().pressKey(o -> o.keyInventory);
			ctx.waitTicks(10);
			String trunk = ctx.computeOnClient(mc -> mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName() + " " + mc.gui.screen().getTitle().getString());
			if (!trunk.contains("StorageScreen") || !trunk.contains("car")) throw new AssertionError("the inventory key should open the car's trunk, got " + trunk);
			ctx.takeScreenshot("claudecraft-storage-trunk");
			ctx.runOnClient(mc -> mc.player.closeContainer());
			sp.getServer().runOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				player.stopRiding();
				var horse = CustomEntities.spawn(server.overworld(), CustomItems.DEFS.get("horse"), player.position().add(3, 0, 0), 1).getFirst();
				player.startRiding(horse);
			});
			ctx.waitTicks(10);
			ctx.getInput().pressKey(o -> o.keyInventory);
			ctx.waitTicks(10);
			String own = ctx.computeOnClient(mc -> mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
			if (!own.contains("InventoryScreen")) throw new AssertionError("on a mount without storage, the inventory key should open your own inventory, got " + own);
		}
	}
}
