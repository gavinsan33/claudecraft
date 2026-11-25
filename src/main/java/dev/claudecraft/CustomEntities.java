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
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.FollowParentGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomSwimmingGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
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
 * It's a tamable animal underneath, so taming, sitting, riding, breeding and babies all come from vanilla, and
 * each is switched on by the definition's properties.
 */
public final class CustomEntities {
	private CustomEntities() {}

	public static EntityType<CustomEntity> TYPE;
	private static final Map<String, EntityModel> MODELS = new ConcurrentHashMap<>();
	static final List<String> MOVEMENTS = List.of("walk", "fly", "swim", "hop");

	static void register() {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, CustomItems.id("custom_entity"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
			EntityType.Builder.of(CustomEntity::new, MobCategory.CREATURE).sized(0.8f, 0.8f).clientTrackingRange(10).build(key));
		FabricDefaultAttributeRegistry.register(TYPE, Animal.createAnimalAttributes()
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
	static List<CustomEntity> spawn(ServerLevel level, JsonObject def, Vec3 at, int count, boolean baby, boolean natural) {
		List<CustomEntity> out = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			CustomEntity e = TYPE.create(level, natural ? EntitySpawnReason.NATURAL : EntitySpawnReason.COMMAND);
			if (e == null) continue;
			double spread = count == 1 ? 0 : 1.5;
			e.snapTo(at.x + (level.getRandom().nextDouble() - 0.5) * spread, at.y, at.z + (level.getRandom().nextDouble() - 0.5) * spread,
				level.getRandom().nextFloat() * 360, 0);
			e.setDefinition(def.get("id").getAsString(), true);
			if (baby) e.setAge(AgeableMob.BABY_START_AGE);
			if (natural) e.natural = true;
			else e.setPersistenceRequired();
			level.addFreshEntity(e);
			out.add(e);
		}
		return out;
	}

	static List<CustomEntity> spawn(ServerLevel level, JsonObject def, Vec3 at, int count) {
		return spawn(level, def, at, count, false, false);
	}

	/** Does the stack match an entry like "minecraft:wheat", "#minecraft:meat" or "custom:berry"? */
	static boolean matches(ItemStack s, List<String> items) {
		if (s.isEmpty()) return false;
		for (String n : items) {
			if (n.startsWith("custom:")) {
				JsonObject def = CustomItems.definition(s);
				if (def != null && def.get("id").getAsString().equals(n.substring(7))) return true;
			} else if (n.startsWith("#")) {
				Identifier tag = Identifier.tryParse(n.substring(1));
				if (tag != null && s.is(net.minecraft.tags.TagKey.create(Registries.ITEM, tag))) return true;
			} else if (BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(n.contains(":") ? n : "minecraft:" + n)) {
				return true;
			}
		}
		return false;
	}

	public static final class CustomEntity extends TamableAnimal implements RangedAttackMob, net.minecraft.world.entity.HasCustomInventoryScreen {
		private static final EntityDataAccessor<String> DEFINITION = SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.STRING);
		private String movementSetUp = "walk";
		/** Spawned by natural spawning: despawns far from players like vanilla mobs, unless tamed or named. */
		boolean natural;
		private RangedAttackGoal rangedGoal;
		private String rangedKey = "";

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

		JsonObject props() {
			return CustomItems.props(definition());
		}

		private String str(String key, String def) {
			JsonObject p = props();
			return p.has(key) ? p.get(key).getAsString() : def;
		}

		private String behavior() {
			return str("behavior", "passive");
		}

		public String movement() {
			return str("movement", "walk");
		}

		boolean flying() {
			return movement().equals("fly");
		}

		boolean swimming() {
			return movement().equals("swim");
		}

		private JsonObject ranged() {
			JsonObject p = props();
			return p.has("ranged") && p.get("ranged").isJsonObject() ? p.getAsJsonObject("ranged") : null;
		}

		private List<String> tameItems() {
			JsonObject p = props();
			if (!p.has("tame")) return List.of();
			JsonElement t = p.get("tame");
			return t.isJsonObject() ? CustomItems.strings(t.getAsJsonObject(), "items") : List.of();
		}

		boolean tameable() {
			return props().has("tame");
		}

		boolean rideable() {
			JsonObject p = props();
			return p.has("rideable") && p.get("rideable").getAsBoolean() || vehicle() != null;
		}

		// ------------------------------------------------------------ storage

		/** Its inventory (a vehicle's trunk, a pack animal's saddlebags), made when first needed; null if none yet. */
		private net.minecraft.world.SimpleContainer storage;

		public int storageSlots() {
			return StorageMenus.slots(props(), false);
		}

		private net.minecraft.world.SimpleContainer storage() {
			int size = StorageMenus.rows(Math.max(1, storageSlots())) * 9;
			if (storage == null || storage.getContainerSize() < size) {
				var grown = new net.minecraft.world.SimpleContainer(size);
				if (storage != null) for (int i = 0; i < storage.getContainerSize(); i++) grown.setItem(i, storage.getItem(i));
				storage = grown;
			}
			return storage;
		}

		/** Open its storage for a player: its rider (the inventory key), or anyone sneak-clicking it, while nearby. */
		private void openStorage(Player player) {
			if (storageSlots() <= 0 || !(player instanceof ServerPlayer sp)) return;
			StorageMenus.open(sp, getName(), storage(), storageSlots(), -1, p -> isAlive() && p.distanceToSqr(this) < 64);
		}

		@Override
		public void openCustomInventoryScreen(Player player) {
			openStorage(player);
		}

		// ------------------------------------------------------------ vehicles

		/** The vehicle settings, or null for an ordinary creature. A vehicle has no AI and moves only as its rider drives it. */
		JsonObject vehicle() {
			JsonObject p = props();
			return p.has("vehicle") && p.get("vehicle").isJsonObject() ? p.getAsJsonObject("vehicle") : null;
		}

		/** thrust: jump pushes it up, WASD push it around, it turns to where the rider looks. drive: W/S drive, A/D steer. */
		private static boolean thrust(JsonObject v) {
			return !v.has("controls") || !v.get("controls").getAsString().equals("drive");
		}

		private static double vnum(JsonObject v, String key, double dflt) {
			return v.has(key) ? v.get(key).getAsDouble() : dflt;
		}

		/** Top speed in blocks per tick. */
		private static double maxSpeed(JsonObject v) {
			return Math.max(0.5, Math.min(60, vnum(v, "max_speed", thrust(v) ? 12 : 10))) / 20;
		}

		/** How far it leans (degrees, client side): roll toward where it drifts sideways, pitch toward where it's going. */
		public float roll, rollO, lean, leanO;

		private void updateLean() {
			rollO = roll;
			leanO = lean;
			JsonObject v = vehicle();
			float r = 0, l = 0;
			if (v != null) {
				float tilt = (float) vnum(v, "tilt", thrust(v) ? 25 : 0);
				double max = maxSpeed(v), dx = getX() - xo, dz = getZ() - zo, yaw = getYRot() * Mth.DEG_TO_RAD;
				double forward = -dx * Math.sin(yaw) + dz * Math.cos(yaw), right = -dx * Math.cos(yaw) - dz * Math.sin(yaw);
				r = (float) Mth.clamp(right / max, -1, 1) * tilt;
				l = (float) Mth.clamp(forward / max, -1, 1) * tilt;
			}
			roll += (r - roll) * 0.25f;
			lean += (l - lean) * 0.25f;
		}

		@Override
		public void tick() {
			super.tick();
			if (level().isClientSide()) updateLean(); // from how it actually moved, so every player sees the same lean
		}

		/** A vehicle's own physics: thrust or engine from its rider, gravity, drag, and tyres that grip. */
		private void vehicleTravel(JsonObject v, Vec3 input) {
			boolean thrust = thrust(v), driven = getControllingPassenger() instanceof Player;
			double max = maxSpeed(v), accel = vnum(v, "acceleration", thrust ? 12 : 8) / 400, gravity = vnum(v, "gravity", thrust ? 16 : 32) / 400;
			if (driven) {
				if (thrust) moveRelative((float) accel, new Vec3(input.x, 0, input.z));
				else if (onGround()) moveRelative((float) accel, new Vec3(0, 0, input.z < 0 ? input.z * 0.5 : input.z));
			}
			Vec3 vel = getDeltaMovement();
			if (driven && thrust && input.y > 0) vel = vel.add(0, vnum(v, "thrust", 28) / 400, 0);
			vel = vel.add(0, -gravity, 0);
			if (thrust) {
				vel = new Vec3(vel.x * 0.95, vel.y * 0.98, vel.z * 0.95); // air drag
			} else {
				// Tyres: keep speed along the way it faces, lose most of any slide; coast to a stop without the pedal.
				Vec3 f = Vec3.directionFromRotation(0, getYRot());
				double along = vel.x * f.x + vel.z * f.z;
				double keep = !onGround() ? 0.99 : driven && input.z != 0 ? 0.98 : 0.9;
				vel = new Vec3(f.x * along * keep + (vel.x - f.x * along) * 0.3, vel.y, f.z * along * keep + (vel.z - f.z * along) * 0.3);
			}
			double h = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
			if (h > max) vel = new Vec3(vel.x * max / h, vel.y, vel.z * max / h);
			vel = new Vec3(vel.x, Mth.clamp(vel.y, -3, max), vel.z);
			setDeltaMovement(vel);
			move(net.minecraft.world.entity.MoverType.SELF, vel);
			calculateEntityAnimation(false);
		}

		/** While its rider thrusts (jump) or drives (forward), play its exhaust effect under it. Server thread. */
		private void exhaust(ServerLevel level, JsonObject v) {
			if (!v.has("exhaust") || tickCount % 4 != 0 || !(getControllingPassenger() instanceof ServerPlayer rider)) return;
			var in = rider.getLastClientInput();
			if (!(thrust(v) ? in.jump() : in.forward())) return;
			JsonObject fx = CustomItems.DEFS.get(v.get("exhaust").getAsString());
			if (!Effects.isEffect(fx)) return;
			float[] at = {0, 0, 0};
			if (v.has("exhaust_at") && v.get("exhaust_at").isJsonArray()) {
				var a = v.getAsJsonArray("exhaust_at");
				for (int k = 0; k < 3 && k < a.size(); k++) at[k] = a.get(k).getAsFloat();
			}
			float scale = definition().has("scale") ? definition().get("scale").getAsFloat() : 16;
			Vec3 from = position().add(new Vec3(at[0] / scale, at[1] / scale, at[2] / scale).yRot(-getYRot() * Mth.DEG_TO_RAD));
			Effects.play(level, fx, from, from, rider, this);
		}

		/** Set the definition and apply its stats. fresh: also fill health to the new maximum. */
		void setDefinition(String id, boolean fresh) {
			for (String t : List.copyOf(entityTags())) if (t.startsWith("custom.")) removeTag(t);
			entityData.set(DEFINITION, id);
			if (!id.isEmpty()) addTag(CommandRewriter.tag(id));
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
				setUpRangedGoal();
			}
			String move = movement();
			if (!move.equals(movementSetUp)) {
				movementSetUp = move;
				switch (move) {
					case "fly" -> {
						moveControl = new FlyingMoveControl(this, 20, true);
						navigation = new FlyingPathNavigation(this, level());
					}
					case "swim" -> {
						moveControl = new SmoothSwimmingMoveControl(this, 85, 10, 0.02f, 0.1f, true);
						navigation = new WaterBoundPathNavigation(this, level());
					}
					default -> {
						moveControl = new MoveControl(this);
						navigation = new GroundPathNavigation(this, level());
					}
				}
				setPathfindingMalus(PathType.WATER, move.equals("swim") ? 0 : 8);
			}
			boolean vehicle = vehicle() != null;
			setNoGravity(vehicle || move.equals("fly")); // vehicles do their own gravity
			if (!level().isClientSide()) {
				// No AI at all for vehicles: they don't wander, flee, fight or look around.
				for (Goal.Flag f : Goal.Flag.values()) {
					if (vehicle) {
						goalSelector.disableControlFlag(f);
						targetSelector.disableControlFlag(f);
					} else {
						goalSelector.enableControlFlag(f);
						targetSelector.enableControlFlag(f);
					}
				}
				getAttribute(Attributes.STEP_HEIGHT).setBaseValue(vehicle && !thrust(vehicle()) ? 1.0 : 0.6); // cars drive up blocks
			}
		}

		/** The ranged attack goal is built from the definition's numbers, so rebuild it when they change. */
		private void setUpRangedGoal() {
			JsonObject r = ranged();
			String key = r == null ? "" : r.toString();
			if (key.equals(rangedKey)) return;
			rangedKey = key;
			if (rangedGoal != null) goalSelector.removeGoal(rangedGoal);
			rangedGoal = null;
			if (r == null) return;
			int interval = Math.max(5, Math.round((r.has("interval_seconds") ? r.get("interval_seconds").getAsFloat() : 2) * 20));
			float range = r.has("range") ? r.get("range").getAsFloat() : 16;
			rangedGoal = new RangedAttackGoal(this, 1.0, interval, range);
			goalSelector.addGoal(2, gated(rangedGoal, () -> !behavior().equals("passive") && !isOrderedToSit()));
		}

		@Override
		public void performRangedAttack(LivingEntity target, float power) {
			JsonObject r = ranged();
			if (r != null) CustomProjectiles.shootAt(this, target, r);
		}

		@Override
		public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
			super.onSyncedDataUpdated(accessor);
			if (DEFINITION.equals(accessor)) applyDefinition(false);
		}

		@Override
		protected EntityDimensions getDefaultDimensions(Pose pose) {
			EntityModel m = model(definitionId());
			return m == null ? super.getDefaultDimensions(pose) : EntityDimensions.scalable(m.width(), m.height()).scale(getAgeScale());
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
			goalSelector.addGoal(0, gated(new FloatGoal(this), () -> !swimming()));
			goalSelector.addGoal(1, new SitWhenOrderedToGoal(this));
			goalSelector.addGoal(1, gated(new PanicGoal(this, 1.4), () -> behavior().equals("passive") && !isTame()));
			goalSelector.addGoal(2, gated(new MeleeAttackGoal(this, 1.2, false), () -> !behavior().equals("passive") && ranged() == null));
			goalSelector.addGoal(3, gated(new BreedGoal(this, 1.0), () -> !food().isEmpty()));
			goalSelector.addGoal(4, gated(new TemptGoal(this, 1.1, s -> isFood(s) || matches(s, tameItems()), false), () -> !isTame()));
			goalSelector.addGoal(4, gated(new FollowOwnerGoal(this, 1.0, 10f, 3f), () -> isTame() && !isVehicle()));
			goalSelector.addGoal(5, gated(new FollowParentGoal(this, 1.1), () -> !food().isEmpty()));
			goalSelector.addGoal(6, gated(new WaterAvoidingRandomStrollGoal(this, 1.0), () -> !flying() && !swimming()));
			goalSelector.addGoal(6, gated(new WaterAvoidingRandomFlyingGoal(this, 1.0), this::flying));
			goalSelector.addGoal(6, gated(new RandomSwimmingGoal(this, 1.0, 10), this::swimming));
			goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8f));
			goalSelector.addGoal(8, new RandomLookAroundGoal(this));
			targetSelector.addGoal(1, gated(new OwnerHurtByTargetGoal(this), () -> !behavior().equals("passive")));
			targetSelector.addGoal(2, gated(new OwnerHurtTargetGoal(this), () -> !behavior().equals("passive")));
			targetSelector.addGoal(3, gated(new HurtByTargetGoal(this), () -> !behavior().equals("passive")));
			targetSelector.addGoal(4, gated(new NearestAttackableTargetGoal<>(this, Player.class, true),
				() -> behavior().equals("hostile") && !isTame() && level().getDifficulty() != Difficulty.PEACEFUL));
		}

		// ------------------------------------------------------------ breeding, taming, riding

		private List<String> food() {
			return CustomItems.strings(props(), "food");
		}

		@Override
		public boolean isFood(ItemStack stack) {
			return matches(stack, food());
		}

		@Override
		public boolean canMate(Animal partner) {
			return partner instanceof CustomEntity ce && ce.definitionId().equals(definitionId()) && super.canMate(partner);
		}

		@Override
		public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
			CustomEntity child = TYPE.create(level, EntitySpawnReason.BREEDING);
			if (child == null) return null;
			child.setDefinition(definitionId(), true);
			child.setPersistenceRequired();
			if (isTame() && getOwnerReference() != null) {
				child.setOwnerReference(getOwnerReference());
				child.setTame(true, true);
			}
			return child;
		}

		@Override
		public InteractionResult mobInteract(Player player, InteractionHand hand) {
			ItemStack held = player.getItemInHand(hand);
			JsonObject p = props();
			// Tamed creatures heal on their food first, like wolves.
			if (isTame() && isFood(held) && getHealth() < getMaxHealth()) {
				if (!level().isClientSide()) {
					usePlayerItem(player, hand, held);
					heal(Math.max(2, getMaxHealth() / 10));
				}
				return InteractionResult.SUCCESS;
			}
			if (isFood(held)) {
				InteractionResult r = super.mobInteract(player, hand); // breed or grow up
				if (r.consumesAction()) return r;
			}
			if (tameable() && !isTame() && matches(held, tameItems())) {
				if (!level().isClientSide()) {
					usePlayerItem(player, hand, held);
					JsonObject t = p.get("tame").isJsonObject() ? p.getAsJsonObject("tame") : new JsonObject();
					float chance = t.has("chance") ? t.get("chance").getAsFloat() : 0.33f;
					if (random.nextFloat() < chance) {
						tame(player);
						navigation.stop();
						setTarget(null);
						level().broadcastEntityEvent(this, (byte) 7); // hearts
					} else {
						level().broadcastEntityEvent(this, (byte) 6); // smoke
					}
				}
				return InteractionResult.SUCCESS;
			}
			if (player.isSecondaryUseActive()) {
				// Sneak + right-click: open its storage, or its sneak_interact_commands.
				if (storageSlots() > 0) {
					if (!level().isClientSide()) openStorage(player);
					return InteractionResult.SUCCESS;
				}
				if (p.has("sneak_interact_commands")) {
					if (!level().isClientSide() && player instanceof ServerPlayer sp) {
						CustomItems.runAsPlayer(sp, CustomItems.strings(p, "sneak_interact_commands"), position(), this);
					}
					return InteractionResult.SUCCESS;
				}
			}
			boolean mayControl = !tameable() || isOwnedBy(player);
			if (rideable() && mayControl && !isBaby() && !isVehicle() && !player.isSecondaryUseActive()) {
				if (!level().isClientSide()) {
					setOrderedToSit(false);
					setInSittingPose(false);
					player.startRiding(this);
				}
				return InteractionResult.SUCCESS;
			}
			if (p.has("interact_commands") && !(isTame() && isOwnedBy(player) && player.isSecondaryUseActive())) {
				if (!level().isClientSide() && player instanceof ServerPlayer sp) {
					CustomItems.runAsPlayer(sp, CustomItems.strings(p, "interact_commands"), position(), this);
				}
				return InteractionResult.SUCCESS;
			}
			if (isTame() && isOwnedBy(player)) {
				if (!level().isClientSide()) {
					setOrderedToSit(!isOrderedToSit());
					setInSittingPose(isOrderedToSit());
					navigation.stop();
					setTarget(null);
				}
				return InteractionResult.SUCCESS;
			}
			return super.mobInteract(player, hand);
		}

		@Override
		public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
			// Vanilla uses a spawn egg of the same entity type on a mob to make a baby; every custom creature shares
			// one type, so only let a creature's own egg do that.
			ItemStack held = player.getItemInHand(hand);
			if (held.is(CustomItems.SPAWN_EGG) && !definitionId().equals(held.get(CustomItems.DEFINITION))) return InteractionResult.PASS;
			return super.interact(player, hand, location);
		}

		@Override
		public LivingEntity getControllingPassenger() {
			return rideable() && getFirstPassenger() instanceof Player p && (!tameable() || isOwnedBy(p)) ? p : super.getControllingPassenger();
		}

		@Override
		protected void tickRidden(Player controller, Vec3 input) {
			super.tickRidden(controller, input);
			JsonObject v = vehicle();
			if (v != null) {
				float yRot = getYRot();
				if (thrust(v)) {
					yRot += Mth.wrapDegrees(controller.getYRot() - yRot) * (float) Mth.clamp(vnum(v, "turn", 0.15), 0, 1);
				} else if (isLocalInstanceAuthoritative()) {
					// Steer with A/D, more as it speeds up, and the other way in reverse, like a car.
					Vec3 f = Vec3.directionFromRotation(0, yRot), vel = getDeltaMovement();
					double along = vel.x * f.x + vel.z * f.z;
					double grip = Math.min(1, Math.abs(along) / (maxSpeed(v) * 0.3));
					yRot -= (float) (input.x * vnum(v, "turn", 4) * grip * Math.signum(along));
				}
				setRot(yRot, 0);
				yBodyRot = yHeadRot = yRot;
				return;
			}
			float yRot = getYRot() + Mth.wrapDegrees(controller.getYRot() - getYRot()) * (flying() ? 0.15f : 0.5f);
			setRot(yRot, controller.getXRot() * 0.5f);
			yRotO = yBodyRot = yHeadRot = yRot;
		}

		@Override
		protected Vec3 getRiddenInput(Player controller, Vec3 selfInput) {
			if (vehicle() != null) return new Vec3(controller.xxa, controller.isJumping() ? 1 : 0, controller.zza); // raw keys
			float strafe = controller.xxa * 0.5f, forward = controller.zza;
			if (forward < 0) forward *= 0.25f;
			if (flying() || swimming() && isInWater()) {
				// Fly (or swim) where the rider looks; jump climbs.
				float up = 0;
				if (forward != 0) up = -Mth.sin(controller.getXRot() * Mth.DEG_TO_RAD) * Math.signum(forward);
				if (controller.isJumping()) up += 0.5f;
				return new Vec3(strafe, up, forward);
			}
			if (controller.isJumping() && onGround()) jumpFromGround();
			return new Vec3(strafe, 0, forward);
		}

		@Override
		protected float getRiddenSpeed(Player controller) {
			float base = (float) getAttributeValue(flying() ? Attributes.FLYING_SPEED : Attributes.MOVEMENT_SPEED);
			return flying() ? base * 0.6f : base;
		}

		@Override
		public void travel(Vec3 input) {
			JsonObject v = vehicle();
			if (v != null) {
				vehicleTravel(v, input);
				return;
			}
			if (flying() && getControllingPassenger() instanceof Player) {
				travelFlying(input, getRiddenSpeed((Player) getControllingPassenger()));
				return;
			}
			if (swimming() && isInWater()) {
				// Like fish and dolphins: move through the water, no sinking.
				moveRelative(getSpeed() * (getControllingPassenger() != null ? 1f : 0.1f), input);
				move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());
				setDeltaMovement(getDeltaMovement().scale(0.9));
				return;
			}
			super.travel(input);
		}

		@Override
		public boolean canBeLeashed() {
			return !isAggressive();
		}

		@Override
		public boolean canBreatheUnderwater() {
			return swimming();
		}

		@Override
		public boolean isPushedByFluid() {
			return !swimming();
		}

		// ------------------------------------------------------------ hooks and abilities

		@Override
		public void aiStep() {
			super.aiStep();
			if (!(level() instanceof ServerLevel level) || !isAlive()) return;
			if (natural && behavior().equals("hostile") && level.getDifficulty() == Difficulty.PEACEFUL) {
				discard();
				return;
			}
			// Hop: jump whenever it's on the move, like a rabbit or slime.
			if (movement().equals("hop") && onGround() && (navigation.isInProgress() || getControllingPassenger() != null && getDeltaMovement().horizontalDistanceSqr() > 1e-4)) {
				jumpControl.jump();
			}
			if (vehicle() != null) exhaust(level, vehicle());
			JsonObject p = props();
			if (!p.has("abilities") || !p.get("abilities").isJsonArray()) return;
			int i = 0;
			for (JsonElement e : p.getAsJsonArray("abilities")) {
				i++;
				if (!e.isJsonObject()) continue;
				JsonObject a = e.getAsJsonObject();
				int every = Math.max(1, Math.round((a.has("every_seconds") ? a.get("every_seconds").getAsFloat() : 5) * 20));
				if ((tickCount + i * 7) % every != 0) continue;
				String when = a.has("when") ? a.get("when").getAsString() : "always";
				LivingEntity target = getTarget();
				if (when.equals("fighting") && (target == null || !target.isAlive())) continue;
				if (when.equals("idle") && target != null) continue;
				ClaudeCraft.runAs(this, CustomItems.strings(a, "commands"), position(), target);
			}
		}

		@Override
		public boolean doHurtTarget(ServerLevel level, Entity target) {
			boolean hit = super.doHurtTarget(level, target);
			if (hit) ClaudeCraft.runAs(this, CustomItems.strings(props(), "attack_commands"), target.position(), target);
			return hit;
		}

		@Override
		protected void actuallyHurt(ServerLevel level, DamageSource source, float dmg) {
			super.actuallyHurt(level, source, dmg);
			ClaudeCraft.runAs(this, CustomItems.strings(props(), "hurt_commands"), position(), source.getEntity());
		}

		@Override
		public void die(DamageSource source) {
			if (level() instanceof ServerLevel) ClaudeCraft.runAs(this, CustomItems.strings(props(), "death_commands"), position(), source.getEntity());
			if (storage != null && level() instanceof ServerLevel) net.minecraft.world.Containers.dropContents(level(), this, storage);
			super.die(source);
		}

		// ------------------------------------------------------------ saving

		@Override
		protected void addAdditionalSaveData(ValueOutput out) {
			super.addAdditionalSaveData(out);
			out.putString("Definition", definitionId());
			if (!definitionId().isEmpty()) out.putBoolean("DefinitionApplied", true);
			out.putBoolean("Natural", natural);
			if (storage != null && !storage.isEmpty()) {
				out.store("Storage", net.minecraft.world.item.component.ItemContainerContents.CODEC,
					net.minecraft.world.item.component.ItemContainerContents.fromItems(storage.getItems()));
			}
		}

		@Override
		protected void readAdditionalSaveData(ValueInput in) {
			super.readAdditionalSaveData(in);
			natural = in.getBooleanOr("Natural", false);
			in.read("Storage", net.minecraft.world.item.component.ItemContainerContents.CODEC).ifPresent(c -> {
				storage = new net.minecraft.world.SimpleContainer((int) Math.max(9, c.itemCopies().count()));
				c.copyInto(storage.getItems());
			});
			// From summon or a spawn egg (no DefinitionApplied yet), start at full health.
			setDefinition(in.getStringOr("Definition", ""), !in.getBooleanOr("DefinitionApplied", false));
		}

		@Override
		public boolean removeWhenFarAway(double distance) {
			return natural && !isTame();
		}

		@Override
		protected Component getTypeName() {
			JsonObject def = definition();
			return def == null ? super.getTypeName() : Component.literal(def.get("name").getAsString());
		}

		@Override
		public boolean causeFallDamage(double distance, float multiplier, DamageSource source) {
			return !flying() && !movement().equals("hop") && vehicle() == null && super.causeFallDamage(distance, multiplier, source);
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
			if (isBaby()) return;
			for (ItemStack stack : rollDrops(CustomItems.strings(props(), "drops"), random)) spawnAtLocation(level, stack);
		}

		static ItemStack dropStack(String id, int count) {
			return CustomEntities.dropStack(id, count);
		}
	}

	/** Roll drops like "minecraft:bone 0-2" or "custom:ruby". Shared with custom blocks. */
	static List<ItemStack> rollDrops(List<String> drops, net.minecraft.util.RandomSource random) {
		List<ItemStack> out = new ArrayList<>();
		for (String d : drops) {
			String[] parts = d.trim().split("\\s+");
			int min = 1, max = 1;
			try {
				if (parts.length > 1) {
					String[] range = parts[1].split("-");
					min = Integer.parseInt(range[0]);
					max = range.length > 1 ? Integer.parseInt(range[1]) : min;
				}
			} catch (NumberFormatException e) {
				continue;
			}
			int n = min + (max > min ? random.nextInt(max - min + 1) : 0);
			if (n <= 0) continue;
			ItemStack stack = dropStack(parts[0], n);
			if (stack != null) out.add(stack);
		}
		return out;
	}

	/** One drop entry's item: vanilla, or custom:<id> (a creature's id gives its spawn egg). */
	static ItemStack dropStack(String id, int count) {
		if (id.startsWith("custom:")) {
			JsonObject def = CustomItems.DEFS.get(id.substring("custom:".length()));
			return def == null ? null : CustomItems.stack(def, count);
		}
		Identifier key = Identifier.tryParse(id);
		Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
		return item == null || item == net.minecraft.world.item.Items.AIR ? null : new ItemStack(item, count);
	}
}
