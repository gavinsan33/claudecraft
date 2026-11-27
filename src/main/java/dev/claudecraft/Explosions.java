package dev.claudecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * claude explode: a real explosion of any power, for Claude's commands and items', blocks' and creatures' hooks
 * (vanilla has no command for one). It can break blocks like TNT, leave them alone, or blast some of them out as flying
 * debris that lands as blocks around the crater; shatter stone like dirt for real craters; set fires; skip damage but
 * still knock things back; and spare whoever set it off.
 */
final class Explosions {
	private Explosions() {}

	static final Set<String> MODES = Set.of("break", "debris", "keep");
	static final float MAX_POWER = 24;
	private static final int MAX_DEBRIS = 250;

	/**
	 * Explode at a point. mode: break (like TNT), debris (break, and fling about a third of the blocks outward as
	 * falling blocks), keep (no block damage). harmless: knockback only. source is spared, and credited for the damage.
	 */
	static int explode(ServerLevel level, Vec3 at, float power, String mode, boolean fire, boolean harmless, float knockback, boolean shatter, Entity source) {
		List<BlockPos> flung = new ArrayList<>();
		java.util.Map<BlockPos, Boolean> decided = new java.util.HashMap<>();
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		ExplosionDamageCalculator calc = new ExplosionDamageCalculator() {
			@Override
			public java.util.Optional<Float> getBlockExplosionResistance(Explosion e, BlockGetter g, BlockPos pos, BlockState state, net.minecraft.world.level.material.FluidState fluid) {
				// Shatter: stone and the like give way like dirt, for big craters; obsidian and tougher still hold.
				var normal = super.getBlockExplosionResistance(e, g, pos, state, fluid);
				return shatter && normal.isPresent() && normal.get() < 100 ? java.util.Optional.of(Math.min(normal.get(), 0.5f)) : normal;
			}

			@Override
			public boolean shouldBlockExplode(Explosion e, BlockGetter g, BlockPos pos, BlockState state, float pow) {
				if (!super.shouldBlockExplode(e, g, pos, state, pow)) return false;
				// Debris: keep some of the solid blocks out of the blast, to throw them ourselves afterwards. Each ray that
				// reaches a block asks again, so decide once per block.
				if (!mode.equals("debris")) return true;
				return !decided.computeIfAbsent(pos.immutable(), p -> {
					boolean fling = flung.size() < MAX_DEBRIS && state.isSolidRender() && !state.hasBlockEntity() && rnd.nextFloat() < 0.35f;
					if (fling) flung.add(p);
					return fling;
				});
			}

			@Override
			public boolean shouldDamageEntity(Explosion e, Entity entity) {
				return !harmless && entity != source && super.shouldDamageEntity(e, entity);
			}

			@Override
			public float getKnockbackMultiplier(Entity entity) {
				return entity == source ? 0 : super.getKnockbackMultiplier(entity) * knockback;
			}
		};
		Level.ExplosionInteraction how = mode.equals("keep") ? Level.ExplosionInteraction.NONE : Level.ExplosionInteraction.TNT;
		level.explode(source, null, calc, at.x, at.y, at.z, power, fire, how);
		int thrown = 0;
		for (BlockPos pos : flung) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) continue;
			// It needs open air to fly out into: above it, or toward the blast. Boxed in, it's just blown away.
			Vec3 toward = at.subtract(Vec3.atCenterOf(pos));
			BlockPos inward = pos.offset((int) Math.signum(Math.round(toward.x)), (int) Math.signum(Math.round(toward.y)), (int) Math.signum(Math.round(toward.z)));
			Vec3 exit = level.getBlockState(pos.above()).isAir() ? new Vec3(0, 0.6, 0)
				: !inward.equals(pos) && level.getBlockState(inward).isAir() ? Vec3.atLowerCornerOf(inward.subtract(pos)).scale(0.6) : null;
			if (exit == null) {
				level.removeBlock(pos, false);
				continue;
			}
			FallingBlockEntity debris = FallingBlockEntity.fall(level, pos, state);
			debris.setPos(debris.position().add(exit));
			thrown++;
			debris.dropItem = false; // it lands as a block, or is lost like any blown-up block
			Vec3 out = Vec3.atCenterOf(pos).subtract(at);
			double dist = Math.max(0.5, out.length());
			double speed = (0.35 + rnd.nextDouble() * 0.5) * Math.min(2, power / 4) / Math.sqrt(dist);
			out = out.normalize().scale(speed);
			debris.setDeltaMovement(out.x + (rnd.nextDouble() - 0.5) * 0.2, Math.abs(out.y) + 0.3 + rnd.nextDouble() * 0.5, out.z + (rnd.nextDouble() - 0.5) * 0.2);
			debris.needsSync = true;
		}
		return thrown;
	}
}
