package dev.claudecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * The "particles" part of an effect: real particles that each player's game simulates. A sprite, colors and sizes
 * over each particle's life, an emitter (a burst and/or a steady rate while the part lasts, where particles appear and
 * how fast they start) and an optional flow field that pushes them every tick, all as math expressions. Flow is what
 * makes tornadoes, vortices, updrafts and orbits. This class is the shared math; the client's CustomParticle draws.
 */
public final class CustomParticles {
	private CustomParticles() {}

	/** Variables every expression sees. x, y, z: offset from where the part plays (0 in spawn). */
	public static final List<String> VARS = List.of("x", "y", "z", "t", "i", "n", "age", "life", "r1", "r2", "r3", "r4");
	public static final int X = 0, Y = 1, Z = 2, T = 3, I = 4, N = 5, AGE = 6, LIFE = 7, R1 = 8;
	static final int MAX_BURST = 5000, MAX_RATE = 2000;
	static final double MAX_LIFETIME = 30;
	static final int MAX_SPRITE = 16, MAX_SPRITE_FRAMES = 16;

	private static final Map<String, Spec> SPECS = new ConcurrentHashMap<>();

	static void definitionsChanged() {
		SPECS.clear();
	}

	/** A particles part, parsed and cached by effect id and part number; null if it's broken. Both sides. */
	public static Spec spec(String effectId, int part, JsonObject p) {
		return SPECS.computeIfAbsent(effectId + "#" + part, k -> {
			try {
				return new Spec(p);
			} catch (RuntimeException e) {
				return null;
			}
		});
	}

	/** Sprites that are already colored (average color named): tinting can only darken or fade them, not change their color. */
	static final Map<String, String> PRECOLORED = Map.ofEntries(Map.entry("glint", "green"), Map.entry("copper_fire_flame", "green"),
		Map.entry("noxious_gas", "yellow-green"), Map.entry("flame", "orange"), Map.entry("lava", "orange"), Map.entry("soul_fire_flame", "cyan"),
		Map.entry("sonic_boom", "cyan"), Map.entry("shriek", "cyan"), Map.entry("sculk_soul", "teal"), Map.entry("sculk_charge", "teal"),
		Map.entry("heart", "red"), Map.entry("cherry", "pink"), Map.entry("bubble", "blue"), Map.entry("splash", "blue"));

	/**
	 * Claude naturally writes CSS-style #RRGGBBAA, but colors here are #AARRGGBB, so "#F0F8FF60" (pale blue, faint) reads as
	 * a solid yellow-green; that's why particles kept coming out green. New particle definitions use #RRGGBB plus opacity,
	 * which can't be misread; this says what's wrong with a part's colors, or null. (Saved definitions still load.)
	 */
	static String colorProblem(JsonObject p) {
		for (String key : List.of("colors", "color")) {
			if (!p.has(key)) continue;
			JsonArray cs = Spec.list(p, key, "#FFFFFF");
			for (JsonElement c : cs) {
				String h = c.getAsString().trim();
				if (h.replace("#", "").length() == 8) {
					return "particle colors are #RRGGBB (6 digits), got " + h + "; for transparency and fading use opacity, e.g. opacity: [0.8, 0]";
				}
			}
		}
		return null;
	}

	/** Advice when a part tints an already colored sprite, which is why particles come out green instead of gold; or null. */
	static String tintWarning(JsonObject p) {
		if (p.has("sprite") || !p.has("colors") && !p.has("color")) return null;
		String tex = p.has("texture") ? p.get("texture").getAsString().replaceFirst("^minecraft:", "") : "generic";
		String family = tex.replaceFirst("_\\d+$", "");
		String color = PRECOLORED.get(family);
		return color == null ? null : "texture " + tex + " is already " + color + ", and colors only darken it, so it will look " + color
			+ "; for your own colors use a white sprite like spark, glitter, glow, effect, generic or big_smoke";
	}

	public static final class Spec {
		/** A particle sprite name (minecraft:big_smoke) or block:<block id>. */
		public final String texture;
		/** Claude's own pixel-art sprite: ARGB pixels per frame (row by row), or null to use texture. */
		public final int[][] spriteFrames;
		public final int spriteW, spriteH;
		public final boolean randomFrame, emissive, collide, trail;
		private final int[] colors;
		private final float[] sizes, opacity;
		private final double lifeMin, lifeMax, gravity, dragPerTick, spin;
		public final int burst;
		final double rate;
		private final Shapes.Expr[] spawn, velocity, flow;

		/** Parse and validate. Throws IllegalArgumentException (or a JSON cast error) with the reason. */
		public Spec(JsonObject d) {
			String tex = d.has("texture") ? d.get("texture").getAsString().trim() : "generic";
			if (tex.startsWith("block:")) {
				Identifier b = Identifier.tryParse(tex.substring(6));
				if (b == null || BuiltInRegistries.BLOCK.getOptional(b).isEmpty()) throw new IllegalArgumentException("unknown block in texture " + tex);
			} else {
				if (Identifier.tryParse(tex) == null) throw new IllegalArgumentException("bad texture name " + tex);
				if (!tex.contains(":")) tex = "minecraft:" + tex;
			}
			texture = tex;
			if (d.has("sprite")) {
				JsonObject s = d.getAsJsonObject("sprite");
				Map<Integer, Integer> palette = new java.util.HashMap<>();
				if (s.has("palette")) for (var e : s.getAsJsonObject("palette").entrySet()) {
					if (e.getKey().codePointCount(0, e.getKey().length()) != 1) throw new IllegalArgumentException("sprite palette keys are single characters");
					palette.put(e.getKey().codePointAt(0), VoxelModel.color(e.getValue().getAsString()));
				}
				JsonArray frames = new JsonArray();
				if (s.has("frames")) frames = s.getAsJsonArray("frames");
				else if (s.has("pixels")) frames.add(s.getAsJsonArray("pixels"));
				if (frames.isEmpty() || frames.size() > MAX_SPRITE_FRAMES) throw new IllegalArgumentException("sprite needs pixels, or 1 to " + MAX_SPRITE_FRAMES + " frames");
				int w = 0, h = 0;
				for (JsonElement f : frames) {
					h = Math.max(h, f.getAsJsonArray().size());
					for (JsonElement row : f.getAsJsonArray()) w = Math.max(w, row.getAsString().codePointCount(0, row.getAsString().length()));
				}
				if (w == 0 || h == 0 || w > MAX_SPRITE || h > MAX_SPRITE) throw new IllegalArgumentException("sprite frames are 1 to " + MAX_SPRITE + " pixels per side");
				spriteW = w;
				spriteH = h;
				spriteFrames = new int[frames.size()][w * h];
				for (int k = 0; k < frames.size(); k++) {
					JsonArray rows = frames.get(k).getAsJsonArray();
					for (int y = 0; y < rows.size(); y++) {
						String row = rows.get(y).getAsString();
						int x = 0;
						for (int i = 0; i < row.length(); x++) {
							int cp = row.codePointAt(i);
							i += Character.charCount(cp);
							if (cp == '.' || cp == ' ') continue;
							Integer c = palette.get(cp);
							if (c == null) throw new IllegalArgumentException("sprite: '" + new String(Character.toChars(cp)) + "' isn't in its palette");
							spriteFrames[k][y * w + x] = c;
						}
					}
				}
			} else {
				spriteFrames = null;
				spriteW = spriteH = 0;
			}
			randomFrame = d.has("frames") && d.get("frames").getAsString().equals("random");
			emissive = d.has("emissive") && d.get("emissive").getAsBoolean();
			collide = d.has("collide") && d.get("collide").getAsBoolean();
			trail = d.has("trail") && d.get("trail").getAsBoolean();
			JsonArray cs = list(d, d.has("colors") ? "colors" : "color", "#FFFFFF");
			colors = new int[cs.size()];
			for (int k = 0; k < colors.length; k++) colors[k] = VoxelModel.color(cs.get(k).getAsString());
			JsonArray os = list(d, "opacity", "1");
			opacity = new float[os.size()];
			for (int k = 0; k < opacity.length; k++) opacity[k] = Math.clamp(os.get(k).getAsFloat(), 0, 1);
			if (opacity.length == 0) throw new IllegalArgumentException("opacity needs at least one value");
			JsonArray ss = list(d, "sizes", "0.2");
			sizes = new float[ss.size()];
			for (int k = 0; k < sizes.length; k++) sizes[k] = Math.clamp(ss.get(k).getAsFloat(), 0, 16);
			if (colors.length == 0 || sizes.length == 0) throw new IllegalArgumentException("colors and sizes need at least one value");
			JsonArray life = list(d, "lifetime", "1");
			lifeMin = Math.clamp(life.get(0).getAsDouble(), 0.05, MAX_LIFETIME);
			lifeMax = Math.clamp(life.get(life.size() - 1).getAsDouble(), lifeMin, MAX_LIFETIME);
			gravity = num(d, "gravity", 0);
			dragPerTick = Math.pow(1 - Math.clamp(num(d, "drag", 0), 0, 1), 1 / 20.0);
			spin = num(d, "spin", 0) * Math.PI * 2;
			rate = Math.clamp(num(d, "rate", 0), 0, MAX_RATE);
			burst = (int) Math.clamp(num(d, "count", rate > 0 ? 0 : 20), 0, MAX_BURST);
			if (burst == 0 && rate == 0) throw new IllegalArgumentException("give count (a burst) or rate (per second)");
			spawn = exprs(d, "spawn");
			velocity = exprs(d, "velocity");
			flow = d.has("flow") ? exprs(d, "flow") : null;
		}

		private static JsonArray list(JsonObject d, String key, String dflt) {
			JsonElement e = d.get(key);
			if (e != null && e.isJsonArray()) return e.getAsJsonArray();
			JsonArray a = new JsonArray();
			if (e == null) a.add(dflt);
			else a.add(e);
			return a;
		}

		private static double num(JsonObject d, String key, double dflt) {
			double v = d.has(key) ? d.get(key).getAsDouble() : dflt;
			if (!Double.isFinite(v)) throw new IllegalArgumentException(key + " must be a number");
			return v;
		}

		/** x, y, z expressions of an object like {"x": "cos(r1*2*pi)", "z": 1}; missing ones are 0. */
		private static Shapes.Expr[] exprs(JsonObject d, String key) {
			JsonObject o = d.has(key) ? d.getAsJsonObject(key) : new JsonObject();
			Shapes.Expr[] out = new Shapes.Expr[3];
			String[] axes = {"x", "y", "z"};
			for (int k = 0; k < 3; k++) {
				try {
					out[k] = o.has(axes[k]) ? Shapes.parse(o.get(axes[k]).getAsString(), VARS) : v -> 0;
				} catch (IllegalArgumentException e) {
					throw new IllegalArgumentException(key + "." + axes[k] + ": " + e.getMessage());
				}
			}
			return out;
		}

		/** Particles emitted before tick k of the part's own time (0 is its first tick): the burst, then the rate. */
		public long emittedBefore(int k) {
			return k <= 0 ? 0 : burst + (long) Math.floor(rate * k / 20);
		}

		/** Roughly how many particles the part makes over a span of ticks (the n variable). */
		public long total(int span) {
			return emittedBefore(span);
		}

		/**
		 * Set up particle i, born at effect time `tick`: fills its variables (random r1..r4, offset x y z) and its
		 * starting velocity in blocks per tick. Returns its lifetime in ticks.
		 */
		public int spawn(double[] v, double[] vel, long i, long n, int tick, RandomGenerator rnd) {
			v[T] = tick / 20.0;
			v[I] = i;
			v[N] = n;
			v[AGE] = v[LIFE] = 0;
			for (int k = 0; k < 4; k++) v[R1 + k] = rnd.nextDouble();
			v[X] = v[Y] = v[Z] = 0;
			double[] at = new double[3];
			for (int k = 0; k < 3; k++) at[k] = finite(spawn[k].eval(v));
			System.arraycopy(at, 0, v, X, 3);
			for (int k = 0; k < 3; k++) vel[k] = finite(velocity[k].eval(v)) / 20;
			return (int) Math.max(1, Math.round((lifeMin + rnd.nextDouble() * (lifeMax - lifeMin)) * 20));
		}

		/** Spin for one particle, radians per tick, in a random direction. */
		public float spinPerTick(RandomGenerator rnd) {
			return (float) (spin / 20 * (rnd.nextBoolean() ? 1 : -1));
		}

		/**
		 * One tick of motion. v holds the particle's variables, already updated for this tick; vel is its own velocity
		 * (blocks per tick), slowed by drag and pulled by gravity. d receives this tick's movement: vel plus the flow.
		 */
		public void step(double[] v, double[] vel, double[] d) {
			vel[1] -= gravity / 400;
			for (int k = 0; k < 3; k++) {
				vel[k] *= dragPerTick;
				d[k] = vel[k] + (flow == null ? 0 : finite(flow[k].eval(v)) / 20);
			}
		}

		/** ARGB color at a point in the particle's life (0 to 1). */
		public int color(float life) {
			float f = life * (colors.length - 1);
			int a = Math.min((int) f, colors.length - 1), b = Math.min(a + 1, colors.length - 1);
			float w = f - a;
			int out = 0;
			for (int shift = 0; shift < 32; shift += 8) {
				int ca = (colors[a] >>> shift) & 0xFF, cb = (colors[b] >>> shift) & 0xFF;
				out |= Math.round(ca + (cb - ca) * w) << shift;
			}
			int alpha = Math.round((out >>> 24) * lerp(opacity, life));
			return out & 0xFFFFFF | alpha << 24;
		}

		/** A value from a list spread evenly over a particle's life (0 to 1). */
		private static float lerp(float[] values, float life) {
			float f = life * (values.length - 1);
			int a = Math.min((int) f, values.length - 1), b = Math.min(a + 1, values.length - 1);
			return values[a] + (values[b] - values[a]) * (f - a);
		}

		/** Width in blocks at a point in the particle's life. */
		public float size(float life) {
			float f = life * (sizes.length - 1);
			int a = Math.min((int) f, sizes.length - 1), b = Math.min(a + 1, sizes.length - 1);
			return sizes[a] + (sizes[b] - sizes[a]) * (f - a);
		}

		private static double finite(double x) {
			return Double.isFinite(x) ? x : 0;
		}

		/**
		 * A dry run without collisions over the part's span (in ticks), so Claude, who can't see particles, can tell
		 * whether its math does what it meant.
		 */
		String summary(int span, RandomGenerator rnd) {
			long n = total(span);
			int samples = (int) Math.min(n, 300);
			if (samples == 0) return "no particles in its time";
			double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
			double[] v = new double[VARS.size()], vel = new double[3], d = new double[3];
			double totalLife = 0;
			for (int s = 0; s < samples; s++) {
				long i = s * n / samples;
				int tick = 0;
				while (emittedBefore(tick + 1) <= i) tick++;
				int life = spawn(v, vel, i, n, tick, rnd);
				totalLife += life;
				double[] p = {v[X], v[Y], v[Z]};
				for (int age = 0; age <= life; age++) {
					for (int k = 0; k < 3; k++) {
						min[k] = Math.min(min[k], p[k]);
						max[k] = Math.max(max[k], p[k]);
					}
					v[T] = (tick + age + 1) / 20.0;
					v[AGE] = (age + 1) / 20.0;
					v[LIFE] = (age + 1) / (double) life;
					step(v, vel, d);
					for (int k = 0; k < 3; k++) v[X + k] = p[k] += d[k];
				}
			}
			double avgLife = totalLife / samples;
			long alive = Math.round(Math.min(n, burst + rate * avgLife / 20));
			return String.format(Locale.ROOT, "%d particles, up to about %d at once, living %.1f s on average, within x %.1f..%.1f, "
				+ "y %.1f..%.1f, z %.1f..%.1f blocks of where it plays", n, alive, avgLife / 20, min[0], max[0], min[1], max[1], min[2], max[2]);
		}
	}
}
