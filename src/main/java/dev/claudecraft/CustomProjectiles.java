package dev.claudecraft;

import com.google.gson.JsonObject;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Things custom items and creatures shoot. The kind is "arrow", "fireball", "small_fireball" or "wind_charge" (the
 * vanilla projectiles), or an item id ("minecraft:snowball", "custom:ruby_shard", or "self" for the item itself),
 * which flies as that item, does the given damage and runs hit_commands where it lands.
 */
public final class CustomProjectiles {
	private CustomProjectiles() {}

	public static EntityType<Thrown> TYPE;
	static final List<String> VANILLA = List.of("arrow", "fireball", "small_fireball", "wind_charge", "beam");

	static void register() {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, CustomItems.id("thrown_item"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
			EntityType.Builder.<Thrown>of(Thrown::new, MobCategory.MISC).sized(0.25f, 0.25f).clientTrackingRange(4).updateInterval(10).build(key));
	}

	/** A projectile kind that names an item, or null if it doesn't name a known one. */
	static ItemStack itemFor(String kind, ItemStack self) {
		if (kind.equals("self")) return self == null ? null : self.copyWithCount(1);
		if (kind.startsWith("custom:")) {
			JsonObject def = CustomItems.DEFS.get(kind.substring(7));
			return def == null ? null : CustomItems.stack(def, 1);
		}
		Identifier id = Identifier.tryParse(kind);
		Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		return item == null || item == Items.AIR ? null : new ItemStack(item);
	}

	/** Check a shoot spec, returning a problem or null. */
	static String validate(JsonObject spec) {
		String kind = spec.has("projectile") ? spec.get("projectile").getAsString() : "arrow";
		if (VANILLA.contains(kind) || kind.equals("self") || kind.startsWith("custom:")) return null;
		return itemFor(kind, null) == null ? "Unknown projectile " + kind + " (use arrow, fireball, small_fireball, wind_charge, self, or an item id)" : null;
	}

	/** Does this stack count as the given ammo ("minecraft:arrow" or "custom:ruby")? */
	private static boolean isAmmo(ItemStack s, String ammo) {
		if (ammo.startsWith("custom:")) {
			JsonObject def = CustomItems.definition(s);
			return def != null && def.get("id").getAsString().equals(ammo.substring(7));
		}
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(ammo.contains(":") ? ammo : "minecraft:" + ammo);
	}

	/** Shoot from a held item. Returns false (and clicks) when it needs ammo the player doesn't have. */
	static boolean shootFromItem(ServerPlayer p, ItemStack weapon, JsonObject spec) {
		boolean creative = p.getAbilities().instabuild;
		String kind = spec.has("projectile") ? spec.get("projectile").getAsString() : "arrow";
		boolean pickup = false;
		if (spec.has("ammo") && !creative) {
			String ammo = spec.get("ammo").getAsString();
			ItemStack found = null;
			var inv = p.getInventory();
			for (int i = 0; i < inv.getContainerSize() && found == null; i++) if (isAmmo(inv.getItem(i), ammo)) found = inv.getItem(i);
			if (found == null) {
				p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.6f, 1.2f);
				return false;
			}
			found.shrink(1);
			pickup = kind.equals("arrow") && ammo.endsWith("arrow");
		}
		ItemStack self = weapon.copyWithCount(1);
		if (kind.equals("self") && !creative) weapon.shrink(1);
		launch(p, p.getLookAngle(), spec, self, pickup);
		return true;
	}

	/** Shoot at a target, aiming a little high for projectiles that fall. */
	static void shootAt(LivingEntity shooter, LivingEntity target, JsonObject spec) {
		double dx = target.getX() - shooter.getX(), dz = target.getZ() - shooter.getZ();
		double dy = target.getY(0.33) - shooter.getEyeY();
		String kind = spec.has("projectile") ? spec.get("projectile").getAsString() : "arrow";
		boolean falls = !(kind.equals("fireball") || kind.equals("small_fireball") || kind.equals("wind_charge") || kind.equals("beam"))
			&& !(spec.has("gravity") && !spec.get("gravity").getAsBoolean());
		if (falls) dy += Math.sqrt(dx * dx + dz * dz) * 0.2;
		launch(shooter, new Vec3(dx, dy, dz), spec, null, false);
	}

	static void launch(LivingEntity shooter, Vec3 dir, JsonObject spec, ItemStack self, boolean pickup) {
		if (!(shooter.level() instanceof ServerLevel level)) return;
		String kind = spec.has("projectile") ? spec.get("projectile").getAsString() : "arrow";
		float speed = spec.has("speed") ? Math.max(0.1f, Math.min(5f, spec.get("speed").getAsFloat())) : kind.equals("arrow") ? 2.5f : 1.5f;
		float damage = spec.has("damage") ? spec.get("damage").getAsFloat() : -1;
		float spread = shooter instanceof ServerPlayer ? 1f : 4f;
		Vec3 d = dir.normalize();
		if (kind.equals("beam")) {
			beam(level, shooter, d, spec);
			return;
		}
		Projectile proj;
		SoundEvent sound;
		switch (kind) {
			case "arrow" -> {
				Arrow a = new Arrow(level, shooter, new ItemStack(Items.ARROW), null);
				a.shoot(d.x, d.y, d.z, speed, spread);
				if (damage >= 0) a.setBaseDamage(damage / speed); // arrow damage scales with speed
				a.pickup = pickup ? AbstractArrow.Pickup.ALLOWED : AbstractArrow.Pickup.CREATIVE_ONLY;
				proj = a;
				sound = SoundEvents.ARROW_SHOOT;
			}
			case "fireball" -> {
				LargeFireball f = new LargeFireball(level, shooter, d, 1);
				f.setPos(shooter.getX() + d.x, shooter.getEyeY() - 0.2, shooter.getZ() + d.z);
				proj = f;
				sound = SoundEvents.GHAST_SHOOT;
			}
			case "small_fireball" -> {
				SmallFireball f = new SmallFireball(level, shooter, d);
				f.setPos(shooter.getX() + d.x * 0.5, shooter.getEyeY() - 0.2, shooter.getZ() + d.z * 0.5);
				proj = f;
				sound = SoundEvents.BLAZE_SHOOT;
			}
			case "wind_charge" -> {
				WindCharge w = new WindCharge(level, shooter.getX() + d.x * 0.5, shooter.getEyeY() - 0.2, shooter.getZ() + d.z * 0.5, d);
				w.setOwner(shooter);
				w.shoot(d.x, d.y, d.z, speed, spread);
				proj = w;
				sound = SoundEvents.WIND_CHARGE_THROW;
			}
			default -> {
				ItemStack item = itemFor(kind, self);
				if (item == null) item = new ItemStack(Items.SNOWBALL);
				Thrown t = new Thrown(level, shooter, item);
				t.damage = Math.max(0, damage);
				t.commands = CustomItems.strings(spec, "hit_commands");
				if (spec.has("gravity") && !spec.get("gravity").getAsBoolean()) t.setNoGravity(true);
				if (spec.has("effect")) t.setTrail(spec.get("effect").getAsString());
				if (spec.has("hit_effect")) t.hitEffect = spec.get("hit_effect").getAsString();
				t.shoot(d.x, d.y, d.z, speed, spread);
				proj = t;
				sound = SoundEvents.SNOWBALL_THROW;
			}
		}
		level.addFreshEntity(proj);
		level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), sound, shooter.getSoundSource(), 0.6f, 0.9f + level.getRandom().nextFloat() * 0.3f);
	}

	/** Where a shot visibly leaves the shooter: a player's right hand, or a mob's eyes. */
	static Vec3 muzzle(LivingEntity shooter, Vec3 dir) {
		Vec3 eye = shooter.getEyePosition();
		if (!(shooter instanceof ServerPlayer)) return eye.add(dir.scale(Math.max(0.3, shooter.getBbWidth() * 0.6)));
		Vec3 right = dir.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
		return eye.add(dir.scale(0.6)).add(right.scale(0.3)).add(0, -0.25, 0);
	}

	/**
	 * An instant beam (a laser, a lightning gun): hits the first creature or block along the line, hurts it, runs
	 * hit_commands there, and plays its effect from the shooter's hand to the hit point.
	 */
	static void beam(ServerLevel level, LivingEntity shooter, Vec3 dir, JsonObject spec) {
		double range = Math.max(1, Math.min(128, spec.has("range") ? spec.get("range").getAsDouble() : 48));
		Vec3 eye = shooter.getEyePosition(), end = eye.add(dir.scale(range));
		var block = level.clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
			net.minecraft.world.level.ClipContext.Fluid.NONE, shooter));
		Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
		EntityHitResult hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(shooter, eye, stop,
			new net.minecraft.world.phys.AABB(eye, stop).inflate(1),
			e -> e.isPickable() && !e.isSpectator() && e != shooter && !(e instanceof Effects.EffectEntity), eye.distanceToSqr(stop));
		Entity target = hit == null ? null : hit.getEntity();
		Vec3 at = hit == null ? stop : hit.getLocation();
		float damage = spec.has("damage") ? spec.get("damage").getAsFloat() : 0;
		if (target != null && damage > 0) {
			var source = shooter instanceof ServerPlayer p ? shooter.damageSources().playerAttack(p) : shooter.damageSources().mobAttack(shooter);
			target.hurtServer(level, source, damage);
		}
		if (spec.has("effect")) {
			JsonObject fx = CustomItems.DEFS.get(spec.get("effect").getAsString());
			if (Effects.isEffect(fx)) Effects.play(level, fx, muzzle(shooter, dir), at, shooter);
		}
		hitEffect(level, spec.has("hit_effect") ? spec.get("hit_effect").getAsString() : null, at, shooter);
		List<String> cmds = CustomItems.strings(spec, "hit_commands");
		if (!cmds.isEmpty()) {
			Entity about = target != null ? target : shooter;
			if (shooter instanceof ServerPlayer p) CustomItems.runAsPlayer(p, cmds, at, about);
			else ClaudeCraft.runAs(shooter, cmds, at, about);
		}
	}

	/** Play an effect where a shot lands (a burst, a shockwave, a black hole). */
	static void hitEffect(ServerLevel level, String id, Vec3 at, Entity owner) {
		JsonObject fx = id == null ? null : CustomItems.DEFS.get(id);
		if (Effects.isEffect(fx)) Effects.play(level, fx, at, at, owner);
	}

	/** A thrown item: draws as its item, hurts what it hits, runs its commands where it lands, then breaks. */
	public static final class Thrown extends ThrowableItemProjectile {
		private static final net.minecraft.network.syncher.EntityDataAccessor<String> TRAIL =
			net.minecraft.network.syncher.SynchedEntityData.defineId(Thrown.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);
		float damage;
		List<String> commands = new ArrayList<>();
		String hitEffect;

		@Override
		protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder b) {
			super.defineSynchedData(b);
			b.define(TRAIL, "");
		}

		void setTrail(String effect) {
			entityData.set(TRAIL, effect);
		}

		/** The effect drawn around it as it flies (an orb, sparks, a beam trailing behind), or "". */
		public String trail() {
			return entityData.get(TRAIL);
		}

		Thrown(EntityType<? extends Thrown> type, Level level) {
			super(type, level);
		}

		Thrown(Level level, LivingEntity owner, ItemStack item) {
			super(TYPE, owner, level, item);
		}

		@Override
		protected Item getDefaultItem() {
			return Items.SNOWBALL;
		}

		@Override
		protected void onHitEntity(EntityHitResult hit) {
			super.onHitEntity(hit);
			if (damage > 0 && level() instanceof ServerLevel level) hit.getEntity().hurtServer(level, damageSources().thrown(this, getOwner()), damage);
		}

		@Override
		protected void onHit(HitResult hit) {
			super.onHit(hit);
			if (!(level() instanceof ServerLevel level)) return;
			hitEffect(level, hitEffect, hit.getLocation(), getOwner());
			if (!commands.isEmpty()) {
				Entity target = hit instanceof EntityHitResult e ? e.getEntity() : this;
				Vec3 at = hit.getLocation();
				if (getOwner() instanceof ServerPlayer p) CustomItems.runAsPlayer(p, commands, at, target);
				else ClaudeCraft.runAs(getOwner() != null ? getOwner() : this, commands, at, target);
			}
			level().broadcastEntityEvent(this, (byte) 3);
			discard();
		}

		@Override
		public void handleEntityEvent(byte id) {
			if (id == 3) {
				var particle = new net.minecraft.core.particles.ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM,
					net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(getItem()));
				for (int i = 0; i < 8; i++) level().addParticle(particle, getX(), getY(), getZ(), 0, 0, 0);
			} else {
				super.handleEntityEvent(id);
			}
		}

		@Override
		protected void addAdditionalSaveData(ValueOutput out) {
			super.addAdditionalSaveData(out);
			out.putFloat("Damage", damage);
			out.putString("HitCommands", String.join("\n", commands));
			out.putString("Trail", trail());
			if (hitEffect != null) out.putString("HitEffect", hitEffect);
		}

		@Override
		protected void readAdditionalSaveData(ValueInput in) {
			super.readAdditionalSaveData(in);
			damage = in.getFloatOr("Damage", 0);
			String cmds = in.getStringOr("HitCommands", "");
			commands = cmds.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(cmds.split("\n")));
			setTrail(in.getStringOr("Trail", ""));
			hitEffect = in.getStringOr("HitEffect", "").isEmpty() ? null : in.getStringOr("HitEffect", "");
		}
	}
}
