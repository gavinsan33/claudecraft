package dev.claudecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Map;

/**
 * Morphs reshape how a player looks: hide parts of the vanilla body and add parts cut from the player's own skin
 * (a head, body, arm or leg box, stretched, moved and turned) or voxel parts like a creature's. Parts can follow a
 * vanilla body part as it moves, or animate by role like creature parts. Definitions live with the others (kind
 * "morph"); which morph a player wears is a synced, saved attachment, so everyone sees it and it survives relogs
 * and deaths. The client's MorphLayer draws it.
 */
public final class Morphs {
	private Morphs() {}

	public static AttachmentType<String> MORPH;

	/** Vanilla body parts: their skin boxes and where they sit. All in pixels (16 per block). */
	public record Region(int u, int v, int w, int h, int d, int ou, int ov, float inflate,
		/** Box center when standing, in creature space (x left, y up from the feet, z forward). */
		float cx, float cy, float cz,
		/** The vanilla model part's pivot, in model space (y down from the neck), for following its movement. */
		float px, float py, float pz) {}

	public static final Map<String, Region> REGIONS = Map.of(
		"head", new Region(0, 0, 8, 8, 8, 32, 0, 0.5f, 0, 28, 0, 0, 0, 0),
		"body", new Region(16, 16, 8, 12, 4, 16, 32, 0.25f, 0, 18, 0, 0, 0, 0),
		"right_arm", new Region(40, 16, 4, 12, 4, 40, 32, 0.25f, -6, 18, 0, -5, 2, 0),
		"left_arm", new Region(32, 48, 4, 12, 4, 48, 48, 0.25f, 6, 18, 0, 5, 2, 0),
		"right_leg", new Region(0, 16, 4, 12, 4, 0, 32, 0.25f, -2, 6, 0, -1.9f, 12, 0),
		"left_leg", new Region(16, 48, 4, 12, 4, 0, 48, 0.25f, 2, 6, 0, 1.9f, 12, 0));
	static final int MAX_PARTS = 32;

	static void register() {
		MORPH = AttachmentRegistry.create(CustomItems.id("morph"), b -> b.persistent(Codec.STRING).copyOnDeath()
			.syncWith(ByteBufCodecs.STRING_UTF8, AttachmentSyncPredicate.all()));
	}

	public static boolean isMorph(JsonObject def) {
		return def != null && def.has("kind") && def.get("kind").getAsString().equals("morph");
	}

	/** The morph an entity wears, or null. Both sides. */
	public static JsonObject of(Entity e) {
		String id = e.getAttached(MORPH);
		JsonObject def = id == null ? null : CustomItems.DEFS.get(id);
		return isMorph(def) ? def : null;
	}

	/** Wear a morph, or go back to normal with null. Server thread. */
	static void set(Entity e, String id) {
		if (id == null) e.removeAttached(MORPH);
		else e.setAttached(MORPH, id);
	}

	/** Vanilla parts a morph hides: a list of region names, or "all". */
	public static List<String> hidden(JsonObject def) {
		JsonElement h = def.get("hide");
		if (h == null) return List.of();
		if (h.isJsonPrimitive()) return h.getAsString().equals("all") ? List.copyOf(REGIONS.keySet()) : List.of(h.getAsString());
		return CustomItems.strings(def, "hide");
	}

	/** The voxel parts as a creature model (palette plus the parts with layers), or null if there are none. */
	public static JsonObject voxelModel(JsonObject def) {
		JsonArray voxel = new JsonArray();
		for (JsonElement e : def.getAsJsonArray("parts")) if (e.getAsJsonObject().has("layers")) voxel.add(e);
		if (voxel.isEmpty()) return null;
		JsonObject m = new JsonObject();
		m.add("palette", def.has("palette") ? def.get("palette") : new JsonObject());
		m.add("parts", voxel);
		return m;
	}

	/**
	 * A wearable item's worn model (armor.worn) as a morph of voxel parts, or null if it has none. Its palette
	 * defaults to the item's own. With mannequin, a gray player is added first, for pictures of how it fits.
	 */
	public static JsonObject worn(JsonObject item, boolean mannequin) {
		JsonObject a = item == null ? null : CustomItems.props(item).getAsJsonObject("armor");
		if (a == null || !a.has("worn") || !a.get("worn").isJsonObject()) return null;
		JsonObject w = a.getAsJsonObject("worn");
		JsonObject d = new JsonObject();
		d.addProperty("id", "worn/" + item.get("id").getAsString());
		JsonObject palette = w.has("palette") ? w.getAsJsonObject("palette").deepCopy()
			: item.has("model") && item.getAsJsonObject("model").has("palette") ? item.getAsJsonObject("model").getAsJsonObject("palette").deepCopy() : new JsonObject();
		JsonArray parts = new JsonArray();
		if (mannequin) {
			palette.addProperty(MANNEQUIN, "#9A9A9A");
			for (var r : REGIONS.values()) {
				JsonArray layers = new JsonArray();
				for (int y = 0; y < r.h(); y++) {
					JsonArray rows = new JsonArray();
					for (int z = 0; z < r.d(); z++) rows.add(MANNEQUIN.repeat(r.w()));
					layers.add(rows);
				}
				JsonObject p = new JsonObject();
				p.add("layers", layers);
				JsonArray origin = new JsonArray();
				origin.add(Math.round(r.cx() - r.w() / 2f));
				origin.add(Math.round(r.cy() - r.h() / 2f));
				origin.add(Math.round(r.cz() - r.d() / 2f));
				p.add("origin", origin);
				parts.add(p);
			}
		}
		if (w.has("parts") && w.get("parts").isJsonArray()) parts.addAll(w.getAsJsonArray("parts"));
		d.add("palette", palette);
		d.add("parts", parts);
		return d;
	}

	private static final String MANNEQUIN = "\u2591";

	/** Check a wearable's worn model, returning a problem or null. Only voxel parts: mobs and armor stands have no skin. */
	static String validateWorn(JsonObject item) {
		JsonObject d = worn(item, false);
		if (d == null) return null;
		if (d.getAsJsonArray("parts").isEmpty()) return "armor.worn needs parts";
		for (JsonElement e : d.getAsJsonArray("parts")) {
			if (!e.isJsonObject() || !e.getAsJsonObject().has("layers")) return "armor.worn parts are voxel parts: give each layers and origin";
		}
		String bad = validate(d);
		return bad == null ? null : "armor.worn: " + bad;
	}

	/** Check a morph definition, returning a problem or null. */
	static String validate(JsonObject def) {
		for (String h : hidden(def)) if (!REGIONS.containsKey(h)) return "hide: " + h + " isn't one of all, " + String.join(", ", REGIONS.keySet());
		if (!def.has("parts") || !def.get("parts").isJsonArray()) return "parts must be a list (it can be empty)";
		JsonArray parts = def.getAsJsonArray("parts");
		if (parts.size() > MAX_PARTS) return "at most " + MAX_PARTS + " parts";
		int i = 0;
		for (JsonElement e : parts) {
			i++;
			if (!e.isJsonObject()) return "part " + i + " must be an object";
			JsonObject p = e.getAsJsonObject();
			boolean skin = p.has("skin"), voxel = p.has("layers");
			if (skin == voxel) return "part " + i + ": give either skin (a piece of the player's skin) or layers (voxels)";
			if (skin && !REGIONS.containsKey(p.get("skin").getAsString())) return "part " + i + ": skin must be one of " + String.join(", ", REGIONS.keySet());
			if (p.has("attach") && !REGIONS.containsKey(p.get("attach").getAsString())) return "part " + i + ": attach must be one of " + String.join(", ", REGIONS.keySet());
			if (p.has("role") && !EntityModel.ROLES.contains(p.get("role").getAsString())) return "part " + i + ": role must be one of " + EntityModel.ROLES;
			for (String k : List.of("center", "scale", "rotation", "pivot")) {
				if (p.has(k) && (!p.get(k).isJsonArray() || p.getAsJsonArray(k).size() != 3)) return "part " + i + ": " + k + " must be [x, y, z]";
			}
		}
		JsonObject vm = voxelModel(def);
		if (vm != null) {
			try {
				EntityModel.parse(vm, 16);
			} catch (RuntimeException ex) {
				return "voxel parts: " + ex.getMessage();
			}
		}
		return null;
	}
}
