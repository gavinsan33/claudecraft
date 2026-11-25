package dev.claudecraft;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

/** A backpack: right-click opens it, contents are kept in the item, bags don't nest, and the open bag can't be moved. */
public class CustomStorageClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(server -> {
				var def = JsonParser.parseString("""
					{"id":"backpack","name":"Backpack","style":"item","model":{"palette":{"b":"#8B5A2B"},"layers":[["bb"]]},
					 "properties":{"storage":2}}""").getAsJsonObject();
				CustomItems.define(server, def);
				var player = server.getPlayerList().getPlayers().getFirst();
				player.getInventory().setSelectedSlot(0);
				player.getInventory().setItem(0, CustomItems.stack(def, 1));
				player.getInventory().setItem(1, CustomItems.stack(def, 1));
				player.getInventory().setItem(2, new ItemStack(Items.SHULKER_BOX));
				player.getInventory().setItem(3, new ItemStack(Items.DIAMOND, 5));
				ItemStack bag = player.getMainHandItem();
				if (bag.getMaxStackSize() != 1) throw new AssertionError("a bag should be unstackable");
				bag.use(player.level(), player, InteractionHand.MAIN_HAND);
				if (!(player.containerMenu instanceof ItemStorage.Menu menu)) throw new AssertionError("right-click should open the bag, got " + player.containerMenu);
				menu.getSlot(0).set(new ItemStack(Items.DIAMOND, 5));
				var held = bag.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItemCopyStream().toList();
				if (held.size() != 1 || !held.getFirst().is(Items.DIAMOND) || held.getFirst().getCount() != 5) throw new AssertionError("the bag should hold 5 diamonds, got " + held);
				if (menu.getSlot(1).mayPlace(player.getInventory().getItem(1))) throw new AssertionError("a bag shouldn't fit in a bag");
				if (menu.getSlot(1).mayPlace(new ItemStack(Items.SHULKER_BOX))) throw new AssertionError("a shulker box shouldn't fit in a bag");
				// The open bag: the player slot holding it is locked, and number-key swaps with it do nothing.
				int bagMenuSlot = -1;
				for (var s : menu.slots) if (s.container == player.getInventory() && s.getContainerSlot() == 0) bagMenuSlot = s.index;
				if (menu.getSlot(bagMenuSlot).mayPickup(player)) throw new AssertionError("the open bag should be locked in place");
				menu.clicked(5, 0, ContainerInput.SWAP, player);
				if (player.getInventory().getItem(0) != bag) throw new AssertionError("swapping with the open bag's hotbar slot should do nothing");
				player.closeContainer();
				bag.use(player.level(), player, InteractionHand.MAIN_HAND);
				if (!player.containerMenu.getSlot(0).getItem().is(Items.DIAMOND)) throw new AssertionError("reopening should show the diamonds again");
				player.getInventory().setItem(0, ItemStack.EMPTY);
				if (player.containerMenu.stillValid(player)) throw new AssertionError("the screen should close once the bag is gone");
				player.getInventory().setItem(0, bag);
			});
			ctx.waitTicks(10);
			ctx.takeScreenshot("claudecraft-backpack");
		}
	}
}
