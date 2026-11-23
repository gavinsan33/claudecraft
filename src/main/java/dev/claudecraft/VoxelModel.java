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
 * A colored voxel model, as Claude designs it for custom items and blocks. Pure Java plus Gson, shared by the
 * server (validation, pictures, block shapes) and the client (meshing). Self-check:
 * {@code java -cp <gson.jar> src/main/java/dev/claudecraft/VoxelModel.java}.
 *
 * Two stored forms, both with "palette": {"r": "#B3312C", "g": "#8044AAFF"} (#RRGGBB or #AARRGGBB; alpha
 * below FF is see-through; '.' and ' ' are empty):
 * - voxels: "layers": [[row, ...], ...], layers bottom to top (y), rows along +z, characters along +x,
 *   like place_blueprint.
 * - a full cube painted like a vanilla block: "faces": {"all"|"side"|"top"|"bottom"|"north"|...: 16 rows of
 *   16 characters}, each seen from outside the block with up toward the sky (top: north up).
 */
public final class VoxelModel {
	public static final int MAX_SIZE = 32;
	private static final String[] FACE_NAMES = {"east", "west", "top", "bottom", "south", "north"};

	public final int sx, sy, sz;
	/** ARGB per voxel at (y * sz + z) * sx + x; 0 = empty. For cubes, the surface shows the face colors. */
	public final int[] argb;
	/** Cubes only: per face (FACE_NAMES order), 16x16 colors indexed [v * 16 + u] in the face's plane axes. */
	private final int[][] faces;

	private VoxelModel(int sx, int sy, int sz, int[] argb, int[][] faces) {
		this.sx = sx;
		this.sy = sy;
		this.sz = sz;
		this.argb = argb;
		this.faces = faces;
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
		if (model.has("faces")) return parseFaces(model.getAsJsonObject("faces"), palette);
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
			for (int x = 0; x < cps.length; x++) argb[(y * sz + z) * sx + x] = lookup(palette, cps[x]);
		}
		return new VoxelModel(sx, sy, sz, argb, null);
	}

	private static int lookup(Map<Integer, Integer> palette, int cp) {
		if (cp == '.' || cp == ' ') return 0;
		Integer c = palette.get(cp);
		if (c == null) throw new IllegalArgumentException("Character '" + new String(Character.toChars(cp)) + "' isn't in the palette");
		return c;
	}

	private static VoxelModel parseFaces(JsonObject f, Map<Integer, Integer> palette) {
		int[][] faces = new int[6][];
		for (int i = 0; i < 6; i++) {
			String name = FACE_NAMES[i];
			boolean cap = i == 2 || i == 3;
			// A face's own texture, else "side" for the four sides, else "all"; top and bottom fall back to "side" last.
			String key = f.has(name) ? name : !cap && f.has("side") ? "side" : f.has("all") ? "all" : "side";
			if (!f.has(key)) throw new IllegalArgumentException("faces needs all, or side plus top and bottom (missing " + name + ")");
			JsonArray rows = f.getAsJsonArray(key);
			if (rows.size() != 16) throw new IllegalArgumentException("Face " + key + " needs 16 rows, got " + rows.size());
			int[] grid = new int[256];
			for (int r = 0; r < 16; r++) {
				int[] cps = rows.get(r).getAsString().codePoints().toArray();
				if (cps.length != 16) throw new IllegalArgumentException("Face " + key + " row " + (r + 1) + " needs 16 characters, got " + cps.length);
				for (int c = 0; c < 16; c++) {
					int[] uv = faceUv(i, r, c);
					grid[uv[1] * 16 + uv[0]] = lookup(palette, cps[c]);
				}
			}
			faces[i] = grid;
		}
		// A voxel version for pictures and shapes: each surface voxel takes a face color (top and bottom win at edges).
		int[] argb = new int[16 * 16 * 16];
		for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
			int c;
			if (y == 15) c = faces[2][z * 16 + x];
			else if (y == 0) c = faces[3][z * 16 + x];
			else if (x == 15) c = faces[0][y * 16 + z];
			else if (x == 0) c = faces[1][y * 16 + z];
			else if (z == 15) c = faces[4][y * 16 + x];
			else if (z == 0) c = faces[5][y * 16 + x];
			else c = 0xFF808080; // hidden inside
			argb[(y * 16 + z) * 16 + x] = c;
		}
		return new VoxelModel(16, 16, 16, argb, faces);
	}

	/**
	 * Where texture pixel (row r from the top, column c from the left, as seen from outside) lands in face i's
	 * plane coordinates {u, v}, using the same plane axes as meshing (see AXES).
	 */
	private static int[] faceUv(int i, int r, int c) {
		int up = 15 - r;
		return switch (i) {
			case 0 -> new int[]{15 - c, up}; // east: looking west, right is north (-z); u = z
			case 1 -> new int[]{c, up}; // west: looking east, right is south; u = z
			case 2 -> new int[]{c, r}; // top: north up; u = x, v = z
			case 3 -> new int[]{c, 15 - r}; // bottom: seen from below, north down; u = x, v = z
			case 4 -> new int[]{c, up}; // south: looking north, right is east; u = x
			default -> new int[]{15 - c, up}; // north: looking south, right is west; u = x
		};
	}

	/**
	 * Turn a flat pixel-art drawing into the voxel form: rows go top to bottom, characters left to right (+x),
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

	public static int color(String s) {
		String h = s.trim();
		if (h.startsWith("#")) h = h.substring(1);
		try {
			if (h.length() == 6) return 0xFF000000 | Integer.parseInt(h, 16);
			if (h.length() == 8) return (int) Long.parseLong(h, 16);
		} catch (NumberFormatException ignored) {}
		throw new IllegalArgumentException("Colors are #RRGGBB or #AARRGGBB, got '" + s + "'");
	}

	/** Average of the opaque surface colors, for pictures and map colors. */
	public int averageColor() {
		long r = 0, g = 0, b = 0, n = 0;
		for (int c : argb) {
			if (c == 0 || (c >>> 24) == 0) continue;
			r += (c >> 16) & 255;
			g += (c >> 8) & 255;
			b += c & 255;
			n++;
		}
		return n == 0 ? 0xFF808080 : 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
	}

	// ---------------------------------------------------------------- meshing

	/**
	 * One face: four corners (x,y,z each, in units where 16 voxels span 0..1), counter-clockwise seen from outside,
	 * its normal, color, and the side of the unit cube it lies on (FACE index, or -1 if it's inside the cube).
	 */
	public record Quad(float[] corners, int face, float nx, float ny, float nz, int argb, int boundary) {
		public boolean translucent() {
			return (argb >>> 24) < 255;
		}
	}

	// Per face: the axis it faces along (0 x, 1 y, 2 z), and its in-plane axes u and v.
	private static final int[][] AXES = {{0, 2, 1}, {0, 2, 1}, {1, 0, 2}, {1, 0, 2}, {2, 0, 1}, {2, 0, 1}};
	private static final boolean[] POSITIVE = {true, false, true, false, true, false};
	// Corner offsets for each face, counter-clockwise seen from outside.
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
	 * Visible faces, with same-colored neighbors merged into larger quads. A face shows next to an empty voxel, or
	 * when an opaque voxel sits behind a see-through one. Voxels are 1/16 of a block.
	 *
	 * @param block true for a placed block: the model sits on the block's floor (centered in x and z);
	 *              false for an item: centered in the cube, and scaled down to fit if bigger than 16
	 */
	public List<Quad> mesh(boolean block) {
		float unit = 1f / (block ? 16 : Math.max(16, Math.max(sx, Math.max(sy, sz))));
		return mesh(unit, new float[]{(1 - sx * unit) / 2, block ? 0 : (1 - sy * unit) / 2, (1 - sz * unit) / 2}, block);
	}

	/** Faces with each voxel `unit` wide and the model's minimum corner at (ox, oy, oz). Nothing is marked for culling. */
	public List<Quad> mesh(float unit, float ox, float oy, float oz) {
		return mesh(unit, new float[]{ox, oy, oz}, false);
	}

	private List<Quad> mesh(float unit, float[] off, boolean block) {
		int[] size = {sx, sy, sz};
		List<Quad> quads = new ArrayList<>();
		for (int f = 0; f < 6; f++) {
			int a = AXES[f][0], u = AXES[f][1], v = AXES[f][2];
			int[] n = NORMALS[f];
			for (int slice = 0; slice < size[a]; slice++) {
				int[] mask = new int[size[u] * size[v]];
				boolean any = false;
				for (int j = 0; j < size[v]; j++) for (int i = 0; i < size[u]; i++) {
					int[] p = new int[3];
					p[a] = slice;
					p[u] = i;
					p[v] = j;
					int c;
					if (faces != null) {
						// Cubes: only the outer face, painted from its texture.
						c = slice == (POSITIVE[f] ? 15 : 0) ? faces[f][j * 16 + i] : 0;
					} else {
						c = at(p[0], p[1], p[2]);
						if (c != 0 && hidden(c, at(p[0] + n[0], p[1] + n[1], p[2] + n[2]))) c = 0;
					}
					mask[j * size[u] + i] = c;
					any |= c != 0;
				}
				if (!any) continue;
				int edge = POSITIVE[f] ? size[a] - 1 : 0;
				boolean onBoundary = slice == edge && (block ? size[a] == 16 || (a == 1 && !POSITIVE[f]) : false);
				for (int[] r : rects(mask, size[u], size[v])) {
					float[] corners = new float[12];
					for (int k = 0; k < 4; k++) {
						int[] o = FACES[f][k];
						float[] p = new float[3];
						p[a] = slice + o[a];
						p[u] = r[0] + o[u] * r[2];
						p[v] = r[1] + o[v] * r[3];
						for (int d = 0; d < 3; d++) corners[k * 3 + d] = off[d] + p[d] * unit;
					}
					quads.add(new Quad(corners, f, n[0], n[1], n[2], r[4], onBoundary ? f : -1));
				}
			}
		}
		return quads;
	}

	/** Is a face of a voxel with color c hidden by its neighbor nb? */
	private static boolean hidden(int c, int nb) {
		if (nb == 0) return false;
		boolean nbSee = (nb >>> 24) < 255;
		return !nbSee || ((c >>> 24) < 255 && nb == c); // behind an opaque voxel, or inside a run of the same glass
	}

	/** Greedy rectangles of equal nonzero colors in a w*h grid: each {x, y, width, height, color}. */
	static List<int[]> rects(int[] grid, int w, int h) {
		List<int[]> out = new ArrayList<>();
		boolean[] used = new boolean[grid.length];
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
			int c = grid[y * w + x];
			if (c == 0 || used[y * w + x]) continue;
			int rw = 1;
			while (x + rw < w && grid[y * w + x + rw] == c && !used[y * w + x + rw]) rw++;
			int rh = 1;
			grow:
			while (y + rh < h) {
				for (int k = 0; k < rw; k++) if (grid[(y + rh) * w + x + k] != c || used[(y + rh) * w + x + k]) break grow;
				rh++;
			}
			for (int j = 0; j < rh; j++) for (int k = 0; k < rw; k++) used[(y + j) * w + x + k] = true;
			out.add(new int[]{x, y, rw, rh, c});
		}
		return out;
	}

	// ---------------------------------------------------------------- self-check

	public static void main(String[] args) {
		JsonObject one = JsonParser.parseString("{\"palette\":{\"r\":\"#FF0000\"},\"layers\":[[\"r\"]]}").getAsJsonObject();
		VoxelModel m = parse(one);
		check(m.mesh(false).size() == 6, "a single voxel has six faces");
		check(m.mesh(false).get(0).argb() == 0xFFFF0000, "opaque color");
		float[] c0 = m.mesh(false).get(0).corners();
		check(Math.abs(c0[0] - (0.5f + 1f / 32)) < 1e-6, "a lone item voxel is centered");
		check(m.mesh(true).stream().allMatch(q -> q.corners()[1] < 0.07f), "a block voxel sits on the floor");

		JsonObject bar = JsonParser.parseString("{\"palette\":{\"s\":\"#888888\"},\"layers\":[[\"ssss\"]]}").getAsJsonObject();
		check(parse(bar).mesh(false).size() == 6, "a bar of one color merges into six faces");

		JsonObject glass = JsonParser.parseString("{\"palette\":{\"s\":\"#888888\",\"g\":\"#80FFFFFF\"},\"layers\":[[\"sg\"]]}").getAsJsonObject();
		List<Quad> gq = parse(glass).mesh(false);
		check(gq.stream().filter(Quad::translucent).count() == 5, "glass shows five faces, not the one against stone");
		check(gq.stream().anyMatch(q -> !q.translucent() && q.face() == 0), "stone's face behind the glass stays visible");

		StringBuilder full = new StringBuilder("[");
		for (int y = 0; y < 16; y++) {
			full.append(y > 0 ? ",[" : "[");
			for (int z = 0; z < 16; z++) full.append(z > 0 ? "," : "").append("\"").append("s".repeat(16)).append("\"");
			full.append("]");
		}
		VoxelModel cube = parse(JsonParser.parseString("{\"palette\":{\"s\":\"#777777\"},\"layers\":" + full + "]}").getAsJsonObject());
		List<Quad> cq = cube.mesh(true);
		check(cq.size() == 6 && cq.stream().allMatch(q -> q.boundary() == q.face()), "a solid 16^3 block is six culled faces");

		String[] side = new String[16];
		for (int r = 0; r < 16; r++) side[r] = r < 4 ? "g".repeat(16) : "d".repeat(16);
		JsonObject faces = new JsonObject();
		faces.add("side", JsonParser.parseString(new com.google.gson.Gson().toJson(side)));
		faces.add("top", JsonParser.parseString(new com.google.gson.Gson().toJson(java.util.Collections.nCopies(16, "g".repeat(16)))));
		faces.add("bottom", JsonParser.parseString(new com.google.gson.Gson().toJson(java.util.Collections.nCopies(16, "d".repeat(16)))));
		JsonObject grass = new JsonObject();
		grass.add("palette", JsonParser.parseString("{\"g\":\"#55AA33\",\"d\":\"#7A5230\"}"));
		grass.add("faces", faces);
		VoxelModel gm = parse(grass);
		List<Quad> fq = gm.mesh(true);
		check(fq.size() == 2 + 4 * 2, "grass-style cube: one quad for top and bottom, two per side");
		check(fq.stream().filter(q -> q.face() == 4 && q.argb() == 0xFF55AA33).allMatch(q -> q.corners()[1] >= 0.74f), "green strip is at the top of the side");
		check(gm.at(8, 15, 8) == 0xFF55AA33 && gm.at(0, 8, 8) == 0xFF7A5230, "voxel version shows the faces");

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
