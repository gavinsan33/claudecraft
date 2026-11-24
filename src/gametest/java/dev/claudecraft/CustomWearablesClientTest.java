package dev.claudecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.EquipmentSlot;

/** A two-block top hat and a striped tank top, worn by the player (seen from the front), an armor stand and a zombie. */
public class CustomWearablesClientTest implements FabricClientGameTest {
	private static JsonArray rows(int n, String row) {
		JsonArray a = new JsonArray();
		for (int i = 0; i < n; i++) a.add(row);
		return a;
	}

	private static JsonObject item(String id, String name, String slot, String palette, JsonArray parts) {
		JsonObject d = JsonParser.parseString("""
			{"id":"%s","name":"%s","style":"item","model":{"palette":%s,"layers":[["kk"]]}}""".formatted(id, name, palette)).getAsJsonObject();
		JsonObject armor = new JsonObject();
		armor.addProperty("slot", slot);
		JsonObject worn = new JsonObject();
		worn.add("parts", parts);
		armor.add("worn", worn);
		JsonObject props = new JsonObject();
		props.add("armor", armor);
		d.add("properties", props);
		return d;
	}

	private static JsonObject part(JsonArray layers, int x, int y, int z, String attach) {
		JsonObject p = new JsonObject();
		p.add("layers", layers);
		p.add("origin", JsonParser.parseString("[" + x + "," + y + "," + z + "]"));
		p.addProperty("attach", attach);
		return p;
	}

	static JsonObject topHat() {
		JsonArray brim = new JsonArray();
		brim.add(rows(12, "k".repeat(12)));
		JsonArray crown = new JsonArray();
		for (int y = 0; y < 31; y++) crown.add(rows(8, (y >= 1 && y <= 3 ? "r" : "k").repeat(8)));
		JsonArray parts = new JsonArray();
		parts.add(part(brim, -6, 32, -6, "head"));
		parts.add(part(crown, -4, 33, -4, "head"));
		return item("top_hat", "Top Hat", "head", "{\"k\":\"#1A1A1E\",\"r\":\"#B01E28\"}", parts);
	}

	static JsonObject tankTop() {
		JsonArray shell = new JsonArray();
		for (int y = 0; y < 12; y++) {
			String c = y == 6 || y == 7 ? "b" : "w";
			JsonArray layer = new JsonArray();
			for (int z = 0; z < 6; z++) {
				boolean edge = z == 0 || z == 5;
				if (y == 11) layer.add(edge ? ".ww....ww." : ".w......w."); // shoulder straps
				else layer.add(edge ? c.repeat(10) : c + "........" + c);
			}
			shell.add(layer);
		}
		JsonArray parts = new JsonArray();
		parts.add(part(shell, -5, 12, -3, "body"));
		return item("tank_top", "Tank Top", "chest", "{\"k\":\"#F2F2F2\",\"w\":\"#F2F2F2\",\"b\":\"#2E62D9\"}", parts);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(server -> {
				for (JsonObject d : new JsonObject[]{topHat(), tankTop()}) {
					String bad = Morphs.validateWorn(d);
					if (bad != null) throw new AssertionError(bad);
					EntityModel.parse(Morphs.worn(d, true), 16); // the view_item worn picture
					CustomItems.define(server, d);
				}
				var level = server.overworld();
				int y = level.getChunk(0, 0).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0) + 1;
				var src = server.createCommandSourceStack();
				server.getCommands().performPrefixedCommand(src, "tp @a 0.5 " + y + " 0.5 180 10");
				server.getCommands().performPrefixedCommand(src, "time set noon");
				var player = server.getPlayerList().getPlayers().getFirst();
				player.setItemSlot(EquipmentSlot.HEAD, CustomItems.stack(CustomItems.DEFS.get("top_hat"), 1));
				player.setItemSlot(EquipmentSlot.CHEST, CustomItems.stack(CustomItems.DEFS.get("tank_top"), 1));
				// Behind the player as the camera sees them: an armor stand and a zombie, both dressed.
				var stand = net.minecraft.world.entity.EntityTypes.ARMOR_STAND.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				var zombie = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				stand.snapTo(-2.5, y, 3.5, 180, 0);
				zombie.snapTo(3.5, y, 3.5, 180, 0);
				zombie.setNoAi(true);
				for (var e : new net.minecraft.world.entity.LivingEntity[]{stand, zombie}) {
					e.setItemSlot(EquipmentSlot.HEAD, CustomItems.stack(CustomItems.DEFS.get("top_hat"), 1));
					e.setItemSlot(EquipmentSlot.CHEST, CustomItems.stack(CustomItems.DEFS.get("tank_top"), 1));
					e.yBodyRot = 180;
					e.yHeadRot = 180;
					level.addFreshEntity(e);
				}
			});
			ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> mc.player.yBodyRot = mc.player.yBodyRotO = mc.player.getYRot());
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(20);
			ctx.takeScreenshot("claudecraft-wearables");
		}
	}
}
