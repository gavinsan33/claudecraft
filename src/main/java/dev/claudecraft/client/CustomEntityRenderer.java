package dev.claudecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.claudecraft.CustomEntities;
import dev.claudecraft.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws custom creatures from their voxel parts, animating each part by role: heads follow the look direction,
 * legs and arms swing with the walk cycle, wings flap (faster in the air), tails sway. Hurt and death look vanilla.
 */
public class CustomEntityRenderer extends EntityRenderer<CustomEntities.CustomEntity, CustomEntityRenderer.State> {
	/** Part meshes per definition id, in model order. Cleared when definitions change. */
	static final Map<String, List<ClaudeCraftClient.Mesh>> PARTS = new ConcurrentHashMap<>();

	public static class State extends LivingEntityRenderState {
		String definition = "";
		float width = 0.5f;
		boolean airborne;
		/** A vehicle's lean in degrees, and the height (blocks) it leans around. */
		float roll, lean, leanAt;
		boolean sitting;
		/** Size scale: babies are half size. */
		float scale = 1;
	}

	public CustomEntityRenderer(EntityRendererProvider.Context ctx) {
		super(ctx);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(CustomEntities.CustomEntity e, State s, float pt) {
		super.extractRenderState(e, s, pt);
		s.definition = e.definitionId();
		s.bodyRot = Mth.rotLerp(pt, e.yBodyRotO, e.yBodyRot);
		s.yRot = Mth.wrapDegrees(Mth.rotLerp(pt, e.yHeadRotO, e.yHeadRot) - s.bodyRot);
		s.xRot = e.getXRot(pt);
		s.walkAnimationPos = e.walkAnimation.position(pt);
		s.walkAnimationSpeed = e.walkAnimation.speed(pt);
		s.deathTime = e.deathTime > 0 ? e.deathTime + pt : 0;
		s.hasRedOverlay = e.hurtTime > 0 || e.deathTime > 0;
		s.width = e.getBbWidth();
		s.airborne = !e.onGround() && !e.isPassenger();
		s.roll = Mth.lerp(pt, e.rollO, e.roll);
		s.lean = Mth.lerp(pt, e.leanO, e.lean);
		s.leanAt = e.getBbHeight() / 2;
		s.sitting = e.isInSittingPose();
		s.scale = e.getScale() * e.getAgeScale();
	}

	@Override
	protected float getShadowRadius(State s) {
		return s.width * 0.5f;
	}

	@Override
	public void submit(State s, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		EntityModel model = CustomEntities.model(s.definition);
		int overlay = net.minecraft.client.renderer.entity.LivingEntityRenderer.getOverlayCoords(s, 0);
		pose.pushPose();
		pose.rotateDegrees(Axis.YP, (-s.bodyRot)); // the model's front (+z) faces where the creature faces
		if (s.roll != 0 || s.lean != 0) {
			// Vehicles bank toward where they drift and tip toward where they're going, around their middle.
			pose.translate(0, s.leanAt, 0);
			pose.rotateDegrees(Axis.ZP, s.roll);
			pose.rotateDegrees(Axis.XP, s.lean);
			pose.translate(0, -s.leanAt, 0);
		}
		pose.scale(s.scale, s.scale, s.scale);
		if (s.sitting && model != null) {
			pose.translate(0, -model.height() * 0.2f, 0); // settle down; legs stay still
			s.walkAnimationSpeed = 0;
		}
		if (s.deathTime > 0) {
			float f = Math.min(1, Mth.sqrt((s.deathTime - 1) / 20f * 1.6f));
			pose.rotateDegrees(Axis.ZP, (f * 90)); // tip over, like vanilla mobs
		}
		if (model == null) {
			pose.translate(-0.5f, 0, -0.5f);
			ClaudeCraftClient.submitMesh(pose, collector, ClaudeCraftClient.missingMesh(), s.lightCoords, overlay);
		} else {
			List<ClaudeCraftClient.Mesh> meshes = PARTS.computeIfAbsent(s.definition, k -> {
				List<ClaudeCraftClient.Mesh> out = new ArrayList<>();
				for (EntityModel.Part p : model.parts) out.add(ClaudeCraftClient.Mesh.split(model.mesh(p)));
				return out;
			});
			for (int i = 0; i < model.parts.size() && i < meshes.size(); i++) {
				EntityModel.Part p = model.parts.get(i);
				pose.pushPose();
				pose.translate(p.pivot()[0] / model.scale, p.pivot()[1] / model.scale, p.pivot()[2] / model.scale);
				animate(pose, p, s);
				ClaudeCraftClient.submitMesh(pose, collector, meshes.get(i), s.lightCoords, overlay);
				pose.popPose();
			}
		}
		pose.popPose();
		super.submit(s, pose, collector, camera); // name tag
	}

	private static void animate(PoseStack pose, EntityModel.Part p, State s) {
		animate(pose, p.role(), p.phase(), p.pivot()[0], s, s.airborne);
	}

	/** Turn a part by its role at its pivot: heads look, legs and arms swing, wings flap, tails sway. Morphs use it too. */
	static void animate(PoseStack pose, String role, int phase, float pivotX, net.minecraft.client.renderer.entity.state.LivingEntityRenderState s, boolean airborne) {
		float walk = s.walkAnimationPos * 0.6662f, speed = Math.min(1, s.walkAnimationSpeed), age = s.ageInTicks;
		switch (role) {
			case "head" -> {
				pose.rotateDegrees(Axis.YP, (-s.yRot));
				pose.rotateDegrees(Axis.XP, (s.xRot));
			}
			case "leg" -> pose.rotate(Axis.XP, (Mth.cos(walk + phase * Mth.PI) * 1.4f * speed));
			case "arm" -> pose.rotate(Axis.XP, (Mth.cos(walk + (1 - phase) * Mth.PI) * speed));
			case "wing" -> {
				float side = pivotX >= 0 ? 1 : -1;
				float flap = airborne ? Mth.sin(age * 1.3f) * 0.8f : Mth.sin(age * 0.15f) * 0.08f;
				pose.rotate(Axis.ZP, (flap * side));
			}
			case "tail" -> pose.rotate(Axis.YP, (Mth.sin(age * 0.2f) * 0.25f + Mth.cos(walk) * 0.4f * speed));
			default -> {}
		}
	}
}
