package dev.claudecraft.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.claudecraft.CustomItems;
import dev.claudecraft.CustomParticles;
import dev.claudecraft.CustomProjectiles;
import dev.claudecraft.Effects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One particle of an effect's particles part, plus the emitting: every client tick, each playing effect (and each
 * projectile carrying one) adds the particles its parts make that tick. Client thread.
 */
final class CustomParticle extends SingleQuadParticle {
	private final CustomParticles.Spec spec;
	/** Where the part played from. It moves with anchor (an effect following someone), carrying the particle along. */
	private double ox, oy, oz;
	private final Entity anchor;
	private final Vec3 anchorOffset;
	private final double[] v, vel, d = new double[3];
	private final int born;
	private final List<TextureAtlasSprite> frames;
	private final boolean blockChip;
	private final float spinPerTick, uo, vo;
	/** Claude's own sprite, if the part has one: its layer (texture) and which frame this particle shows. */
	private final Layer own;
	private int frame;

	private CustomParticle(ClientLevel level, CustomParticles.Spec spec, Vec3 origin, Entity anchor, List<TextureAtlasSprite> frames, double[] v, double[] vel, int lifetime, int born) {
		super(level, origin.x + v[CustomParticles.X], origin.y + v[CustomParticles.Y], origin.z + v[CustomParticles.Z], frames.getFirst());
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		this.spec = spec;
		this.ox = origin.x;
		this.oy = origin.y;
		this.oz = origin.z;
		this.anchor = anchor;
		this.anchorOffset = anchor == null ? null : origin.subtract(anchor.position());
		this.v = v;
		this.vel = vel;
		this.born = born;
		this.frames = frames;
		this.blockChip = spec.texture.startsWith("block:");
		this.lifetime = lifetime;
		this.hasPhysics = spec.collide;
		this.gravity = 0;
		this.spinPerTick = spec.spinPerTick(rnd);
		this.roll = this.oRoll = spinPerTick == 0 ? 0 : rnd.nextFloat() * (float) (Math.PI * 2);
		this.uo = rnd.nextFloat() * 3;
		this.vo = rnd.nextFloat() * 3;
		this.own = spec.spriteFrames == null ? null : spriteLayer(spec);
		if (spec.randomFrame) {
			if (own != null) frame = rnd.nextInt(spec.spriteFrames.length);
			else setSprite(frames.get(rnd.nextInt(frames.size())));
		}
		appearance(0);
	}

	@Override
	public void tick() {
		xo = x;
		yo = y;
		zo = z;
		oRoll = roll;
		if (age++ >= lifetime) {
			remove();
			return;
		}
		float life = age / (float) lifetime;
		if (anchor != null) {
			Vec3 o = anchor.position().add(anchorOffset);
			double dx = o.x - ox, dy = o.y - oy, dz = o.z - oz;
			if (dx != 0 || dy != 0 || dz != 0) {
				setPos(x + dx, y + dy, z + dz);
				ox = o.x;
				oy = o.y;
				oz = o.z;
			}
		}
		v[CustomParticles.X] = x - ox;
		v[CustomParticles.Y] = y - oy;
		v[CustomParticles.Z] = z - oz;
		v[CustomParticles.T] = (born + age) / 20.0;
		v[CustomParticles.AGE] = age / 20.0;
		v[CustomParticles.LIFE] = life;
		spec.step(v, vel, d);
		move(d[0], d[1], d[2]);
		if (onGround) {
			vel[0] *= 0.7;
			vel[2] *= 0.7;
		}
		roll += spinPerTick;
		appearance(life);
	}

	private void appearance(float life) {
		int c = spec.color(life);
		setColor((c >> 16 & 0xFF) / 255f, (c >> 8 & 0xFF) / 255f, (c & 0xFF) / 255f);
		setAlpha((c >>> 24) / 255f);
		quadSize = spec.size(life) / 2;
		if (spec.randomFrame) return;
		if (own != null) frame = Math.min(spec.spriteFrames.length - 1, (int) (life * spec.spriteFrames.length));
		else if (frames.size() > 1) setSprite(frames.get(Math.min(frames.size() - 1, (int) (life * frames.size()))));
	}

	@Override
	protected Layer getLayer() {
		return own != null ? own : blockChip ? Layer.TRANSLUCENT_TERRAIN : Layer.TRANSLUCENT;
	}

	// ---------------------------------------------------------------- Claude's own sprites

	/** Uploaded sprites by spec: frames side by side in one small texture. Released when definitions change. */
	private static final java.util.Map<CustomParticles.Spec, Layer> SPRITES = new java.util.IdentityHashMap<>();
	private static int spriteCount;

	private static Layer spriteLayer(CustomParticles.Spec spec) {
		return SPRITES.computeIfAbsent(spec, s -> {
			int w = s.spriteW, h = s.spriteH, n = s.spriteFrames.length;
			var img = new com.mojang.blaze3d.platform.NativeImage(w * n, h, true);
			for (int k = 0; k < n; k++) for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setPixel(k * w + x, y, s.spriteFrames[k][y * w + x]);
			Identifier id = Identifier.fromNamespaceAndPath("claudecraft", "particle_sprite/" + spriteCount++);
			Minecraft.getInstance().getTextureManager().register(id, new net.minecraft.client.renderer.texture.DynamicTexture(() -> "claudecraft particle sprite", img));
			return new Layer(true, id, Layer.TRANSLUCENT.pipeline(), Layer.TRANSLUCENT.oitPipelineSet());
		});
	}

	/** Free the uploaded sprites (definitions changed, so their specs are rebuilt). Client thread. */
	static void releaseSprites() {
		var tm = Minecraft.getInstance().getTextureManager();
		for (Layer l : SPRITES.values()) tm.release(l.textureAtlasLocation());
		SPRITES.clear();
	}

	@Override
	protected int getLightCoords(float partialTick) {
		return spec.emissive ? LightCoordsUtil.FULL_BRIGHT : super.getLightCoords(partialTick);
	}

	// Block chips show a quarter of the block's texture, like vanilla breaking particles.
	@Override
	protected float getU0() {
		return own != null ? (float) frame / spec.spriteFrames.length : blockChip ? sprite.getU(uo / 4) : super.getU0();
	}

	@Override
	protected float getU1() {
		return own != null ? (frame + 1f) / spec.spriteFrames.length : blockChip ? sprite.getU((uo + 1) / 4) : super.getU1();
	}

	@Override
	protected float getV0() {
		return own != null ? 0f : blockChip ? sprite.getV(vo / 4) : super.getV0();
	}

	@Override
	protected float getV1() {
		return own != null ? 1f : blockChip ? sprite.getV((vo + 1) / 4) : super.getV1();
	}

	// ---------------------------------------------------------------- emitting

	/** Add this tick's particles for every playing effect and every projectile carrying one. */
	static void tick(Minecraft mc) {
		if (mc.level == null || mc.isPaused()) return;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof Effects.EffectEntity fx) {
				emit(mc, fx.effectId(), fx, fx.position(), fx.to(), fx.tickCount - 1, false);
			} else if (e instanceof CustomProjectiles.Thrown t && !t.trail().isEmpty()) {
				Vec3 back = t.getDeltaMovement().scale(-3);
				emit(mc, t.trail(), null, t.position(), new org.joml.Vector3f((float) back.x, (float) back.y, (float) back.z), t.tickCount - 1, true);
			}
		}
	}

	/**
	 * One effect's particles parts at effect time `tick`. to: its end point relative to the start. anchor: the
	 * effect's entity, whose movement (when it follows someone) carries particles along unless the part is a trail.
	 */
	private static void emit(Minecraft mc, String id, Entity anchor, Vec3 start, Vector3fc to, int tick, boolean loop) {
		JsonObject def = CustomItems.DEFS.get(id);
		if (!Effects.isEffect(def) || tick < 0) return;
		int life = Effects.durationTicks(def), i = 0;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		for (JsonElement el : def.getAsJsonArray("parts")) {
			i++;
			JsonObject p = el.getAsJsonObject();
			if (!"particles".equals(p.has("type") ? p.get("type").getAsString() : "")) continue;
			CustomParticles.Spec spec = CustomParticles.spec(id, i, p);
			if (spec == null) continue;
			int begin = (int) Math.round((p.has("start_seconds") ? p.get("start_seconds").getAsDouble() : 0) * 20);
			int end = p.has("end_seconds") ? (int) Math.round(p.get("end_seconds").getAsDouble() * 20) : life;
			int span = Math.max(1, end - begin), local = tick - begin;
			if (local < 0) continue;
			if (loop) local %= span;
			else if (local >= span) continue;
			long first = spec.emittedBefore(local), next = spec.emittedBefore(local + 1), n = spec.total(span);
			if (next <= first) continue;
			List<TextureAtlasSprite> frames = sprites(mc, spec.texture);
			String at = p.has("at") ? p.get("at").getAsString() : "start";
			for (long k = first; k < next; k++) {
				double f = at.equals("end") ? 1 : at.equals("along") ? rnd.nextDouble() : 0;
				Vec3 origin = start.add(to.x() * f, to.y() * f, to.z() * f);
				double[] v = new double[CustomParticles.VARS.size()], vel = new double[3];
				int lifetime = spec.spawn(v, vel, k, n, tick, rnd);
				// Added straight to the engine, so they aren't culled at 32 blocks like vanilla's.
				mc.particleEngine.add(new CustomParticle(mc.level, spec, origin, spec.trail ? null : anchor, frames, v, vel, lifetime, tick));
			}
		}
	}

	/** The sprite or animation frames for a texture name: name, else name_0, name_1... or name_01, name_02... */
	private static List<TextureAtlasSprite> sprites(Minecraft mc, String texture) {
		if (texture.startsWith("block:")) {
			var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(texture.substring(6)));
			return List.of(mc.getModelManager().getBlockStateModelSet().getParticleMaterial(block.defaultBlockState()).sprite());
		}
		TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES);
		Identifier id = Identifier.parse(texture);
		TextureAtlasSprite one = atlas.getSprite(id);
		if (one != atlas.missingSprite()) return List.of(one);
		List<TextureAtlasSprite> out = new ArrayList<>();
		for (String fmt : new String[]{"_%d", "_%02d"}) {
			for (int k = fmt.equals("_%d") ? 0 : 1; ; k++) {
				TextureAtlasSprite s = atlas.getSprite(id.withSuffix(String.format(fmt, k)));
				if (s == atlas.missingSprite()) break;
				out.add(s);
			}
			if (!out.isEmpty()) return out;
		}
		return List.of(atlas.missingSprite());
	}
}
