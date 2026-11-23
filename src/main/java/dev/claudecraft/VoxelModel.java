package dev.claudecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A colored voxel model, as Claude designs it for custom items. Pure Java plus Gson, shared by the server
 * (validation, view_item) and the client (meshing). Self-check: {@code java -cp <gson.jar> VoxelModel.java}.
 *
 * Stored form: {"palette": {"r": "#B3312C", "g": "#8044AAFF"}, "layers": [[row, ...], ...]}, where layers go
 * bottom to top (y), rows go along +z and characters along +x, like place_blueprint. '.' and ' ' are empty.
 * Colors are #RRGGBB or #AARRGGBB; alpha below 255 is see-through.
 */
public final class VoxelModel {
	public static final int MAX_SIZE = 32;

	public final int sx, sy, sz;
	/** ARGB per voxel at (y * sz + z) * sx + x; 0 = empty. */
	public final int[] argb;

	private VoxelModel(int sx, int sy, int sz, int[] argb) {
		this.sx = sx;
		this.sy = sy;
		this.sz = sz;
		this.argb = argb;
	}

	public int at(int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return 0;
		return argb[(y * sz + z) * sx + x];
	}

	/** Parse the stored form. Throws IllegalArgumentException with a readable message for bad input. */
	public static VoxelModel parse(JsonObject model) {
		Map<Integer, Integer> palette = new HashMap<>();
		if (!model.has("palette") || !model.get("palette").isJsonObject()) throw new IllegalArgumentException("palette is required");
		for (var e : model.getAsJsonObject("palette").entrySet()) {
			if (e.getKey().codePointCount(0, e.getKey().length()) != 1) throw new IllegalArgumentException("Palette keys must be single characters, got '" + e.getKey() + "'");
			palette.put(e.getKey().codePointAt(0), color(e.getValue().getAsString()));
		}
		if (!model.has("layers") || !model.get("layers").isJsonArray()) throw new IllegalArgumentException("layers is required");
		JsonArray layers = model.getAsJsonArray("layers");
		int sy = layers.size(), sz = 0, sx = 0;
		List<List<int[]>> rows = new ArrayList<>();
		for (JsonElement layer : layers) {
			List<int[]> rs = new ArrayList<>();
			for (JsonElement row : layer.getAsJsonArray()) {
				int[] cps = row.getAsString().codePoints().toArray();
				rs.add(cps);
				sx = Math.max(sx, cps.length);
			}
			sz = Math.max(sz, rs.size());
			rows.add(rs);
		}
		if (sx == 0 || sy == 0 || sz == 0) throw new IllegalArgumentException("The model is empty");
		if (sx > MAX_SIZE || sy > MAX_SIZE || sz > MAX_SIZE) {
			throw new IllegalArgumentException("Model is " + sx + "x" + sy + "x" + sz + "; at most " + MAX_SIZE + " voxels per side");
		}
		int[] argb = new int[sx * sy * sz];
		for (int y = 0; y < sy; y++) for (int z = 0; z < rows.get(y).size(); z++) {
			int[] cps = rows.get(y).get(z);
			for (int x = 0; x < cps.length; x++) {
				int cp = cps[x];
				if (cp == '.' || cp == ' ') continue;
				Integer c = palette.get(cp);
				if (c == null) throw new IllegalArgumentException("Character '" + new String(Character.toChars(cp)) + "' isn't in the palette");
				argb[(y * sz + z) * sx + x] = c;
			}
		}
		return new VoxelModel(sx, sy, sz, argb);
	}

	/**
	 * Turn a flat pixel-art drawing into the stored form: rows go top to bottom, characters left to right (+x),
	 * extruded `thickness` voxels along z, like a vanilla item sprite.
	 */
	public static JsonObject fromPixels(JsonObject palette, JsonArray pixels, int thickness) {
		int t = Math.max(1, Math.min(MAX_SIZE, thickness));
		JsonArray layers = new JsonArray();
		for (int r = pixels.size() - 1; r >= 0; r--) { // bottom row is layer 0
			JsonArray layer = new JsonArray();
			for (int k = 0; k < t; k++) layer.add(pixels.get(r).getAsString());
			layers.add(layer);
		}
		JsonObject out = new JsonObject();
		out.add("palette", palette);
		out.add("layers", layers);
		return out;
	}

	static int color(String s) {
		String h = s.trim();
		if (h.startsWith("#")) h = h.substring(1);
		try {
			if (h.length() == 6) return 0xFF000000 | Integer.parseInt(h, 16);
			if (h.length() == 8) return (int) Long.parseLong(h, 16);
		} catch (NumberFormatException ignored) {}
		throw new IllegalArgumentException("Colors are #RRGGBB or #AARRGGBB, got '" + s + "'");
	}

	// ---------------------------------------------------------------- meshing

	/** One face: four corners (x,y,z each, in item units where the 16-voxel cube spans 0..1), its normal and color. */
	public record Quad(float[] corners, float nx, float ny, float nz, int argb) {
		public boolean translucent() {
			return (argb >>> 24) < 255;
		}
	}

	// Corner offsets for each face, counter-clockwise seen from outside, and its normal.
	private static final int[][][] FACES = {
		{{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}}, // +x
		{{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}, // -x
		{{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}}, // +y
		{{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}}, // -y
		{{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}}, // +z
		{{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}}, // -z
	};
	private static final int[][] NORMALS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	/**
	 * Faces that can be seen: next to an empty voxel, or an opaque voxel next to a see-through one. Voxels are
	 * 1/16 of an item; models bigger than 16 are scaled down to fit, and every model is centered in the cube.
	 */
	public List<Quad> mesh() {
		float unit = 1f / Math.max(16, Math.max(sx, Math.max(sy, sz)));
		float ox = (1 - sx * unit) / 2, oy = (1 - sy * unit) / 2, oz = (1 - sz * unit) / 2;
		List<Quad> quads = new ArrayList<>();
		for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
			int c = at(x, y, z);
			if (c == 0) continue;
			boolean see = (c >>> 24) < 255;
			for (int f = 0; f < 6; f++) {
				int[] n = NORMALS[f];
				int nb = at(x + n[0], y + n[1], z + n[2]);
				boolean nbSee = nb != 0 && (nb >>> 24) < 255;
				if (nb != 0 && !(nbSee && (!see || nb != c))) continue; // hidden behind a neighbor
				float[] corners = new float[12];
				for (int k = 0; k < 4; k++) {
					corners[k * 3] = ox + (x + FACES[f][k][0]) * unit;
					corners[k * 3 + 1] = oy + (y + FACES[f][k][1]) * unit;
					corners[k * 3 + 2] = oz + (z + FACES[f][k][2]) * unit;
				}
				quads.add(new Quad(corners, n[0], n[1], n[2], c));
			}
		}
		return quads;
	}

	// ---------------------------------------------------------------- self-check

	public static void main(String[] args) {
		JsonObject one = JsonParser.parseString("{\"palette\":{\"r\":\"#FF0000\"},\"layers\":[[\"r\"]]}").getAsJsonObject();
		VoxelModel m = parse(one);
		check(m.mesh().size() == 6, "a single voxel has six faces");
		check(m.mesh().get(0).argb() == 0xFFFF0000, "opaque color");
		float[] c0 = m.mesh().get(0).corners();
		check(Math.abs(c0[0] - (0.5f + 1f / 32)) < 1e-6, "a lone voxel is centered");

		JsonObject bar = JsonParser.parseString("{\"palette\":{\"s\":\"#888888\"},\"layers\":[[\"ss\"]]}").getAsJsonObject();
		check(parse(bar).mesh().size() == 10, "two touching voxels hide their shared faces");

		JsonObject glass = JsonParser.parseString("{\"palette\":{\"s\":\"#888888\",\"g\":\"#80FFFFFF\"},\"layers\":[[\"sg\"]]}").getAsJsonObject();
		List<Quad> gq = parse(glass).mesh();
		check(gq.size() == 11, "an opaque face behind glass stays visible, the glass face against it doesn't");
		check(gq.stream().filter(Quad::translucent).count() == 5, "five glass faces");

		JsonObject pal = JsonParser.parseString("{\"a\":\"#123456\"}").getAsJsonObject();
		JsonArray px = JsonParser.parseString("[\"a.\", \"aa\"]").getAsJsonArray();
		VoxelModel flat = parse(fromPixels(pal, px, 1));
		check(flat.sx == 2 && flat.sy == 2 && flat.sz == 1, "pixels become a 2x2x1 model");
		check(flat.at(0, 1, 0) != 0 && flat.at(1, 1, 0) == 0 && flat.at(1, 0, 0) != 0, "top pixel row is the top layer");

		check(color("#80FFFFFF") == 0x80FFFFFF, "alpha colors");
		try {
			parse(JsonParser.parseString("{\"palette\":{},\"layers\":[[\"x\"]]}").getAsJsonObject());
			check(false, "unknown characters are rejected");
		} catch (IllegalArgumentException expected) {}
		System.out.println("VoxelModel self-check passed.");
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
	}
}
