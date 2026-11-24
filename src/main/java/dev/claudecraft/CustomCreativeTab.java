package dev.claudecraft;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A creative inventory tab holding every custom item, block and spawn egg in the world. Refilled when designs change. */
public final class CustomCreativeTab {
	private CustomCreativeTab() {}

	public static CreativeModeTab TAB;

	static void register() {
		TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, CustomItems.id("custom"), FabricCreativeModeTab.builder()
			.title(Component.literal("ClaudeCraft"))
			.icon(CustomCreativeTab::icon)
			.displayItems((params, out) -> {
				List<JsonObject> defs = new ArrayList<>(CustomItems.DEFS.values());
				// Items, then blocks, then spawn eggs, each alphabetical.
				defs.sort(Comparator.<JsonObject>comparingInt(d -> CustomBlocks.isBlock(d) ? 1 : CustomEntities.isEntity(d) ? 2 : 0)
					.thenComparing(d -> d.get("id").getAsString()));
				for (JsonObject def : defs) if (!def.has("kind") || !List.of("dimension", "effect", "morph").contains(def.get("kind").getAsString())) out.accept(CustomItems.stack(def, 1));
			})
			.build());
	}

	private static ItemStack icon() {
		for (JsonObject def : CustomItems.DEFS.values()) {
			if (!def.has("kind")) return CustomItems.stack(def, 1); // a plain item
		}
		return new ItemStack(net.minecraft.world.item.Items.AMETHYST_SHARD);
	}
}
