package dev.claudecraft;

import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Items that hold items: backpacks, pouches, quivers, lunchboxes. Right-click opens a chest screen; the contents live
 * in the stack's container component (like a shulker box's), so they travel with the item and show in its tooltip.
 * Bags can't go in bags, and the open bag is locked in place so it can't be moved or swapped while open (which would
 * let its contents be copied).
 */
final class ItemStorage {
	private ItemStorage() {}

	/** Rows of storage (1 to 6, 9 slots each), or 0 for none. */
	static int rows(JsonObject def) {
		JsonObject p = CustomItems.props(def);
		return p.has("storage") ? Math.max(0, Math.min(6, p.get("storage").getAsInt())) : 0;
	}

	/** Whether a stack may go inside a bag: not another bag, shulker box or bundle. */
	static boolean fits(ItemStack s) {
		return s.getItem().canFitInsideContainerItems() && !s.has(DataComponents.BUNDLE_CONTENTS) && rows(CustomItems.definition(s)) == 0;
	}

	/** Open the bag in a player's hand. Server thread. */
	static void open(ServerPlayer player, InteractionHand hand, ItemStack bag, JsonObject def) {
		int rows = rows(def);
		int bagSlot = hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : Inventory.SLOT_OFFHAND;
		SimpleContainer box = new SimpleContainer(rows * 9) {
			@Override
			public void setChanged() {
				super.setChanged();
				bag.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(getItems()));
			}
		};
		bag.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(box.getItems());
		player.openMenu(new SimpleMenuProvider((id, inv, p) -> new Menu(id, inv, box, rows, bag, bagSlot), bag.getHoverName()));
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 0.8f, 1.1f);
	}

	private static MenuType<ChestMenu> type(int rows) {
		return switch (rows) {
			case 1 -> MenuType.GENERIC_9x1;
			case 2 -> MenuType.GENERIC_9x2;
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 5 -> MenuType.GENERIC_9x5;
			default -> MenuType.GENERIC_9x6;
		};
	}

	/**
	 * A vanilla chest menu (so the client shows the vanilla screen) whose bag slots refuse other bags, and whose
	 * player slot holding the open bag can't be touched. The client doesn't know these rules; the server corrects it.
	 */
	static final class Menu extends ChestMenu {
		private final ItemStack bag;
		private final int bagSlot;

		Menu(int id, Inventory inv, SimpleContainer box, int rows, ItemStack bag, int bagSlot) {
			super(type(rows), id, inv, box, rows);
			this.bag = bag;
			this.bagSlot = bagSlot;
			for (int i = 0; i < slots.size(); i++) {
				Slot old = slots.get(i);
				Slot replacement;
				if (old.container == box) {
					replacement = new Slot(box, old.getContainerSlot(), old.x, old.y) {
						@Override
						public boolean mayPlace(ItemStack s) {
							return fits(s);
						}
					};
				} else if (old.container == inv && old.getContainerSlot() == bagSlot) {
					replacement = new Slot(inv, bagSlot, old.x, old.y) {
						@Override
						public boolean mayPickup(Player p) {
							return false;
						}

						@Override
						public boolean mayPlace(ItemStack s) {
							return false;
						}
					};
				} else {
					continue;
				}
				replacement.index = i;
				slots.set(i, replacement);
			}
		}

		@Override
		public boolean stillValid(Player p) {
			return !bag.isEmpty() && p.getInventory().getItem(bagSlot) == bag;
		}

		@Override
		public void clicked(int slot, int button, ContainerInput input, Player p) {
			// Number keys (and F for the off hand) swap with a hotbar slot directly; don't let that move the open bag.
			if (input == ContainerInput.SWAP && (button == bagSlot || button == 40 && bagSlot == Inventory.SLOT_OFFHAND)) return;
			super.clicked(slot, button, input, p);
		}
	}
}
