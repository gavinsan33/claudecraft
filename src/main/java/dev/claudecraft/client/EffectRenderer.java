package dev.claudecraft.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.claudecraft.CustomItems;
import dev.claudecraft.Effects;
import dev.claudecraft.VoxelModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Draws effects from their definitions, every frame: glowing beams (straight, crackling lightning, waves, spirals),
 * orbs with a halo and a spinning ring, expanding shockwave rings and sparks. Glows are layered see-through quads
 * blended additively, the way vanilla draws lightning, so overlapping light adds up; orb bodies are solid, so a
 * black hole can be truly black. Everything faces the camera, so beams look round from any angle.
 */
public class EffectRenderer extends EntityRenderer<Effects.EffectEntity, EffectRenderer.State> {
	public static class State extends EntityRenderState {
		String effect = "";
		final Vector3f to = new Vector3f();
		int seed;
		float age;
	}

	/**
	 * Additive light that doesn't write depth, so glows never hide other glows behind them, drawn both sides. Like
	 * vanilla's lightning render type otherwise.
	 */
	private static final com.mojang.renderpearl.api.pipeline.RenderPipeline GLOW_PIPELINE = net.minecraft.client.renderer.RenderPipelines.register(
		com.mojang.renderpearl.api.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.LIGHTNING_SNIPPET)
			.withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("claudecraft", "pipeline/effect_glow"))
			.withColorTargetState(new com.mojang.renderpearl.api.pipeline.ColorTargetState(com.mojang.renderpearl.api.pipeline.BlendFunction.LIGHTNING))
			.withDepthStencilState(new com.mojang.renderpearl.api.pipeline.DepthStencilState(com.mojang.renderpearl.api.pipeline.CompareOp.GREATER_THAN_OR_EQUAL, false))
			.withCull(false)
			.build());
	private static final net.minecraft.client.renderer.rendertype.RenderType GLOW = net.minecraft.client.renderer.rendertype.RenderType.create("claudecraft_glow",
		net.minecraft.client.renderer.rendertype.RenderSetup.builder(GLOW_PIPELINE).sortOnUpload().createRenderSetup());

	public EffectRenderer(EntityRendererProvider.Context ctx) {
		super(ctx);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(Effects.EffectEntity e, State s, float pt) {
		super.extractRenderState(e, s, pt);
		s.effect = e.effectId();
		s.to.set(e.to());
		s.seed = e.seed();
		s.age = e.tickCount + pt;
	}

	@Override
	protected boolean affectedByCulling(Effects.EffectEntity e) {
		return false; // a beam reaches far past the entity's little box
	}

	@Override
	public void submit(State s, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		JsonObject def = CustomItems.DEFS.get(s.effect);
		if (!Effects.isEffect(def)) return;
		Vector3f cam = new Vector3f((float) (camera.pos.x - s.x), (float) (camera.pos.y - s.y), (float) (camera.pos.z - s.z));
		draw(def, s.to, s.age, s.seed, false, cam, pose, collector);
	}

	// ------------------------------------------------------------ drawing

	/** Quads to draw: four corners of x, y, z each, and a color per corner. */
	static final class Quads {
		final List<float[]> pos = new ArrayList<>();
		final List<int[]> color = new ArrayList<>();

		void add(Vector3fc a, Vector3fc b, Vector3fc c, Vector3fc d, int ca, int cb, int cc, int cd) {
			pos.add(new float[]{a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), c.x(), c.y(), c.z(), d.x(), d.y(), d.z()});
			color.add(new int[]{ca, cb, cc, cd});
		}

		void add(Vector3fc a, Vector3fc b, Vector3fc c, Vector3fc d, int argb) {
			add(a, b, c, d, argb, argb, argb, argb);
		}
	}

	/**
	 * Draw an effect whose start is the pose's origin. to: its end point relative to the start. cam: the camera
	 * relative to the start. loop: keep it going (a projectile's trail) instead of playing once.
	 */
	public static void draw(JsonObject def, Vector3fc to, float age, int seed, boolean loop, Vector3fc cam, PoseStack pose, SubmitNodeCollector collector) {
		float life = durationTicks(def);
		Quads glow = new Quads(), solid = new Quads();
		int i = 0;
		for (JsonElement e : def.getAsJsonArray("parts")) {
			i++;
			if (!e.isJsonObject()) continue;
			JsonObject p = e.getAsJsonObject();
			float start = num(p, "start_seconds", 0) * 20, end = p.has("end_seconds") ? num(p, "end_seconds", 0) * 20 : life;
			float span = Math.max(1, end - start);
			float local = loop ? (age - start) % span : age - start;
			if (local < 0 || (!loop && local > span)) continue;
			float t = local / span;
			// Fade in over two ticks; fade out over the last part of its time unless it's told not to.
			float alpha = loop ? 1 : Math.min(1, local / 2f);
			float fadeFrom = 1 - num(p, "fade", 0.3f);
			if (!loop && t > fadeFrom && fadeFrom < 1) alpha *= (1 - t) / (1 - fadeFrom);
			Random rng = new Random(seed * 31L + i * 7919L);
			switch (p.has("type") ? p.get("type").getAsString() : "") {
				case "beam" -> beam(p, to, local, t, alpha, rng, seed, cam, glow);
				case "orb" -> orb(p, anchor(p, to), local, t, alpha, loop, cam, glow, solid);
				case "ring" -> ring(p, anchor(p, to), to, t, alpha, cam, glow);
				case "sparks" -> sparks(p, to, local, t, alpha, rng, cam, glow);
				default -> {}
			}
		}
		if (!solid.pos.isEmpty()) {
			int light = LightCoordsUtil.FULL_BRIGHT;
			collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(ClaudeCraftClient.WHITE), (pp, vc) -> emit(pp, vc, solid, true, light));
		}
		if (!glow.pos.isEmpty()) collector.submitCustomGeometry(pose, GLOW, (pp, vc) -> emit(pp, vc, glow, false, 0));
	}

	private static final float[] U = {0, 1, 1, 0}, V = {1, 1, 0, 0};

	/**
	 * Solid quads go out in both windings, so they show from either side under the entity render type's culling;
	 * glow doesn't cull, so once (twice would double its brightness).
	 */
	private static void emit(PoseStack.Pose pp, VertexConsumer vc, Quads q, boolean entity, int light) {
		for (int n = 0; n < q.pos.size(); n++) {
			float[] c = q.pos.get(n);
			int[] col = q.color.get(n);
			for (int pass = 0; pass < (entity ? 2 : 1); pass++) {
				for (int k = 0; k < 4; k++) {
					int j = pass == 0 ? k : 3 - k;
					var v = vc.addVertex(pp, c[j * 3], c[j * 3 + 1], c[j * 3 + 2]).setColor(col[j]);
					if (entity) v.setUv(U[k], V[k]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pp, 0, 1, 0);
				}
			}
		}
	}

	static int durationTicks(JsonObject def) {
		double s = def.has("duration_seconds") ? def.get("duration_seconds").getAsDouble() : 1;
		return (int) Math.max(1, Math.min(600 * 20, Math.round(s * 20)));
	}

	private static float num(JsonObject p, String k, float d) {
		return p.has(k) ? p.get(k).getAsFloat() : d;
	}

	private static int color(JsonObject p, String k, int d) {
		if (!p.has(k)) return d;
		try {
			int c = VoxelModel.color(p.get(k).getAsString());
			return p.get(k).getAsString().length() <= 7 ? c | 0xFF000000 : c;
		} catch (RuntimeException e) {
			return d;
		}
	}

	/** The color with its alpha scaled. */
	private static int fade(int argb, float a) {
		int alpha = Math.round(((argb >>> 24) & 0xFF) * Mth.clamp(a, 0, 1));
		return alpha << 24 | (argb & 0xFFFFFF);
	}

	private static Vector3f anchor(JsonObject p, Vector3fc to) {
		String at = p.has("at") ? p.get("at").getAsString() : "start";
		return at.equals("end") ? new Vector3f(to) : at.equals("along") ? new Vector3f(to).mul(0.5f) : new Vector3f();
	}

	/** Two directions at right angles to d (and each other). */
	private static Vector3f[] perpendiculars(Vector3fc d) {
		Vector3f n = new Vector3f(d).normalize();
		Vector3f helper = Math.abs(n.y) < 0.9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
		Vector3f u = new Vector3f(n).cross(helper).normalize();
		Vector3f v = new Vector3f(n).cross(u).normalize();
		return new Vector3f[]{u, v};
	}

	/** A flat strip from a to b, turned to face the camera, half-width w. soft: fades from its middle to clear edges, like light. */
	private static void strip(Vector3fc a, Vector3fc b, float w, int ca, int cb, boolean soft, Vector3fc cam, Quads out) {
		Vector3f dir = new Vector3f(b).sub(a);
		if (dir.lengthSquared() < 1e-8f) return;
		Vector3f mid = new Vector3f(a).add(b).mul(0.5f);
		Vector3f side = new Vector3f(dir).cross(new Vector3f(cam).sub(mid));
		if (side.lengthSquared() < 1e-8f) side = perpendiculars(dir)[0];
		side.normalize().mul(w);
		if (!soft) {
			out.add(new Vector3f(a).add(side), new Vector3f(a).sub(side), new Vector3f(b).sub(side), new Vector3f(b).add(side), ca, ca, cb, cb);
			return;
		}
		int clearA = ca & 0xFFFFFF, clearB = cb & 0xFFFFFF;
		out.add(new Vector3f(a).add(side), new Vector3f(a), new Vector3f(b), new Vector3f(b).add(side), clearA, ca, cb, clearB);
		out.add(new Vector3f(a), new Vector3f(a).sub(side), new Vector3f(b).sub(side), new Vector3f(b), ca, clearA, clearB, cb);
	}

	/** A glowing line along points: a wide faint glow, a narrower brighter one, and a bright core. */
	private static void glowLine(List<Vector3f> pts, float width, float glowSize, int color, int core, float alpha, Vector3fc cam, Quads out) {
		for (int k = 0; k + 1 < pts.size(); k++) {
			Vector3f a = pts.get(k), b = pts.get(k + 1);
			strip(a, b, width * glowSize, fade(color, alpha * 0.45f), fade(color, alpha * 0.45f), true, cam, out);
			strip(a, b, width * glowSize * 0.4f, fade(color, alpha * 0.8f), fade(color, alpha * 0.8f), true, cam, out);
			// Nudge the core toward the camera so it isn't hidden behind the glow it sits in.
			Vector3f na = new Vector3f(cam).sub(a).normalize().mul(0.01f).add(a), nb = new Vector3f(cam).sub(b).normalize().mul(0.01f).add(b);
			strip(na, nb, width * 0.5f, fade(core, alpha), fade(core, alpha), false, cam, out);
		}
	}

	private static void beam(JsonObject p, Vector3fc to, float local, float t, float alpha, Random rng, int seed, Vector3fc cam, Quads out) {
		float len = to.length();
		if (len < 1e-3f) return;
		String style = p.has("style") ? p.get("style").getAsString() : "straight";
		float width = num(p, "width", 0.12f), glowSize = num(p, "glow", 3.5f);
		int color = color(p, "color", 0xFF60C0FF), core = color(p, "core_color", 0xFFFFFFFF);
		float grow = num(p, "grow_seconds", 0) * 20;
		float reach = grow > 0 ? Math.min(1, local / grow) : 1;
		if (num(p, "pulse", 0) > 0) width *= 1 + num(p, "pulse", 0) * Mth.sin(local * 0.8f) * 0.5f;
		Vector3f dir = new Vector3f(to).normalize();
		Vector3f[] uv = perpendiculars(to);
		switch (style) {
			case "lightning" -> {
				// A fresh crackle every couple of ticks (or a still one with flicker false).
				boolean flicker = !p.has("flicker") || p.get("flicker").getAsBoolean();
				Random r = new Random(seed * 131L + (flicker ? (long) (local / 2) : 0));
				float jitter = num(p, "jitter", Math.min(1.2f, 0.12f * len + 0.3f));
				List<Vector3f> main = bolt(new Vector3f(), new Vector3f(to).mul(reach), jitter, r);
				glowLine(main, width, glowSize, color, core, alpha, cam, out);
				int branches = Math.round(num(p, "branches", 3));
				for (int b = 0; b < branches && main.size() > 3; b++) {
					Vector3f from = main.get(1 + r.nextInt(main.size() - 2));
					Vector3f off = new Vector3f(dir).mul(0.6f).add(new Vector3f(uv[0]).mul(r.nextFloat() * 2 - 1)).add(new Vector3f(uv[1]).mul(r.nextFloat() * 2 - 1));
					off.normalize().mul(len * (0.12f + r.nextFloat() * 0.25f));
					glowLine(bolt(from, new Vector3f(from).add(off), jitter * 0.6f, r), width * 0.55f, glowSize * 0.8f, color, core, alpha * 0.8f, cam, out);
				}
			}
			case "wave", "spiral" -> {
				float amp = num(p, "amplitude", 0.35f), freq = num(p, "frequency", 0.6f), speed = num(p, "speed", 1f);
				int n = Math.max(8, Math.min(400, Math.round(len * 6 * reach)));
				List<Vector3f> pts = new ArrayList<>();
				for (int k = 0; k <= n; k++) {
					float f = (float) k / n * reach, dist = f * len;
					float phase = Mth.TWO_PI * (freq * dist - local / 20f * speed);
					float taper = Math.min(1, Math.min(dist, (len - dist)) * 2); // pinned at both ends
					Vector3f pt = new Vector3f(to).mul(f).add(new Vector3f(uv[0]).mul(amp * taper * Mth.sin(phase)));
					if (style.equals("spiral")) pt.add(new Vector3f(uv[1]).mul(amp * taper * Mth.cos(phase)));
					pts.add(pt);
				}
				glowLine(pts, width, glowSize, color, core, alpha, cam, out);
			}
			default -> glowLine(List.of(new Vector3f(), new Vector3f(to).mul(reach)), width, glowSize, color, core, alpha, cam, out);
		}
	}

	/** A jagged line from a to b: a random walk sideways that's pinned at both ends. */
	private static List<Vector3f> bolt(Vector3f a, Vector3f b, float jitter, Random r) {
		Vector3f d = new Vector3f(b).sub(a);
		float len = d.length();
		int n = Math.max(3, Math.min(48, Math.round(len * 1.5f)));
		Vector3f[] uv = perpendiculars(d);
		List<Vector3f> pts = new ArrayList<>();
		float ox = 0, oy = 0;
		for (int k = 0; k <= n; k++) {
			float f = (float) k / n;
			ox = ox * 0.6f + (r.nextFloat() * 2 - 1) * jitter;
			oy = oy * 0.6f + (r.nextFloat() * 2 - 1) * jitter;
			float pin = Mth.sin(f * Mth.PI);
			pts.add(new Vector3f(d).mul(f).add(a).add(new Vector3f(uv[0]).mul(ox * pin)).add(new Vector3f(uv[1]).mul(oy * pin)));
		}
		return pts;
	}

	private static void orb(JsonObject p, Vector3f c, float local, float t, float alpha, boolean loop, Vector3fc cam, Quads glow, Quads solid) {
		float radius = num(p, "radius", 0.5f);
		// Swell in over a few ticks, breathe with pulse, and shrink away at the end.
		float grow = Math.min(1, local / Math.max(1, num(p, "grow_seconds", 0.15f) * 20));
		float pulse = 1 + num(p, "pulse", 0) * Mth.sin(local * 0.35f) * 0.25f;
		float r = radius * grow * pulse * (loop ? 1 : Math.max(0.05f, Math.min(1, alpha * 1.5f)));
		int color = color(p, "color", 0xFF80D0FF);
		int glowColor = color(p, "glow_color", color);
		float glowSize = num(p, "glow", 2.2f);
		if (!p.has("solid") || p.get("solid").getAsBoolean()) sphere(c, r, fade(color, Math.min(1, alpha * 2)), solid);
		if (glowSize > 0) disc(c, r * 0.9f, r * glowSize, fade(glowColor, alpha * 0.8f), cam, glow);
		float ringR = num(p, "ring", 0);
		if (ringR > 0) {
			int ringColor = color(p, "ring_color", glowColor);
			float tilt = num(p, "tilt", 20) * Mth.DEG_TO_RAD, spin = local / 20f * num(p, "spin", 0.5f) * Mth.TWO_PI;
			annulus(c, r * 1.1f, r * ringR, fade(ringColor, alpha), 0, tilt, spin, glow);
		}
	}

	/** A camera-facing disc of light fading out from inner to outer radius. */
	private static void disc(Vector3f c, float inner, float outer, int argb, Vector3fc cam, Quads out) {
		Vector3f toCam = new Vector3f(cam).sub(c);
		if (toCam.lengthSquared() < 1e-6f) return;
		Vector3f[] uv = perpendiculars(toCam);
		// Thin wedges in rings of falling brightness: a soft glow, without the spokes that long wedges of one fade show.
		int segs = 64;
		float[] radii = {inner, inner + (outer - inner) * 0.2f, inner + (outer - inner) * 0.5f, outer};
		int[] cols = {argb, fade(argb, 0.5f), fade(argb, 0.15f), argb & 0xFFFFFF};
		for (int k = 0; k < segs; k++) {
			float a0 = Mth.TWO_PI * k / segs, a1 = Mth.TWO_PI * (k + 1) / segs;
			Vector3f d0 = new Vector3f(uv[0]).mul(Mth.cos(a0)).add(new Vector3f(uv[1]).mul(Mth.sin(a0)));
			Vector3f d1 = new Vector3f(uv[0]).mul(Mth.cos(a1)).add(new Vector3f(uv[1]).mul(Mth.sin(a1)));
			for (int b = 0; b < 3; b++) {
				out.add(new Vector3f(d0).mul(radii[b]).add(c), new Vector3f(d1).mul(radii[b]).add(c), new Vector3f(d1).mul(radii[b + 1]).add(c),
					new Vector3f(d0).mul(radii[b + 1]).add(c), cols[b], cols[b], cols[b + 1], cols[b + 1]);
			}
		}
	}

	/**
	 * A flat ring around c, tilted and spinning: brightest a little way out from its inner edge and fading to nothing
	 * at the outer one, with three softly brighter arcs that sweep around as it spins.
	 */
	private static void annulus(Vector3f c, float inner, float outer, int argb, float yaw, float tilt, float spin, Quads out) {
		int segs = 96;
		float[] radii = {inner, inner + (outer - inner) * 0.3f, outer};
		float[] bright = {0.45f, 1f, 0f};
		for (int k = 0; k < segs; k++) {
			float a0 = Mth.TWO_PI * k / segs, a1 = Mth.TWO_PI * (k + 1) / segs;
			float m0 = 0.7f + 0.3f * Mth.sin(3 * (a0 - spin)), m1 = 0.7f + 0.3f * Mth.sin(3 * (a1 - spin));
			for (int b = 0; b < 2; b++) {
				out.add(ringPoint(c, radii[b], a0, tilt, yaw), ringPoint(c, radii[b], a1, tilt, yaw), ringPoint(c, radii[b + 1], a1, tilt, yaw),
					ringPoint(c, radii[b + 1], a0, tilt, yaw),
					fade(argb, bright[b] * m0), fade(argb, bright[b] * m1), fade(argb, bright[b + 1] * m1), fade(argb, bright[b + 1] * m0));
			}
		}
	}

	private static Vector3f ringPoint(Vector3f c, float r, float angle, float tilt, float yaw) {
		Vector3f v = new Vector3f(Mth.cos(angle) * r, 0, Mth.sin(angle) * r);
		v.rotateX(tilt).rotateY(yaw);
		return v.add(c);
	}

	/** A solid sphere for orb bodies. */
	private static void sphere(Vector3f c, float r, int argb, Quads out) {
		int lat = 12, lon = 18;
		for (int i = 0; i < lat; i++) {
			float t0 = Mth.PI * i / lat - Mth.HALF_PI, t1 = Mth.PI * (i + 1) / lat - Mth.HALF_PI;
			for (int j = 0; j < lon; j++) {
				float p0 = Mth.TWO_PI * j / lon, p1 = Mth.TWO_PI * (j + 1) / lon;
				out.add(spherePoint(c, r, t0, p0), spherePoint(c, r, t0, p1), spherePoint(c, r, t1, p1), spherePoint(c, r, t1, p0), argb);
			}
		}
	}

	private static Vector3f spherePoint(Vector3f c, float r, float lat, float lon) {
		return new Vector3f(Mth.cos(lat) * Mth.cos(lon) * r, Mth.sin(lat) * r, Mth.cos(lat) * Mth.sin(lon) * r).add(c);
	}

	/** An expanding shockwave: a ring growing from one radius to another and fading as it goes. */
	private static void ring(JsonObject p, Vector3f c, Vector3fc to, float t, float alpha, Vector3fc cam, Quads out) {
		float from = num(p, "from_radius", 0.2f), until = num(p, "to_radius", 4f), width = num(p, "width", 0.4f);
		float eased = 1 - (1 - t) * (1 - t);
		float r = from + (until - from) * eased;
		int color = fade(color(p, "color", 0xFFFFFFFF), alpha * (1 - t * 0.7f));
		String axis = p.has("axis") ? p.get("axis").getAsString() : "up";
		float yaw = 0, tilt = 0;
		if (axis.equals("facing") && to.lengthSquared() > 1e-6f || axis.equals("camera")) {
			Vector3f n = axis.equals("camera") ? new Vector3f(cam).sub(c) : new Vector3f(to);
			n.normalize();
			yaw = (float) Math.atan2(n.x, n.z);
			tilt = (float) Math.acos(Mth.clamp(n.y, -1, 1));
		}
		// A band with a bright middle and soft edges.
		int segs = 48, clear = color & 0xFFFFFF;
		for (int k = 0; k < segs; k++) {
			float a0 = Mth.TWO_PI * k / segs, a1 = Mth.TWO_PI * (k + 1) / segs;
			out.add(ringPoint(c, Math.max(0, r - width), a0, tilt, yaw), ringPoint(c, Math.max(0, r - width), a1, tilt, yaw), ringPoint(c, r, a1, tilt, yaw), ringPoint(c, r, a0, tilt, yaw),
				clear, clear, color, color);
			out.add(ringPoint(c, r, a0, tilt, yaw), ringPoint(c, r, a1, tilt, yaw), ringPoint(c, r + width, a1, tilt, yaw), ringPoint(c, r + width, a0, tilt, yaw),
				color, color, clear, clear);
		}
	}

	/** Little glowing specks flying out (bursts), drifting along a beam, or swirling inward (into a black hole). */
	private static void sparks(JsonObject p, Vector3fc to, float local, float t, float alpha, Random rng, Vector3fc cam, Quads out) {
		int count = Math.max(1, Math.min(400, Math.round(num(p, "count", 24))));
		float speed = num(p, "speed", 3), size = num(p, "size", 0.08f), gravity = num(p, "gravity", 0), radius = num(p, "radius", 3);
		boolean inward = p.has("inward") && p.get("inward").getAsBoolean();
		String at = p.has("at") ? p.get("at").getAsString() : "start";
		int color = color(p, "color", 0xFFFFE080);
		float sec = local / 20f;
		for (int k = 0; k < count; k++) {
			Vector3f dir = new Vector3f((float) rng.nextGaussian(), (float) rng.nextGaussian(), (float) rng.nextGaussian());
			if (dir.lengthSquared() < 1e-6f) dir.set(0, 1, 0);
			dir.normalize();
			float s = speed * (0.5f + rng.nextFloat());
			float offset = rng.nextFloat();
			Vector3f base = at.equals("end") ? new Vector3f(to) : at.equals("along") ? new Vector3f(to).mul(rng.nextFloat()) : new Vector3f();
			Vector3f pos;
			float a = alpha;
			if (inward) {
				// Fall in from the edge, swirling, and start over: endless while the effect lasts.
				float cycle = radius / Math.max(0.1f, s);
				float f = ((sec / cycle) + offset) % 1f;
				float swirl = f * Mth.TWO_PI * 0.75f;
				Vector3f d = new Vector3f(dir).rotateY(swirl);
				pos = d.mul(radius * (1 - f) * (1 - f)).add(base);
				a *= Math.min(1, f * 4) * (1 - f * 0.3f);
			} else {
				pos = new Vector3f(dir).mul(s * sec).add(base);
				pos.y -= 0.5f * gravity * sec * sec;
				a *= 1 - t;
			}
			Vector3f toCam = new Vector3f(cam).sub(pos);
			if (toCam.lengthSquared() < 1e-6f) continue;
			Vector3f[] uv = perpendiculars(toCam);
			Vector3f r = new Vector3f(uv[0]).mul(size), u = new Vector3f(uv[1]).mul(size);
			int col = fade(color, a);
			out.add(new Vector3f(pos).sub(r).sub(u), new Vector3f(pos).add(r).sub(u), new Vector3f(pos).add(r).add(u), new Vector3f(pos).sub(r).add(u), col);
		}
	}
}
