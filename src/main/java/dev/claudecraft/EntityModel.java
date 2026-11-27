package dev.claudecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A creature's model: voxel parts that the client animates by role (legs swing, heads look around, wings flap).
 * Pure Java plus Gson. Self-check: from src/main/java, {@code java -cp <gson.jar> dev/claudecraft/EntityModel.java}.
 *
 * Stored form: {"palette": {...}, "parts": [{"name", "role", "origin": [x,y,z], "pivot": [x,y,z], "layers": [...],
 * "phase": 0|1}]}. Coordinates are voxels in the creature's own space: x = 0, z = 0 is its center, y = 0 its feet,
 * +z the way it faces, +x its left side. origin is where a part's layers start (their minimum corner); pivot is the
 * point it rotates around (a hip, a neck, a wing root).
 */
public final class EntityModel {
	public static final Set<String> ROLES = Set.of("body", "head", "leg", "arm", "wing", "tail", "wheel", "front_wheel", "propeller", "rotor", "none");
	/** Roles that spin about their own center (an axle, a hub), so that's their default pivot. */
	public static final Set<String> SPINNING = Set.of("wheel", "front_wheel", "propeller", "rotor");
	public static final int MAX_PARTS = 24, MAX_SPAN = 64;

	/** @param phase legs and arms with the same phase swing together */
	public record Part(String name, String role, int phase, float[] pivot, int[] origin, VoxelModel voxels) {}

	public final List<Part> parts;
	/** Voxels per block. */
	public final float scale;
	/** Bounds of all parts at rest, in voxels (max exclusive). */
	public final int minX, minY, minZ, maxX, maxY, maxZ;

	private EntityModel(List<Part> parts, float scale) {
		this.parts = parts;
		this.scale = scale;
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
		for (Part p : parts) {
			x0 = Math.min(x0, p.origin[0]);
			y0 = Math.min(y0, p.origin[1]);
			z0 = Math.min(z0, p.origin[2]);
			x1 = Math.max(x1, p.origin[0] + p.voxels.sx);
			y1 = Math.max(y1, p.origin[1] + p.voxels.sy);
			z1 = Math.max(z1, p.origin[2] + p.voxels.sz);
		}
		minX = x0;
		minY = y0;
		minZ = z0;
		maxX = x1;
		maxY = y1;
		maxZ = z1;
	}

	/** Throws IllegalArgumentException with a readable message for bad input. */
	public static EntityModel parse(JsonObject model, float scale) {
		if (scale < 4 || scale > 64) throw new IllegalArgumentException("scale must be 4 to 64 voxels per block");
		if (!model.has("parts") || !model.get("parts").isJsonArray()) throw new IllegalArgumentException("parts is required");
		JsonArray arr = model.getAsJsonArray("parts");
		if (arr.isEmpty() || arr.size() > MAX_PARTS) throw new IllegalArgumentException("A model needs 1 to " + MAX_PARTS + " parts");
		List<Part> parts = new ArrayList<>();
		int limbs = 0;
		for (JsonElement e : arr) {
			JsonObject p = e.getAsJsonObject();
			String name = p.has("name") ? p.get("name").getAsString() : "part" + parts.size();
			String role = p.has("role") ? p.get("role").getAsString() : "body";
			if (!ROLES.contains(role)) throw new IllegalArgumentException("Part " + name + ": role must be one of " + ROLES);
			JsonObject vox = new JsonObject();
			vox.add("palette", model.get("palette"));
			vox.add("layers", p.get("layers"));
			VoxelModel v;
			try {
				v = VoxelModel.parse(vox);
			} catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("Part " + name + ": " + ex.getMessage());
			}
			int[] origin = ints(p, "origin", name);
			float[] pivot = p.has("pivot") ? floats(p.getAsJsonArray("pivot"), name)
				: SPINNING.contains(role) ? new float[]{origin[0] + v.sx / 2f, origin[1] + v.sy / 2f, origin[2] + v.sz / 2f} // its center
				: new float[]{origin[0] + v.sx / 2f, origin[1] + v.sy, origin[2] + v.sz / 2f}; // default: top center
			boolean limb = role.equals("leg") || role.equals("arm");
			int phase = p.has("phase") ? p.get("phase").getAsInt() & 1 : limb ? limbs & 1 : 0;
			if (limb) limbs++;
			parts.add(new Part(name, role, phase, pivot, origin, v));
		}
		EntityModel m = new EntityModel(parts, scale);
		if (m.maxX - m.minX > MAX_SPAN || m.maxY - m.minY > MAX_SPAN || m.maxZ - m.minZ > MAX_SPAN) {
			throw new IllegalArgumentException("The whole model must fit in " + MAX_SPAN + " voxels per side");
		}
		return m;
	}

	private static int[] ints(JsonObject p, String key, String name) {
		if (!p.has(key) || !p.get(key).isJsonArray() || p.getAsJsonArray(key).size() != 3) {
			throw new IllegalArgumentException("Part " + name + " needs " + key + ": [x, y, z]");
		}
		JsonArray a = p.getAsJsonArray(key);
		return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
	}

	private static float[] floats(JsonArray a, String name) {
		if (a.size() != 3) throw new IllegalArgumentException("Part " + name + " needs pivot: [x, y, z]");
		return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
	}

	/** Hitbox width and height in blocks. */
	public float width() {
		return Math.max(0.2f, 2 * Math.max(Math.max(-minX, maxX), Math.max(-minZ, maxZ)) / scale);
	}

	public float height() {
		return Math.max(0.2f, maxY / scale);
	}

	/** A part's faces in blocks, relative to its pivot (the renderer moves them to the pivot and rotates them there). */
	public List<VoxelModel.Quad> mesh(Part p) {
		float u = 1 / scale;
		return p.voxels.mesh(u, (p.origin[0] - p.pivot[0]) * u, (p.origin[1] - p.pivot[1]) * u, (p.origin[2] - p.pivot[2]) * u);
	}

	/** All parts at rest as one voxel grid over the bounds, for pictures: colors[(y * sz + z) * sx + x], 0 = empty. */
	public int[] flatten() {
		int sx = maxX - minX, sy = maxY - minY, sz = maxZ - minZ;
		int[] out = new int[sx * sy * sz];
		for (Part p : parts) {
			VoxelModel v = p.voxels;
			for (int y = 0; y < v.sy; y++) for (int z = 0; z < v.sz; z++) for (int x = 0; x < v.sx; x++) {
				int c = v.at(x, y, z);
				if (c == 0) continue;
				int gx = p.origin[0] + x - minX, gy = p.origin[1] + y - minY, gz = p.origin[2] + z - minZ;
				out[(gy * sz + gz) * sx + gx] = c;
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- self-check

	public static void main(String[] args) {
		JsonObject pig = JsonParser.parseString("""
			{"palette":{"p":"#F0A0A0","s":"#E08080"},"parts":[
			 {"name":"body","role":"body","origin":[-3,4,-5],"layers":[["pppppp","pppppp","pppppp","pppppp","pppppp","pppppp","pppppp","pppppp","pppppp","pppppp"]]},
			 {"name":"head","role":"head","origin":[-2,4,5],"pivot":[0,6,5],"layers":[["pppp","pppp","pppp"],["pppp","pppp","ppss"]]},
			 {"name":"leg1","role":"leg","origin":[-3,0,3],"layers":[["ss"],["ss"],["ss"],["ss"]]},
			 {"name":"leg2","role":"leg","origin":[1,0,3],"layers":[["ss"],["ss"],["ss"],["ss"]]},
			 {"name":"leg3","role":"leg","origin":[-3,0,-5],"layers":[["ss"],["ss"],["ss"],["ss"]]},
			 {"name":"leg4","role":"leg","origin":[1,0,-5],"layers":[["ss"],["ss"],["ss"],["ss"]]}
			]}""").getAsJsonObject();
		EntityModel m = parse(pig, 16);
		check(m.parts.size() == 6, "six parts");
		check(m.minY == 0 && m.maxY == 6 && m.minZ == -5 && m.maxZ == 8, "bounds");
		check(Math.abs(m.height() - 6 / 16f) < 1e-6, "height in blocks");
		check(Math.abs(m.width() - 16 / 16f) < 1e-6, "width covers the farthest part from center (z = 8)");
		check(m.parts.get(2).phase() == 0 && m.parts.get(3).phase() == 1, "legs alternate by default");
		check(m.parts.get(3).pivot()[1] == 4, "default pivot is the part's top center");
		List<VoxelModel.Quad> head = m.mesh(m.parts.get(1));
		float minZ = Float.MAX_VALUE;
		for (VoxelModel.Quad q : head) for (int k = 0; k < 4; k++) minZ = Math.min(minZ, q.corners()[k * 3 + 2]);
		check(Math.abs(minZ) < 1e-6, "head mesh starts at its pivot plane");
		int[] flat = m.flatten();
		check(flat.length == 6 * 6 * 13 && flat[0] != 0, "flattened grid includes the corner leg");
		try {
			parse(JsonParser.parseString("{\"palette\":{},\"parts\":[{\"role\":\"fin\",\"origin\":[0,0,0],\"layers\":[[\".\"]]}]}").getAsJsonObject(), 16);
			check(false, "unknown roles are rejected");
		} catch (IllegalArgumentException expected) {}
		System.out.println("EntityModel self-check passed.");
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
	}
}
