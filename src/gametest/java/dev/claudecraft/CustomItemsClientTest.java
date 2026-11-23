package dev.claudecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Defines a few custom items, hands them to the player, and screenshots them in hand, in the hotbar and in the inventory. */
public class CustomItemsClientTest implements FabricClientGameTest {
	private static JsonObject def(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(server -> {
				JsonObject ruby = def("{\"id\":\"ruby\",\"name\":\"Ruby\",\"style\":\"item\",\"properties\":{\"rarity\":\"rare\"}}");
				ruby.add("model", VoxelModel.fromPixels(def("{\"r\":\"#E0115F\",\"d\":\"#9B0A3F\",\"w\":\"#FFC0D8\"}"),
					JsonParser.parseString("""
						["....dddddddd....","...drrrrrrrrd...","..drwwrrrrrrrd..",".drwwrrrrrrrrrd.","drrrrrrrrrrrrrrd",".drrrrrrrrrrrrd.",
						 "..drrrrrrrrrrd..","...drrrrrrrrd...","....drrrrrrd....",".....drrrrd.....","......drrd......",".......dd......."]""").getAsJsonArray(), 2));
				CustomItems.define(server, ruby);
				JsonObject blade = def("{\"id\":\"frost_blade\",\"name\":\"Frost Blade\",\"style\":\"handheld\",\"properties\":{\"attack_damage\":8}}");
				blade.add("model", VoxelModel.fromPixels(def("{\"b\":\"#5AD1E8\",\"h\":\"#6B4423\",\"g\":\"#E8C547\"}"),
					JsonParser.parseString("""
						["..............bb",".............bbb","............bbb.","...........bbb..","..........bbb...",".........bbb....",
						 "........bbb.....",".......bbb......","..g...bbb.......","...g.bbb........","....gbb.........",".....hg.........",
						 "....h..g........","...h............","..h.............",".h.............."]""").getAsJsonArray(), 1));
				CustomItems.define(server, blade);
				// 12-voxel glass sphere with a solid gold core.
				com.google.gson.JsonArray layers = new com.google.gson.JsonArray();
				for (int y = 0; y < 12; y++) {
					com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
					for (int z = 0; z < 12; z++) {
						StringBuilder row = new StringBuilder();
						for (int x = 0; x < 12; x++) {
							double d = Math.hypot(Math.hypot(x - 5.5, y - 5.5), z - 5.5);
							row.append(d < 2.5 ? 'c' : d < 6 ? 'g' : '.');
						}
						rows.add(row.toString());
					}
					layers.add(rows);
				}
				JsonObject orb = def("{\"id\":\"orb\",\"name\":\"Glass Orb\",\"style\":\"block\",\"model\":{\"palette\":{\"g\":\"#6044AAFF\",\"c\":\"#FFFFD700\"}}}");
				orb.getAsJsonObject("model").add("layers", layers);
				CustomItems.define(server, orb);
				ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
				p.getInventory().setItem(0, CustomItems.stack(CustomItems.DEFS.get("ruby"), 5));
				p.getInventory().setItem(1, CustomItems.stack(CustomItems.DEFS.get("frost_blade"), 1));
				p.getInventory().setItem(2, CustomItems.stack(CustomItems.DEFS.get("orb"), 1));
				ItemStack unknown = new ItemStack(CustomItems.ITEM);
				unknown.set(CustomItems.DEFINITION, "not_defined");
				p.getInventory().setItem(3, unknown);
			});
			sp.getConnection().waitForClientboundPackets();
			ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(1));
			ctx.waitTicks(20);
			ctx.takeScreenshot("claudecraft-hand-blade");
			ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(2));
			ctx.waitTicks(10);
			ctx.takeScreenshot("claudecraft-hand-orb");
			ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
			ctx.waitTicks(10);
			ctx.takeScreenshot("claudecraft-hand-ruby");
			ctx.setScreen(() -> new InventoryScreen(ctx.computeOnClient(mc -> mc.player)));
			ctx.waitTicks(10);
			ctx.takeScreenshot("claudecraft-inventory");
			ctx.setScreen(() -> null);

			// Right-click commands run on the server as the player.
			boolean zapped = sp.getServer().computeOnServer(server -> {
				CustomItems.define(server, def("""
					{"id":"wand","name":"Zap Wand","style":"handheld","properties":{"use_commands":["tag @s add zapped"],"cooldown_seconds":1},
					 "model":{"palette":{"w":"#8B5A2B"},"layers":[["w"]]}}"""));
				ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
				p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, CustomItems.stack(CustomItems.DEFS.get("wand"), 1));
				p.getMainHandItem().use(p.level(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
				return p.entityTags().contains("zapped");
			});
			if (!zapped) throw new AssertionError("using the wand should run its command");
			if (!ctx.computeOnClient(mc -> mc.player.getInventory().getItem(1).getHoverName().getString()).equals("Frost Blade")) {
				throw new AssertionError("client should name the item from its definition");
			}
		}
	}
}
