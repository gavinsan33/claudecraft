package dev.claudecraft;

import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Items that hold items: backpacks, pouches, quivers, lunchboxes. The contents live in the stack's container
 * component (like a shulker box's), so they travel with the item and show in its tooltip. The open bag is locked in
 * place so it can't be moved or swapped while open, which would let its contents be copied.
 */
final class ItemStorage {
	private ItemStorage() {}

	static int slots(JsonObject def) {
		return StorageMenus.slots(CustomItems.props(def), false);
	}

	/** Open the bag in a player's hand. Server thread. */
	static void open(ServerPlayer player, InteractionHand hand, ItemStack bag, JsonObject def) {
		int slots = slots(def);
		int bagSlot = hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : Inventory.SLOT_OFFHAND;
		SimpleContainer box = new SimpleContainer(StorageMenus.rows(slots) * 9) {
			@Override
			public void setChanged() {
				super.setChanged();
				bag.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(getItems()));
			}
		};
		bag.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(box.getItems());
		StorageMenus.open(player, bag.getHoverName(), box, slots, bagSlot, p -> !bag.isEmpty() && p.getInventory().getItem(bagSlot) == bag);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 0.8f, 1.1f);
	}
}
