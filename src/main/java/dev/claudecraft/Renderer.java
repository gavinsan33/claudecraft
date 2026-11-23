package dev.claudecraft;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Draws a block snapshot as flat-shaded cubes, so Claude can see a build's shape and colors. Pure Java2D,
 * no Minecraft types: run {@code java src/main/java/dev/claudecraft/Renderer.java out.png} to self-check
 * and render a test scene.
 */
final class Renderer {
	private Renderer() {}

	/** Target size of the longer image side, in pixels. */
	private static final int TARGET = 1000;
	private static final int PAD = 12;
	private static final Color BACKGROUND = new Color(0xE9EEF3);

	/**
	 * A box of block colors, colors[(y * sz + z) * sx + x] as 0xRRGGBB, with 0 meaning nothing is drawn there.
	 */
	record Snapshot(int[] colors, int sx, int sy, int sz) {
		int at(int x, int y, int z) {
			if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return 0;
			return colors[(y * sz + z) * sx + x];
		}
	}

	/** The four isometric camera positions, named for where the camera stands. */
	enum View {
		SOUTHEAST("view from the southeast, looking northwest: east (+x) runs toward the lower right, south (+z) toward the lower left"),
		SOUTHWEST("view from the southwest, looking northeast: south (+z) runs toward the lower right, east (+x) toward the upper right"),
		NORTHWEST("view from the northwest, looking southeast: east (+x) runs toward the upper left, south (+z) toward the upper right"),
		NORTHEAST("view from the northeast, looking southwest: east (+x) runs toward the lower left, south (+z) toward the upper left");

		final String caption;

		View(String caption) {
			this.caption = caption;
		}
	}

	/**
	 * Isometric picture, orthographic along the (1,1,1) diagonal of the rotated box. Rotation (never mirroring)
	 * puts the camera at the box's +u +y +w corner, where u and w are the rotated horizontal axes.
	 */
	static BufferedImage isometric(Snapshot s, View view) {
		boolean swap = view == View.SOUTHWEST || view == View.NORTHEAST;
		int su = swap ? s.sz() : s.sx(), sw = swap ? s.sx() : s.sz(), sy = s.sy();
		// Map rotated (u, w) back to the snapshot's (x, z).
		Rot rot = switch (view) {
			case SOUTHEAST -> (u, w) -> new int[]{u, w};
			case SOUTHWEST -> (u, w) -> new int[]{s.sx() - 1 - w, u};
			case NORTHWEST -> (u, w) -> new int[]{s.sx() - 1 - u, s.sz() - 1 - w};
			case NORTHEAST -> (u, w) -> new int[]{w, s.sz() - 1 - u};
		};
		// Cube sprite: diamond half-width a = 2k, half-height b = k, vertical edge h = 2k. With h = 2b the
		// projection is exactly along (1,1,1), so sorting by u+y+w is a correct back-to-front order.
		int k = Math.max(1, Math.min(8, TARGET / Math.max(1, Math.max((su + sw) * 2, (su + sw) + 2 * sy))));
		int a = 2 * k, b = k, h = 2 * k;
		int width = (su + sw) * a + 2 * PAD, height = (su + sw) * b + sy * h + 2 * PAD;
		int offX = sw * a + PAD, offY = sy * h + PAD;

		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(BACKGROUND);
		g.fillRect(0, 0, width, height);
		boolean outline = k >= 3;
		int[] px = new int[4], py = new int[4];
		for (int sum = 0; sum <= su + sy + sw - 3; sum++) {
			for (int u = Math.max(0, sum - sy - sw + 2); u < su && u <= sum; u++) {
				for (int w = Math.max(0, sum - u - sy + 1); w < sw && u + w <= sum; w++) {
					int y = sum - u - w;
					int c = get(s, rot, u, y, w, su, sw);
					if (c == 0) continue;
					// Only the three faces that point at the camera, and only where nothing covers them.
					if (get(s, rot, u, y + 1, w, su, sw) == 0) {
						face(g, px, py, offX, offY, a, b, h, new int[][]{{u, y + 1, w}, {u + 1, y + 1, w}, {u + 1, y + 1, w + 1}, {u, y + 1, w + 1}}, c, 1.0, outline);
					}
					if (get(s, rot, u + 1, y, w, su, sw) == 0) {
						face(g, px, py, offX, offY, a, b, h, new int[][]{{u + 1, y, w}, {u + 1, y + 1, w}, {u + 1, y + 1, w + 1}, {u + 1, y, w + 1}}, c, 0.78, outline);
					}
					if (get(s, rot, u, y, w + 1, su, sw) == 0) {
						face(g, px, py, offX, offY, a, b, h, new int[][]{{u, y, w + 1}, {u + 1, y, w + 1}, {u + 1, y + 1, w + 1}, {u, y + 1, w + 1}}, c, 0.6, outline);
					}
				}
			}
		}
		g.dispose();
		return img;
	}

	private interface Rot { int[] xz(int u, int w); }

	private static int get(Snapshot s, Rot rot, int u, int y, int w, int su, int sw) {
		if (u < 0 || w < 0 || u >= su || w >= sw) return 0;
		int[] xz = rot.xz(u, w);
		return s.at(xz[0], y, xz[1]);
	}

	private static void face(Graphics2D g, int[] px, int[] py, int offX, int offY, int a, int b, int h,
			int[][] corners, int rgb, double shade, boolean outline) {
		for (int i = 0; i < 4; i++) {
			px[i] = offX + (corners[i][0] - corners[i][2]) * a;
			py[i] = offY + (corners[i][0] + corners[i][2]) * b - corners[i][1] * h;
		}
		g.setColor(shade(rgb, shade));
		g.fillPolygon(px, py, 4);
		if (outline) {
			g.setColor(shade(rgb, shade * 0.82));
			g.drawPolygon(px, py, 4);
		}
	}

	private static Color shade(int rgb, double f) {
		int r = (int) Math.min(255, ((rgb >> 16) & 255) * f);
		int gr = (int) Math.min(255, ((rgb >> 8) & 255) * f);
		int bl = (int) Math.min(255, (rgb & 255) * f);
		return new Color(r, gr, bl);
	}

	/** Plan view: north is up, east is right; each column shows its highest block, brighter when higher. */
	static BufferedImage top(Snapshot s) {
		int c = Math.max(1, Math.min(16, TARGET / Math.max(s.sx(), s.sz())));
		int width = s.sx() * c + 2 * PAD, height = s.sz() * c + 2 * PAD;
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(BACKGROUND);
		g.fillRect(0, 0, width, height);
		for (int z = 0; z < s.sz(); z++) for (int x = 0; x < s.sx(); x++) {
			for (int y = s.sy() - 1; y >= 0; y--) {
				int rgb = s.at(x, y, z);
				if (rgb == 0) continue;
				g.setColor(shade(rgb, 0.6 + 0.4 * (s.sy() == 1 ? 1 : (double) y / (s.sy() - 1))));
				g.fillRect(PAD + x * c, PAD + z * c, c, c);
				if (c >= 6) {
					g.setColor(shade(rgb, 0.5));
					g.drawRect(PAD + x * c, PAD + z * c, c - 1, c - 1);
				}
				break;
			}
		}
		g.dispose();
		return img;
	}

	static byte[] png(BufferedImage img) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(img, "png", out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ---------------------------------------------------------------- self-check

	public static void main(String[] args) throws IOException {
		// Test scene: grass floor, a stone tower in the northwest corner, a red block east of it.
		int sx = 12, sy = 10, sz = 8;
		int[] col = new int[sx * sy * sz];
		Snapshot s = new Snapshot(col, sx, sy, sz);
		for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) col[z * sx + x] = 0x7FB238;
		for (int y = 1; y < sy; y++) for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) col[(y * sz + z) * sx + x] = 0x707070;
		col[(sz + 1) * sx + 8] = 0xFF0000;

		for (View v : View.values()) {
			BufferedImage img = isometric(s, v);
			check(img.getWidth() <= TARGET + 2 * PAD + 64 && img.getHeight() <= TARGET + 2 * PAD + 64, v + " fits the target size");
			check(countColor(img, BACKGROUND.getRGB()) < img.getWidth() * img.getHeight(), v + " draws something");
			check(countRed(img) > 0, v + " shows the red block");
		}
		BufferedImage plan = top(s);
		int cell = (plan.getWidth() - 2 * PAD) / sx;
		check(plan.getRGB(PAD + cell / 2, PAD + cell / 2) == shade(0x707070, 1.0).getRGB(), "top view: tower top in the northwest corner, full brightness");
		check((plan.getRGB(PAD + 8 * cell + cell / 2, PAD + cell + cell / 2) & 0xFF0000) > 0x800000, "top view: red block east of the tower");
		check(png(plan).length > 100, "png encodes");

		if (args.length > 0) ImageIO.write(isometric(s, View.SOUTHEAST), "png", new java.io.File(args[0]));
		System.out.println("Renderer self-check passed.");
	}

	private static int countColor(BufferedImage img, int rgb) {
		int n = 0;
		for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) if (img.getRGB(x, y) == rgb) n++;
		return n;
	}

	private static int countRed(BufferedImage img) {
		int n = 0;
		for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
			int p = img.getRGB(x, y);
			if (((p >> 16) & 255) > 150 && ((p >> 8) & 255) < 60 && (p & 255) < 60) n++;
		}
		return n;
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
	}
}
