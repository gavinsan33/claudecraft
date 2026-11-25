package dev.claudecraft.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** The vanilla chest screen, with the squares a storage doesn't use (past its slot count) painted over. */
final class StorageScreen extends ContainerScreen {
	StorageScreen(net.minecraft.world.inventory.ChestMenu menu, Inventory inv, Component title) {
		super(menu, inv, title);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		for (Slot s : menu.slots) {
			if (!s.isActive()) g.fill(leftPos + s.x - 1, topPos + s.y - 1, leftPos + s.x + 17, topPos + s.y + 17, 0xFFC6C6C6); // the panel's gray
		}
	}
}
