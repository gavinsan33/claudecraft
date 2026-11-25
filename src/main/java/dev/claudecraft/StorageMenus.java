package dev.claudecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * The storage screen shared by custom items (backpacks), blocks (chests) and creatures (vehicle trunks, saddlebags):
 * a chest screen with any number of slots, 1 to 54. It uses as many rows as it needs, and the leftover squares in
 * the last row are switched off (the client's StorageScreen paints over them). The slot count travels with the
 * screen-opening packet, so the client builds the same menu.
 */
public final class StorageMenus {
	private StorageMenus() {}

	public static final int MAX_SLOTS = 54;
	public static ExtendedMenuType<Menu, Integer> TYPE;

	static void register() {
		TYPE = Registry.register(BuiltInRegistries.MENU, CustomItems.id("storage"),
			new ExtendedMenuType<>((id, inv, slots) -> new Menu(id, inv, new SimpleContainer(rows(slots) * 9), slots, -1, p -> true), ByteBufCodecs.VAR_INT));
	}

	public static int rows(int slots) {
		return Math.max(1, Math.min(6, (slots + 8) / 9));
	}

	/**
	 * How many slots a definition's storage has, or 0: storage is a slot count, or {slots, open}. For blocks a bare
	 * number is rows (that's what it meant before slots could be any number).
	 */
	public static int slots(JsonObject props, boolean bareNumberIsRows) {
		JsonElement s = props.get("storage");
		if (s == null) return 0;
		int n = s.isJsonObject() ? (s.getAsJsonObject().has("slots") ? s.getAsJsonObject().get("slots").getAsInt() : 27)
			: bareNumberIsRows ? s.getAsInt() * 9 : s.getAsInt();
		return Math.max(0, Math.min(MAX_SLOTS, n));
	}

	/** When storage opens: on "use" (right-click) or "sneak_use" (sneak + right-click). */
	static String openOn(JsonObject props, String dflt) {
		JsonElement s = props.get("storage");
		return s != null && s.isJsonObject() && s.getAsJsonObject().has("open") ? s.getAsJsonObject().get("open").getAsString() : dflt;
	}

	/** A problem with a definition's storage settings, or null. */
	static String validate(JsonObject props, boolean bareNumberIsRows) {
		JsonElement s = props.get("storage");
		if (s == null) return null;
		int n;
		try {
			n = s.isJsonObject() ? s.getAsJsonObject().get("slots").getAsInt() : s.getAsInt() * (bareNumberIsRows ? 9 : 1);
		} catch (RuntimeException e) {
			return "storage needs slots (1 to " + MAX_SLOTS + ")";
		}
		if (n < 1 || n > MAX_SLOTS) return "storage holds 1 to " + MAX_SLOTS + " slots";
		String open = openOn(props, "use");
		if (!open.equals("use") && !open.equals("sneak_use")) return "storage.open must be use or sneak_use";
		return null;
	}

	/**
	 * Show a storage screen. box holds the items (at least rows(slots) * 9 big); lockedSlot is a player inventory slot
	 * that can't be touched while it's open (the bag being looked into), or -1; valid says when to close it.
	 */
	static void open(ServerPlayer player, Component title, Container box, int slots, int lockedSlot, Predicate<Player> valid) {
		player.openMenu(new ExtendedMenuProvider<Integer>() {
			@Override
			public Integer getScreenOpeningData(ServerPlayer p) {
				return slots;
			}

			@Override
			public Component getDisplayName() {
				return title;
			}

			@Override
			public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
				return new Menu(id, inv, box, slots, lockedSlot, valid);
			}
		});
	}

	/** Whether a stack may go into storage: not another storage item, shulker box or bundle (no bags in bags). */
	static boolean fits(ItemStack s) {
		return s.getItem().canFitInsideContainerItems() && !s.has(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS)
			&& slots(CustomItems.props(CustomItems.definition(s)), false) == 0;
	}

	public static final class Menu extends ChestMenu {
		/** How many of the grid's slots are in use; the rest are switched off. */
		public final int count;
		private final int lockedSlot;
		private final Predicate<Player> valid;

		Menu(int id, Inventory inv, Container box, int count, int lockedSlot, Predicate<Player> valid) {
			super(TYPE, id, inv, box, rows(count));
			this.count = count;
			this.lockedSlot = lockedSlot;
			this.valid = valid;
			for (int i = 0; i < slots.size(); i++) {
				Slot old = slots.get(i);
				Slot replacement;
				if (old.container == box) {
					boolean on = old.getContainerSlot() < count;
					replacement = new Slot(box, old.getContainerSlot(), old.x, old.y) {
						@Override
						public boolean mayPlace(ItemStack s) {
							return on && fits(s);
						}

						@Override
						public boolean mayPickup(Player p) {
							return on;
						}

						@Override
						public boolean isActive() {
							return on;
						}
					};
				} else if (old.container == inv && old.getContainerSlot() == lockedSlot) {
					replacement = new Slot(inv, lockedSlot, old.x, old.y) {
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
			return valid.test(p);
		}

		@Override
		public void clicked(int slot, int button, ContainerInput input, Player p) {
			// Number keys (and F for the off hand) swap straight with a hotbar slot; don't let that move a locked bag.
			if (lockedSlot >= 0 && input == ContainerInput.SWAP && (button == lockedSlot || button == 40 && lockedSlot == Inventory.SLOT_OFFHAND)) return;
			super.clicked(slot, button, input, p);
		}

		@Override
		public ItemStack quickMoveStack(Player p, int index) {
			// Shift-click into storage: vanilla would fill the switched-off slots too, so do it within the slots in use.
			Slot slot = slots.get(index);
			if (!slot.hasItem() || !slot.mayPickup(p)) return ItemStack.EMPTY;
			ItemStack stack = slot.getItem(), before = stack.copy();
			int grid = rows(count) * 9;
			if (index < grid) {
				if (!moveItemStackTo(stack, grid, slots.size(), true)) return ItemStack.EMPTY;
			} else if (!fits(stack) || !moveItemStackTo(stack, 0, count, false)) {
				return ItemStack.EMPTY;
			}
			if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
			else slot.setChanged();
			return before;
		}
	}
}
