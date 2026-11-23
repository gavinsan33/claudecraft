package dev.claudecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Creatures Claude designs while the game runs. One registered entity type, claudecraft:custom_entity; each
 * entity's definition id is synced data, and the definition sets its model, size, stats, behavior and sounds.
 */
public final class CustomEntities {
	private CustomEntities() {}

	public static EntityType<CustomEntity> TYPE;
	private static final Map<String, EntityModel> MODELS = new ConcurrentHashMap<>();

	static void register() {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, CustomItems.id("custom_entity"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
			EntityType.Builder.of(CustomEntity::new, MobCategory.CREATURE).sized(0.8f, 0.8f).clientTrackingRange(10).build(key));
		FabricDefaultAttributeRegistry.register(TYPE, Mob.createMobAttributes()
			.add(Attributes.ATTACK_DAMAGE, 2).add(Attributes.MOVEMENT_SPEED, 0.25).add(Attributes.FLYING_SPEED, 0.4).add(Attributes.FOLLOW_RANGE, 24));
	}

	static boolean isEntity(JsonObject def) {
		return def != null && def.has("kind") && def.get("kind").getAsString().equals("entity");
	}

	static void definitionsChanged() {
		MODELS.clear();
	}

	/** A definition's parsed model, cached; null if it's missing or broken. Both sides. */
	public static EntityModel model(String id) {
		JsonObject def = id == null ? null : CustomItems.DEFS.get(id);
		if (!isEntity(def)) return null;
		return MODELS.computeIfAbsent(id, k -> {
			try {
				return EntityModel.parse(def.getAsJsonObject("model"), def.has("scale") ? def.get("scale").getAsFloat() : 16);
			} catch (RuntimeException e) {
				return null;
			}
		});
	}

	/** Spawn copies of a definition around a point. Server thread. */
	static List<CustomEntity> spawn(ServerLevel level, JsonObject def, Vec3 at, int count) {
		List<CustomEntity> out = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			CustomEntity e = TYPE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			if (e == null) continue;
			double spread = count == 1 ? 0 : 1.5;
			e.snapTo(at.x + (level.getRandom().nextDouble() - 0.5) * spread, at.y, at.z + (level.getRandom().nextDouble() - 0.5) * spread,
				level.getRandom().nextFloat() * 360, 0);
			e.setDefinition(def.get("id").getAsString(), true);
			e.setPersistenceRequired();
			level.addFreshEntity(e);
			out.add(e);
		}
		return out;
	}

	public static final class CustomEntity extends PathfinderMob {
		private static final EntityDataAccessor<String> DEFINITION = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.STRING);
		private boolean flyingSetUp;

		CustomEntity(EntityType<? extends CustomEntity> type, Level level) {
			super(type, level);
		}

		@Override
		protected void defineSynchedData(SynchedEntityData.Builder builder) {
			super.defineSynchedData(builder);
			builder.define(DEFINITION, "");
		}

		public String definitionId() {
			return entityData.get(DEFINITION);
		}

		JsonObject definition() {
			JsonObject def = CustomItems.DEFS.get(definitionId());
			return isEntity(def) ? def : null;
		}

		private JsonObject props() {
			JsonObject def = definition();
			return def != null && def.has("properties") ? def.getAsJsonObject("properties") : new JsonObject();
		}

		private String behavior() {
			JsonObject p = props();
			return p.has("behavior") ? p.get("behavior").getAsString() : "passive";
		}

		boolean flying() {
			JsonObject p = props();
			return p.has("movement") && p.get("movement").getAsString().equals("fly");
		}

		/** Set the definition and apply its stats. fresh: also fill health to the new maximum. */
		void setDefinition(String id, boolean fresh) {
			entityData.set(DEFINITION, id);
			applyDefinition(fresh);
		}

		/** Apply size, stats and movement from the definition. Also called when it's redefined. */
		void applyDefinition(boolean fresh) {
			refreshDimensions();
			JsonObject p = props();
			if (!level().isClientSide()) {
				double health = p.has("health") ? Math.max(1, Math.min(1024, p.get("health").getAsDouble())) : 10;
				getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
				if (fresh || getHealth() > health) setHealth((float) health);
				getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(p.has("speed") ? p.get("speed").getAsDouble() : 0.25);
				getAttribute(Attributes.FLYING_SPEED).setBaseValue(p.has("speed") ? p.get("speed").getAsDouble() * 1.6 : 0.4);
				getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(p.has("attack_damage") ? p.get("attack_damage").getAsDouble() : 2);
			}
			boolean fly = flying();
			if (fly != flyingSetUp) {
				flyingSetUp = fly;
				moveControl = fly ? new FlyingMoveControl(this, 20, true) : new MoveControl(this);
				navigation = fly ? new FlyingPathNavigation(this, level()) : new GroundPathNavigation(this, level());
				setNoGravity(fly);
			}
		}

		@Override
		public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
			super.onSyncedDataUpdated(accessor);
			if (DEFINITION.equals(accessor)) refreshDimensions();
		}

		@Override
		protected EntityDimensions getDefaultDimensions(Pose pose) {
			EntityModel m = model(definitionId());
			return m == null ? super.getDefaultDimensions(pose) : EntityDimensions.scalable(m.width(), m.height());
		}

		/** A goal that only runs while the definition allows it (goals are added before the definition is known). */
		private static Goal gated(Goal inner, BooleanSupplier allowed) {
			Goal g = new Goal() {
				@Override public boolean canUse() { return allowed.getAsBoolean() && inner.canUse(); }
				@Override public boolean canContinueToUse() { return allowed.getAsBoolean() && inner.canContinueToUse(); }
				@Override public boolean isInterruptable() { return inner.isInterruptable(); }
				@Override public void start() { inner.start(); }
				@Override public void stop() { inner.stop(); }
				@Override public boolean requiresUpdateEveryTick() { return inner.requiresUpdateEveryTick(); }
				@Override public void tick() { inner.tick(); }
			};
			g.setFlags(inner.getFlags());
			return g;
		}

		@Override
		protected void registerGoals() {
			goalSelector.addGoal(0, new FloatGoal(this));
			goalSelector.addGoal(1, gated(new PanicGoal(this, 1.4), () -> behavior().equals("passive")));
			goalSelector.addGoal(2, gated(new MeleeAttackGoal(this, 1.2, false), () -> !behavior().equals("passive")));
			goalSelector.addGoal(5, gated(new WaterAvoidingRandomStrollGoal(this, 1.0), () -> !flying()));
			goalSelector.addGoal(5, gated(new WaterAvoidingRandomFlyingGoal(this, 1.0), this::flying));
			goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8f));
			goalSelector.addGoal(7, new RandomLookAroundGoal(this));
			targetSelector.addGoal(1, gated(new HurtByTargetGoal(this), () -> !behavior().equals("passive")));
			targetSelector.addGoal(2, gated(new NearestAttackableTargetGoal<>(this, Player.class, true), () -> behavior().equals("hostile")));
		}

		@Override
		protected void addAdditionalSaveData(ValueOutput out) {
			super.addAdditionalSaveData(out);
			out.putString("Definition", definitionId());
		}

		@Override
		protected void readAdditionalSaveData(ValueInput in) {
			super.readAdditionalSaveData(in);
			entityData.set(DEFINITION, in.getStringOr("Definition", ""));
			applyDefinition(false);
		}

		@Override
		public boolean removeWhenFarAway(double distance) {
			return false;
		}

		@Override
		protected Component getTypeName() {
			JsonObject def = definition();
			return def == null ? super.getTypeName() : Component.literal(def.get("name").getAsString());
		}

		@Override
		public boolean causeFallDamage(double distance, float multiplier, DamageSource source) {
			return !flying() && super.causeFallDamage(distance, multiplier, source);
		}

		private SoundEvent sound(String kind) {
			JsonObject p = props();
			if (!p.has("sounds")) return null;
			String base = p.get("sounds").getAsString();
			if (!base.contains(":")) base = "minecraft:entity." + base; // "pig" -> minecraft:entity.pig
			Identifier id = Identifier.tryParse(base + "." + kind);
			return id == null ? null : BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
		}

		@Override
		protected SoundEvent getAmbientSound() {
			return sound("ambient");
		}

		@Override
		protected SoundEvent getHurtSound(DamageSource source) {
			SoundEvent s = sound("hurt");
			return s != null ? s : super.getHurtSound(source);
		}

		@Override
		protected SoundEvent getDeathSound() {
			SoundEvent s = sound("death");
			return s != null ? s : super.getDeathSound();
		}

		/** Drops like "minecraft:bone 0-2" or "custom:ruby". */
		@Override
		protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
			super.dropCustomDeathLoot(level, source, recentlyHit);
			JsonObject p = props();
			if (!p.has("drops")) return;
			for (JsonElement e : p.getAsJsonArray("drops")) {
				String[] parts = e.getAsString().trim().split("\\s+");
				int min = 1, max = 1;
				if (parts.length > 1) {
					String[] range = parts[1].split("-");
					min = Integer.parseInt(range[0]);
					max = range.length > 1 ? Integer.parseInt(range[1]) : min;
				}
				int n = min + (max > min ? random.nextInt(max - min + 1) : 0);
				if (n <= 0) continue;
				ItemStack stack = dropStack(parts[0], n);
				if (stack != null) spawnAtLocation(level, stack);
			}
		}

		static ItemStack dropStack(String id, int count) {
			if (id.startsWith("custom:")) {
				JsonObject def = CustomItems.DEFS.get(id.substring("custom:".length()));
				return def == null || isEntity(def) ? null : CustomItems.stack(def, count);
			}
			Identifier key = Identifier.tryParse(id);
			Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
			return item == null ? null : new ItemStack(item, count);
		}

		@Override
		protected InteractionResult mobInteract(Player player, InteractionHand hand) {
			JsonObject p = props();
			if (!p.has("interact_commands")) return super.mobInteract(player, hand);
			if (!level().isClientSide() && player instanceof ServerPlayer sp) {
				// Run at the creature, so ~ ~ ~ is where it stands while @s is still the player.
				String at = "execute positioned " + getX() + " " + getY() + " " + getZ() + " run ";
				List<String> cmds = new ArrayList<>();
				for (JsonElement e : p.getAsJsonArray("interact_commands")) cmds.add(at + e.getAsString().replaceFirst("^/", ""));
				ClaudeCraft.runItemCommands(sp, cmds);
			}
			return InteractionResult.SUCCESS;
		}
	}
}
