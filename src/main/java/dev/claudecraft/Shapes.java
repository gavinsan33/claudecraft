package dev.claudecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WorldEdit-style shape generators and a small math-expression evaluator. Pure math, no Minecraft
 * types, so it can be self-checked with {@code java src/main/java/dev/claudecraft/Shapes.java}.
 */
final class Shapes {
	private Shapes() {}

	interface Solid { boolean has(int x, int y, int z); }

	/**
	 * A solid inside an inclusive bounding box. {@code openAxis} ('x','y','z' or 0): when hollow, the shape's
	 * ends along that axis stay open (a tube, not a can; a cone roof without a floor).
	 */
	record Shape(int x0, int y0, int z0, int x1, int y1, int z1, Solid solid, char openAxis) {
		long volume() {
			return (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
		}

		/** Every block of the shape, or only its 1-thick surface when hollow. */
		List<int[]> voxels(boolean hollow) {
			List<int[]> out = new ArrayList<>();
			for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
				if (!solid.has(x, y, z)) continue;
				if (hollow && in(x + 1, y, z) && in(x - 1, y, z) && in(x, y + 1, z) && in(x, y - 1, z) && in(x, y, z + 1) && in(x, y, z - 1)) continue;
				out.add(new int[]{x, y, z});
			}
			return out;
		}

		/** Neighbor test for hollowing: past an open end counts as inside, so no cap is drawn there. */
		private boolean in(int x, int y, int z) {
			if (openAxis == 'x' && (x < x0 || x > x1)) return true;
			if (openAxis == 'y' && (y < y0 || y > y1)) return true;
			if (openAxis == 'z' && (z < z0 || z > z1)) return true;
			return solid.has(x, y, z);
		}
	}

	static Shape sphere(int cx, int cy, int cz, double rx, double ry, double rz, boolean domeOnly) {
		double ax = rx + 0.5, ay = ry + 0.5, az = rz + 0.5;
		int ex = (int) Math.ceil(rx), ey = (int) Math.ceil(ry), ez = (int) Math.ceil(rz);
		return new Shape(cx - ex, domeOnly ? cy : cy - ey, cz - ez, cx + ex, cy + ey, cz + ez, (x, y, z) -> {
			double dx = (x - cx) / ax, dy = (y - cy) / ay, dz = (z - cz) / az;
			return (!domeOnly || y >= cy) && dx * dx + dy * dy + dz * dz <= 1;
		}, domeOnly ? 'y' : 0);
	}

	/** Local coordinates: u runs along the axis (0 at the base, toward +axis, or -axis if length is negative), a and b across it. */
	interface Local { boolean has(double u, double a, double b); }

	/**
	 * Wrap a shape defined in axis-local coordinates. u covers 0..|length|-1 from the base (or centered when
	 * centered=true, u in -halfLen..halfLen), a and b cover -across..across.
	 */
	private static Shape alongAxis(int cx, int cy, int cz, char axis, int length, boolean centered, int across, Local local, char open) {
		int uMin, uMax;
		if (centered) { uMin = -length; uMax = length; }
		else if (length >= 0) { uMin = 0; uMax = Math.max(0, length - 1); }
		else { uMin = length + 1; uMax = 0; }
		int sign = length < 0 && !centered ? -1 : 1;
		Solid s = switch (axis) {
			case 'x' -> (x, y, z) -> local.has((x - cx) * sign, y - cy, z - cz);
			case 'z' -> (x, y, z) -> local.has((z - cz) * sign, x - cx, y - cy);
			default -> (x, y, z) -> local.has((y - cy) * sign, x - cx, z - cz);
		};
		return switch (axis) {
			case 'x' -> new Shape(cx + uMin, cy - across, cz - across, cx + uMax, cy + across, cz + across, s, open);
			case 'z' -> new Shape(cx - across, cy - across, cz + uMin, cx + across, cy + across, cz + uMax, s, open);
			default -> new Shape(cx - across, cy + uMin, cz - across, cx + across, cy + uMax, cz + across, s, open);
		};
	}

	static Shape cylinder(int cx, int cy, int cz, double r, int height, char axis) {
		double lim = (r + 0.5) * (r + 0.5);
		return alongAxis(cx, cy, cz, axis, height, false, (int) Math.ceil(r), (u, a, b) -> a * a + b * b <= lim, axis);
	}

	/** Radius r at the base, shrinking linearly to a single block at the tip. Negative height points down. */
	static Shape cone(int cx, int cy, int cz, double r, int height) {
		int h = Math.max(1, Math.abs(height));
		return alongAxis(cx, cy, cz, 'y', height, false, (int) Math.ceil(r), (u, a, b) -> {
			double rad = h == 1 ? r : r * (h - 1 - u) / (h - 1);
			return a * a + b * b <= (rad + 0.5) * (rad + 0.5);
		}, 'y');
	}

	/** Square pyramid with half-width r at the base. height defaults to r+1, which gives one-block steps. */
	static Shape pyramid(int cx, int cy, int cz, double r, int height) {
		int h = Math.max(1, Math.abs(height));
		return alongAxis(cx, cy, cz, 'y', height, false, (int) Math.ceil(r), (u, a, b) -> {
			double half = h == 1 ? r : r * (h - 1 - u) / (h - 1);
			return Math.max(Math.abs(a), Math.abs(b)) <= half + 1e-9;
		}, 'y');
	}

	/** Ring of tube radius r around a circle of radius big; axis is the hole's direction (y = lying flat). */
	static Shape torus(int cx, int cy, int cz, double big, double r, char axis) {
		double lim = (r + 0.5) * (r + 0.5);
		return alongAxis(cx, cy, cz, axis, (int) Math.ceil(r), true, (int) Math.ceil(big + r), (u, a, b) -> {
			double d = Math.sqrt(a * a + b * b) - big;
			return d * d + u * u <= lim;
		}, (char) 0);
	}

	/** Polyline through the points; radius 0 is a connected one-block line, larger values make a round beam. */
	static List<int[]> line(List<int[]> points, double radius) {
		Set<List<Integer>> seen = new LinkedHashSet<>();
		int ext = (int) Math.ceil(radius);
		double lim = (radius + 0.5) * (radius + 0.5);
		for (int i = 0; i + 1 < points.size() || (points.size() == 1 && i == 0); i++) {
			int[] p = points.get(i), q = points.get(Math.min(i + 1, points.size() - 1));
			double len = Math.max(Math.abs(q[0] - p[0]), Math.max(Math.abs(q[1] - p[1]), Math.abs(q[2] - p[2])));
			int steps = (int) Math.ceil(len * 2);
			for (int s = 0; s <= steps; s++) {
				double t = steps == 0 ? 0 : (double) s / steps;
				int x = (int) Math.round(p[0] + (q[0] - p[0]) * t);
				int y = (int) Math.round(p[1] + (q[1] - p[1]) * t);
				int z = (int) Math.round(p[2] + (q[2] - p[2]) * t);
				for (int dy = -ext; dy <= ext; dy++) for (int dz = -ext; dz <= ext; dz++) for (int dx = -ext; dx <= ext; dx++) {
					if (dx * dx + dy * dy + dz * dz <= lim) seen.add(List.of(x + dx, y + dy, z + dz));
				}
			}
		}
		List<int[]> out = new ArrayList<>();
		for (List<Integer> v : seen) out.add(new int[]{v.get(0), v.get(1), v.get(2)});
		return out;
	}

	// ---------------------------------------------------------------- expressions

	interface Expr { double eval(double[] vars); }

	/**
	 * Parse a math expression over the given variable names. Supports + - * / % ^, comparisons
	 * (< <= > >= == !=, 1 for true), && || !, parentheses, pi, e and common functions.
	 */
	static Expr parse(String src, List<String> varNames) {
		return new Parser(src, varNames).parseAll();
	}

	private static final class Parser {
		private final String s;
		private final Map<String, Integer> vars = new HashMap<>();
		private int i;

		Parser(String s, List<String> names) {
			this.s = s;
			for (int k = 0; k < names.size(); k++) vars.put(names.get(k), k);
		}

		Expr parseAll() {
			Expr e = or();
			skip();
			if (i < s.length()) throw error("Unexpected '" + s.charAt(i) + "'");
			return e;
		}

		private IllegalArgumentException error(String msg) {
			return new IllegalArgumentException(msg + " at position " + i + " in: " + s);
		}

		private void skip() {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
		}

		private boolean eat(String tok) {
			skip();
			if (s.startsWith(tok, i)) {
				// Don't read "<=" as "<" followed by "=", or "=" alone as "==".
				if ((tok.equals("<") || tok.equals(">") || tok.equals("!")) && s.startsWith("=", i + 1)) return false;
				i += tok.length();
				return true;
			}
			return false;
		}

		private static double bool(boolean b) { return b ? 1 : 0; }

		private Expr or() {
			Expr l = and();
			while (eat("||")) { Expr a = l, b = and(); l = v -> bool(a.eval(v) != 0 || b.eval(v) != 0); }
			return l;
		}

		private Expr and() {
			Expr l = cmp();
			while (eat("&&")) { Expr a = l, b = cmp(); l = v -> bool(a.eval(v) != 0 && b.eval(v) != 0); }
			return l;
		}

		private Expr cmp() {
			Expr l = add();
			while (true) {
				Expr a = l;
				if (eat("<=")) { Expr b = add(); l = v -> bool(a.eval(v) <= b.eval(v)); }
				else if (eat(">=")) { Expr b = add(); l = v -> bool(a.eval(v) >= b.eval(v)); }
				else if (eat("==")) { Expr b = add(); l = v -> bool(a.eval(v) == b.eval(v)); }
				else if (eat("!=")) { Expr b = add(); l = v -> bool(a.eval(v) != b.eval(v)); }
				else if (eat("<")) { Expr b = add(); l = v -> bool(a.eval(v) < b.eval(v)); }
				else if (eat(">")) { Expr b = add(); l = v -> bool(a.eval(v) > b.eval(v)); }
				else return l;
			}
		}

		private Expr add() {
			Expr l = mul();
			while (true) {
				Expr a = l;
				if (eat("+")) { Expr b = mul(); l = v -> a.eval(v) + b.eval(v); }
				else if (eat("-")) { Expr b = mul(); l = v -> a.eval(v) - b.eval(v); }
				else return l;
			}
		}

		private Expr mul() {
			Expr l = unary();
			while (true) {
				Expr a = l;
				if (eat("*")) { Expr b = unary(); l = v -> a.eval(v) * b.eval(v); }
				else if (eat("/")) { Expr b = unary(); l = v -> a.eval(v) / b.eval(v); }
				else if (eat("%")) { Expr b = unary(); l = v -> a.eval(v) % b.eval(v); }
				else return l;
			}
		}

		private Expr unary() {
			if (eat("-")) { Expr a = unary(); return v -> -a.eval(v); }
			if (eat("+")) return unary();
			if (eat("!")) { Expr a = unary(); return v -> bool(a.eval(v) == 0); }
			return pow();
		}

		private Expr pow() {
			Expr base = primary();
			if (eat("^")) { Expr exp = unary(); return v -> Math.pow(base.eval(v), exp.eval(v)); } // right-assoc
			return base;
		}

		private Expr primary() {
			skip();
			if (i >= s.length()) throw error("Unexpected end");
			char c = s.charAt(i);
			if (eat("(")) {
				Expr e = or();
				if (!eat(")")) throw error("Expected ')'");
				return e;
			}
			if (Character.isDigit(c) || c == '.') {
				int start = i;
				while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
				double num = Double.parseDouble(s.substring(start, i));
				return v -> num;
			}
			if (Character.isLetter(c) || c == '_') {
				int start = i;
				while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
				String name = s.substring(start, i);
				if (eat("(")) {
					List<Expr> args = new ArrayList<>();
					if (!eat(")")) {
						do args.add(or()); while (eat(","));
						if (!eat(")")) throw error("Expected ')'");
					}
					return call(name, args);
				}
				Integer idx = vars.get(name);
				if (idx != null) { int k = idx; return v -> v[k]; }
				if (name.equals("pi")) return v -> Math.PI;
				if (name.equals("e")) return v -> Math.E;
				throw error("Unknown variable '" + name + "' (have " + String.join(", ", vars.keySet()) + ", pi, e)");
			}
			throw error("Unexpected '" + c + "'");
		}

		private Expr call(String name, List<Expr> a) {
			int n = a.size();
			Expr f = switch (name + "/" + n) {
				case "abs/1" -> v -> Math.abs(a.get(0).eval(v));
				case "sqrt/1" -> v -> Math.sqrt(a.get(0).eval(v));
				case "sin/1" -> v -> Math.sin(a.get(0).eval(v));
				case "cos/1" -> v -> Math.cos(a.get(0).eval(v));
				case "tan/1" -> v -> Math.tan(a.get(0).eval(v));
				case "asin/1" -> v -> Math.asin(a.get(0).eval(v));
				case "acos/1" -> v -> Math.acos(a.get(0).eval(v));
				case "atan/1" -> v -> Math.atan(a.get(0).eval(v));
				case "atan2/2" -> v -> Math.atan2(a.get(0).eval(v), a.get(1).eval(v));
				case "floor/1" -> v -> Math.floor(a.get(0).eval(v));
				case "ceil/1" -> v -> Math.ceil(a.get(0).eval(v));
				case "round/1" -> v -> Math.round(a.get(0).eval(v));
				case "sign/1" -> v -> Math.signum(a.get(0).eval(v));
				case "exp/1" -> v -> Math.exp(a.get(0).eval(v));
				case "log/1" -> v -> Math.log(a.get(0).eval(v));
				case "pow/2" -> v -> Math.pow(a.get(0).eval(v), a.get(1).eval(v));
				case "hypot/2" -> v -> Math.hypot(a.get(0).eval(v), a.get(1).eval(v));
				case "min/2" -> v -> Math.min(a.get(0).eval(v), a.get(1).eval(v));
				case "max/2" -> v -> Math.max(a.get(0).eval(v), a.get(1).eval(v));
				default -> null;
			};
			if (f == null) throw error("Unknown function " + name + " with " + n + " argument(s)");
			return f;
		}
	}

	// ---------------------------------------------------------------- self-check

	public static void main(String[] args) {
		List<String> xyz = List.of("x", "y", "z");
		check(parse("1 + 2 * 3", xyz).eval(new double[3]) == 7, "precedence");
		check(parse("-2^2", xyz).eval(new double[3]) == -4, "unary minus binds looser than ^");
		check(parse("2^3^2", xyz).eval(new double[3]) == 512, "^ is right-associative");
		check(parse("x*x + y*y <= 1 && !(z > 0)", xyz).eval(new double[]{0.5, 0.5, -1}) == 1, "logic");
		check(parse("max(abs(x), hypot(3, 4))", xyz).eval(new double[]{-7, 0, 0}) == 7, "functions");
		check(parse("x != 1", xyz).eval(new double[]{1, 0, 0}) == 0, "!=");

		check(sphere(0, 0, 0, 0, 0, 0, false).voxels(false).size() == 1, "radius-0 sphere is one block");
		check(sphere(0, 0, 0, 1, 1, 1, false).voxels(false).size() == 19, "radius-1 sphere is a 3x3x3 minus corners");
		int solid = sphere(0, 0, 0, 5, 5, 5, false).voxels(false).size();
		int shell = sphere(0, 0, 0, 5, 5, 5, false).voxels(true).size();
		check(solid > shell && shell > 0, "hollow sphere is smaller");
		check(sphere(0, 64, 0, 4, 4, 4, true).voxels(false).stream().allMatch(v -> v[1] >= 64), "dome stays above center");

		// A hollow cylinder is an open tube: every layer is the same ring, no caps.
		List<int[]> tube = cylinder(0, 0, 0, 3, 5, 'y').voxels(true);
		long bottom = tube.stream().filter(v -> v[1] == 0).count(), mid = tube.stream().filter(v -> v[1] == 2).count();
		check(bottom == mid && tube.stream().allMatch(v -> v[1] >= 0 && v[1] <= 4), "open tube, 5 tall");
		check(cylinder(0, 0, 0, 0, -3, 'x').voxels(false).stream().allMatch(v -> v[0] <= 0 && v[0] >= -2), "negative length flips");

		List<int[]> pyr = pyramid(0, 0, 0, 2, 3).voxels(false);
		check(pyr.size() == 25 + 9 + 1, "stepped pyramid 5x5, 3x3, 1x1");
		check(cone(0, 0, 0, 3, 4).voxels(false).stream().filter(v -> v[1] == 3).count() == 1, "cone tip is one block");
		check(torus(0, 0, 0, 4, 1, 'y').voxels(false).stream().noneMatch(v -> v[0] == 0 && v[2] == 0), "torus has a hole");

		check(line(List.of(new int[]{0, 0, 0}, new int[]{10, 0, 0}), 0).size() == 11, "straight line");
		List<int[]> diag = line(List.of(new int[]{0, 0, 0}, new int[]{5, 3, 7}, new int[]{0, 10, 0}), 0);
		for (int k = 1; k < diag.size(); k++) {
			int[] p = diag.get(k - 1), q = diag.get(k);
			check(Math.abs(p[0] - q[0]) <= 1 && Math.abs(p[1] - q[1]) <= 1 && Math.abs(p[2] - q[2]) <= 1, "polyline is connected");
		}
		System.out.println("Shapes self-check passed.");
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
	}
}
