package dev.claudecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.List;
import java.util.UUID;

/**
 * Visual effects Claude designs: glowing beams (straight, lightning, waves, spirals), orbs with halos and spinning
 * rings, expanding shockwave rings and sparks, drawn by each player's game from the definition (see the client's
 * EffectRenderer). An effect plays as a short-lived entity at its start point, with an optional end point for
 * beams; it can also be a lasting field that pulls or pushes things around it and hurts them, like a black hole.
 */
public final class Effects {
	private Effects() {}

	public static EntityType<EffectEntity> TYPE;
	public static final List<String> PART_TYPES = List.of("beam", "orb", "ring", "sparks", "particles");
	public static final List<String> BEAM_STYLES = List.of("straight", "lightning", "wave", "spiral");
	public static final List<String> PLACES = List.of("start", "end", "along");
	static final int MAX_SECONDS = 600;

	static void register() {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, CustomItems.id("effect"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
			EntityType.Builder.<EffectEntity>of(EffectEntity::new, MobCategory.MISC).sized(0.25f, 0.25f).noSave()
				.clientTrackingRange(16).updateInterval(4).build(key));
	}

	public static boolean isEffect(JsonObject def) {
		return def != null && def.has("kind") && def.get("kind").getAsString().equals("effect");
	}

	public static int durationTicks(JsonObject def) {
		double s = def != null && def.has("duration_seconds") ? def.get("duration_seconds").getAsDouble() : 1;
		return (int) Math.max(1, Math.min(MAX_SECONDS * 20, Math.round(s * 20)));
	}

	/** Check an effect definition, returning a problem or null. */
	static String validate(JsonObject def) {
		if (!def.has("parts") || !def.get("parts").isJsonArray() || def.getAsJsonArray("parts").isEmpty()) return "an effect needs at least one part";
		JsonArray parts = def.getAsJsonArray("parts");
		if (parts.size() > 16) return "at most 16 parts";
		int i = 0;
		for (JsonElement e : parts) {
			i++;
			if (!e.isJsonObject()) return "part " + i + " must be an object";
			JsonObject p = e.getAsJsonObject();
			String type = p.has("type") ? p.get("type").getAsString() : "";
			if (!PART_TYPES.contains(type)) return "part " + i + ": type must be one of " + String.join(", ", PART_TYPES);
			if (p.has("style") && !BEAM_STYLES.contains(p.get("style").getAsString())) return "part " + i + ": style must be one of " + String.join(", ", BEAM_STYLES);
			if (p.has("at") && !PLACES.contains(p.get("at").getAsString())) return "part " + i + ": at must be one of " + String.join(", ", PLACES);
			for (String c : List.of("color", "core_color", "glow_color", "ring_color")) {
				if (!p.has(c)) continue;
				try {
					VoxelModel.color(p.get(c).getAsString());
				} catch (RuntimeException ex) {
					return "part " + i + ": " + c + " must be #RRGGBB or #AARRGGBB";
				}
			}
			if (type.equals("sparks") && p.has("count") && p.get("count").getAsInt() > 400) return "part " + i + ": at most 400 sparks";
			if (type.equals("particles")) {
				try {
					new CustomParticles.Spec(p);
				} catch (RuntimeException ex) {
					return "part " + i + " (particles): " + ex.getMessage();
				}
			}
		}
		if (def.has("sound") && sound(def.get("sound").getAsString()) == null) return "Unknown sound " + def.get("sound").getAsString();
		return null;
	}

	/** A dry run of each particles part, for Claude, who can't see them; empty if there are none. */
	static String particleSummary(JsonObject def) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		for (JsonElement e : def.getAsJsonArray("parts")) {
			i++;
			JsonObject p = e.getAsJsonObject();
			if (!p.get("type").getAsString().equals("particles")) continue;
			int start = (int) Math.round((p.has("start_seconds") ? p.get("start_seconds").getAsDouble() : 0) * 20);
			int end = p.has("end_seconds") ? (int) Math.round(p.get("end_seconds").getAsDouble() * 20) : durationTicks(def);
			sb.append(" Part ").append(i).append(" dry run: ")
				.append(new CustomParticles.Spec(p).summary(Math.max(1, end - start), java.util.concurrent.ThreadLocalRandom.current())).append('.');
		}
		return sb.toString();
	}

	static SoundEvent sound(String name) {
		Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
		return id == null ? null : BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
	}

	/** Play an effect from one point toward another (the same point for orbs and bursts). Server thread. */
	static EffectEntity play(ServerLevel level, JsonObject def, Vec3 from, Vec3 to, Entity owner) {
		EffectEntity e = new EffectEntity(TYPE, level);
		e.setPos(from);
		e.setEffect(def.get("id").getAsString(), to.subtract(from), level.getRandom().nextInt());
		if (owner != null) e.owner = owner.getUUID();
		level.addFreshEntity(e);
		if (def.has("sound")) {
			SoundEvent s = sound(def.get("sound").getAsString());
			float volume = def.has("sound_volume") ? def.get("sound_volume").getAsFloat() : 1f;
			if (s != null) level.playSound(null, from.x, from.y, from.z, s, SoundSource.PLAYERS, volume, 0.9f + level.getRandom().nextFloat() * 0.2f);
		}
		return e;
	}

	/** One playing effect. Doesn't save: effects are moments, and fields last at most a few minutes. */
	public static final class EffectEntity extends Entity {
		private static final EntityDataAccessor<String> EFFECT = SynchedEntityData.defineId(EffectEntity.class, EntityDataSerializers.STRING);
		private static final EntityDataAccessor<Vector3fc> TO = SynchedEntityData.defineId(EffectEntity.class, EntityDataSerializers.VECTOR3);
		private static final EntityDataAccessor<Integer> SEED = SynchedEntityData.defineId(EffectEntity.class, EntityDataSerializers.INT);
		UUID owner;

		EffectEntity(EntityType<? extends EffectEntity> type, Level level) {
			super(type, level);
			noPhysics = true;
			setNoGravity(true);
		}

		@Override
		protected void defineSynchedData(SynchedEntityData.Builder b) {
			b.define(EFFECT, "");
			b.define(TO, new Vector3f());
			b.define(SEED, 0);
		}

		void setEffect(String id, Vec3 to, int seed) {
			for (String t : List.copyOf(entityTags())) if (t.startsWith("custom.")) removeTag(t);
			entityData.set(EFFECT, id);
			entityData.set(TO, new Vector3f((float) to.x, (float) to.y, (float) to.z));
			entityData.set(SEED, seed);
			if (!id.isEmpty()) addTag(CommandRewriter.tag(id));
		}

		public String effectId() {
			return entityData.get(EFFECT);
		}

		/** The end point, relative to the start (where the entity is). */
		public Vector3fc to() {
			return entityData.get(TO);
		}

		public int seed() {
			return entityData.get(SEED);
		}

		public JsonObject definition() {
			JsonObject def = CustomItems.DEFS.get(effectId());
			return isEffect(def) ? def : null;
		}

		@Override
		public void tick() {
			super.tick();
			if (!(level() instanceof ServerLevel level)) return;
			JsonObject def = definition();
			if (def == null || tickCount >= durationTicks(def)) {
				discard();
				return;
			}
			if (def.has("field") && def.get("field").isJsonObject()) field(level, def.getAsJsonObject("field"));
		}

		/** Pull (or push) and hurt what's nearby, and run the field's commands every second. */
		private void field(ServerLevel level, JsonObject f) {
			double radius = Math.max(0.5, Math.min(48, f.has("radius") ? f.get("radius").getAsDouble() : 6));
			double pull = f.has("pull") ? f.get("pull").getAsDouble() : 0;
			float damage = f.has("damage") ? f.get("damage").getAsFloat() : 0;
			boolean spareOwner = !f.has("spare_owner") || f.get("spare_owner").getAsBoolean();
			Vec3 center = position();
			for (Entity e : level.getEntities(this, new AABB(center, center).inflate(radius))) {
				if (!(e instanceof LivingEntity || e instanceof ItemEntity) || e instanceof EffectEntity) continue;
				if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
				if (spareOwner && e.getUUID().equals(owner)) continue;
				Vec3 d = center.subtract(e.position().add(0, e.getBbHeight() / 2, 0));
				double dist = d.length();
				if (dist > radius || dist < 1e-3) continue;
				if (pull != 0) {
					// Stronger closer in, like gravity; capped so things don't fly off at absurd speeds.
					double strength = pull * 0.04 * Math.min(4, radius / Math.max(0.75, dist));
					Vec3 v = e.getDeltaMovement().add(d.normalize().scale(strength));
					if (v.lengthSqr() > 4) v = v.normalize().scale(2);
					e.setDeltaMovement(v);
					e.needsSync = true; // send players their new motion
					e.fallDistance = 0;
				}
				if (damage > 0 && tickCount % 10 == 0 && e instanceof LivingEntity living) {
					Entity source = owner == null ? null : level.getEntity(owner);
					DamageSource ds = source != null ? damageSources().indirectMagic(this, source) : damageSources().magic();
					living.hurtServer(level, ds, damage / 2); // damage is per second, applied twice a second
				}
			}
			if (tickCount % 20 == 0) {
				List<String> cmds = CustomItems.strings(f, "commands");
				if (!cmds.isEmpty()) ClaudeCraft.runAs(this, cmds, center, null);
			}
		}

		@Override
		protected void readAdditionalSaveData(ValueInput in) {
			// From summon: {Effect:"black_hole", To:[x, y, z]}
			Vec3 to = in.read("To", Vec3.CODEC).orElse(Vec3.ZERO);
			setEffect(in.getStringOr("Effect", ""), to, in.getIntOr("Seed", random.nextInt()));
		}

		@Override
		protected void addAdditionalSaveData(ValueOutput out) {
			out.putString("Effect", effectId());
			Vector3fc t = to();
			out.store("To", Vec3.CODEC, new Vec3(t.x(), t.y(), t.z()));
			out.putInt("Seed", seed());
		}

		@Override
		public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
			return false;
		}

		@Override
		public boolean isIgnoringBlockTriggers() {
			return true;
		}

		@Override
		public boolean shouldRenderAtSqrDistance(double distance) {
			return true; // vanilla hides entities this small past about 16 blocks; effects are meant to be seen from afar
		}

		@Override
		public boolean shouldBeSaved() {
			return false;
		}
	}
}
