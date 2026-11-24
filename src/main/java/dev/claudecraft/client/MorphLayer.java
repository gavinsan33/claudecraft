package dev.claudecraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.claudecraft.EntityModel;
import dev.claudecraft.Morphs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerModelType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws a player's morph: parts cut from their own skin and voxel parts, each following a vanilla body part or
 * animated by role. Vanilla parts the morph hides are hidden by PlayerModelMixin. Everything here is drawn in
 * creature space (x left, y up from the feet, z forward, 16 voxels per block), the same as define_entity.
 */
public final class MorphLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	/** Skin boxes by region, slim arms and overlay: built once and never changed, since drawing happens later. */
	private static final Map<String, ModelPart> BOXES = new ConcurrentHashMap<>();
	/** Parsed morphs by definition id. Cleared when definitions change. */
	static final Map<String, Parsed> PARSED = new ConcurrentHashMap<>();

	record Part(String skin, float[] center, float[] scale, float[] rotation, float[] pivot, String role, int phase, String attach,
		boolean overlay, ClaudeCraftClient.Mesh mesh) {}

	record Parsed(List<Part> parts, List<String> hidden) {}

	MorphLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	/** The morph the player behind a render state wears, or null. */
	static JsonObject morph(AvatarRenderState s) {
		var level = Minecraft.getInstance().level;
		Entity e = level == null ? null : level.getEntity(s.id);
		return e == null ? null : Morphs.of(e);
	}

	/** Hide the vanilla parts the morph covers. Called at the end of PlayerModel.setupAnim, right before each draw. */
	public static void hideParts(PlayerModel m, AvatarRenderState s) {
		JsonObject def = morph(s);
		List<String> hidden = def == null ? List.of() : parsed(def).hidden();
		boolean head = !hidden.contains("head"), body = !hidden.contains("body"), ra = !hidden.contains("right_arm"),
			la = !hidden.contains("left_arm"), rl = !hidden.contains("right_leg"), ll = !hidden.contains("left_leg");
		// Base parts aren't reset by vanilla, so set them both ways; overlays vanilla just set from the skin options.
		m.head.visible = head;
		m.hat.visible &= head;
		m.body.visible = body;
		m.jacket.visible &= body;
		m.rightArm.visible = ra;
		m.rightSleeve.visible &= ra;
		m.leftArm.visible = la;
		m.leftSleeve.visible &= la;
		m.rightLeg.visible = rl;
		m.rightPants.visible &= rl;
		m.leftLeg.visible = ll;
		m.leftPants.visible &= ll;
	}

	private static float[] vec(JsonObject p, String key, float[] dflt) {
		if (!p.has(key)) return dflt;
		JsonArray a = p.getAsJsonArray(key);
		return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
	}

	static Parsed parsed(JsonObject def) {
		return PARSED.computeIfAbsent(def.get("id").getAsString(), k -> {
			List<Part> out = new ArrayList<>();
			JsonObject vm = Morphs.voxelModel(def);
			EntityModel voxels = vm == null ? null : EntityModel.parse(vm, 16);
			int v = 0;
			for (JsonElement e : def.getAsJsonArray("parts")) {
				JsonObject p = e.getAsJsonObject();
				String role = p.has("role") ? p.get("role").getAsString() : "none";
				int phase = p.has("phase") ? p.get("phase").getAsInt() : 0;
				String attach = p.has("attach") ? p.get("attach").getAsString() : null;
				float[] rot = vec(p, "rotation", new float[3]);
				if (p.has("skin")) {
					Morphs.Region r = Morphs.REGIONS.get(p.get("skin").getAsString());
					float[] scale = vec(p, "scale", new float[]{1, 1, 1});
					float[] center = vec(p, "center", new float[]{r.cx(), r.cy(), r.cz()});
					float[] pivot = vec(p, "pivot", new float[]{center[0], center[1] + r.h() * scale[1] / 2, center[2]});
					boolean overlay = !p.has("overlay") || p.get("overlay").getAsBoolean();
					out.add(new Part(p.get("skin").getAsString(), center, scale, rot, pivot, role, phase, attach, overlay, null));
				} else {
					EntityModel.Part vp = voxels.parts.get(v++);
					out.add(new Part(null, null, null, rot, vp.pivot(), role, phase, attach, false, ClaudeCraftClient.Mesh.split(voxels.mesh(vp))));
				}
			}
			return new Parsed(out, Morphs.hidden(def));
		});
	}

	/** A skin region's box, centered on its own origin, in model orientation (y down, face toward -z). */
	private static ModelPart box(Morphs.Region r, String name, boolean slim, boolean overlay) {
		return BOXES.computeIfAbsent(name + (slim ? "/slim" : "") + (overlay ? "/overlay" : ""), k -> {
			int w = slim && name.endsWith("_arm") ? 3 : r.w();
			MeshDefinition mesh = new MeshDefinition();
			mesh.getRoot().addOrReplaceChild("box", CubeListBuilder.create().texOffs(overlay ? r.ou() : r.u(), overlay ? r.ov() : r.v())
				.addBox(-w / 2f, -r.h() / 2f, -r.d() / 2f, w, r.h(), r.d(), overlay ? new CubeDeformation(r.inflate()) : CubeDeformation.NONE), PartPose.ZERO);
			return LayerDefinition.create(mesh, 64, 64).bakeRoot().getChild("box");
		});
	}

	private static ModelPart vanilla(PlayerModel m, String name) {
		return switch (name) {
			case "head" -> m.head;
			case "body" -> m.body;
			case "right_arm" -> m.rightArm;
			case "left_arm" -> m.leftArm;
			case "right_leg" -> m.rightLeg;
			default -> m.leftLeg;
		};
	}

	private static boolean overlayShown(String skin, AvatarRenderState s) {
		return switch (skin) {
			case "head" -> s.showHat;
			case "body" -> s.showJacket;
			case "right_arm" -> s.showRightSleeve;
			case "left_arm" -> s.showLeftSleeve;
			case "right_leg" -> s.showRightPants;
			default -> s.showLeftPants;
		};
	}

	private static Parsed safeParsed(JsonObject def) {
		try {
			return parsed(def);
		} catch (RuntimeException e) {
			return null; // the server validates morphs, so only a corrupt save gets here
		}
	}

	@Override
	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState s, float yRot, float xRot) {
		JsonObject def = morph(s);
		Parsed m = def == null || s.isInvisible ? null : safeParsed(def);
		if (m == null) return;
		var skinType = RenderTypes.entityTranslucent(s.skin.body().texturePath());
		boolean slim = s.skin.model() == PlayerModelType.SLIM;
		int overlay = LivingEntityRenderer.getOverlayCoords(s, 0);
		for (Part p : m.parts()) draw(pose, collector, light, overlay, p, skinType, slim, getParentModel(), s, p.skin() != null && overlayShown(p.skin(), s));
	}

	public static String armName(PlayerModel model, ModelPart arm) {
		return arm == model.rightArm ? "right_arm" : "left_arm";
	}

	/** First person: whether the local player's morph hides this arm, so the vanilla hand shouldn't be drawn. */
	public static boolean firstPersonArmHidden(String arm) {
		var player = Minecraft.getInstance().player;
		JsonObject def = player == null ? null : Morphs.of(player);
		Parsed m = def == null ? null : safeParsed(def);
		return m != null && m.hidden().contains(arm);
	}

	/** First person: draw the local player's morph parts that are attached to this arm, where the hand is. */
	public static void firstPersonParts(PoseStack pose, SubmitNodeCollector collector, int light, net.minecraft.resources.Identifier skin, PlayerModel model,
			String arm, boolean sleeve) {
		var player = Minecraft.getInstance().player;
		JsonObject def = player == null ? null : Morphs.of(player);
		Parsed m = def == null ? null : safeParsed(def);
		if (m == null) return;
		boolean slim = player.getSkin().model() == PlayerModelType.SLIM;
		var still = new AvatarRenderState(); // no walk cycle or head turn in first person
		for (Part p : m.parts()) {
			if (arm.equals(p.attach())) draw(pose, collector, light, OverlayTexture.NO_OVERLAY, p, RenderTypes.entityTranslucent(skin), slim, model, still, sleeve);
		}
	}

	/** One morph part, in the pose of the player model (feet at 1.5 blocks, y down, facing -z). */
	private static void draw(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, Part p, net.minecraft.client.renderer.rendertype.RenderType skinType,
			boolean slim, PlayerModel model, AvatarRenderState s, boolean showOverlay) {
		pose.pushPose();
		if (p.attach() != null) {
			// Follow the vanilla part: its current turn about its pivot (and any shift, like sneaking).
			Morphs.Region a = Morphs.REGIONS.get(p.attach());
			vanilla(model, p.attach()).translateAndRotate(pose);
			pose.translate(-a.px() / 16, -a.py() / 16, -a.pz() / 16);
		}
		// Model space (y down, feet at 1.5 blocks, facing -z) to creature space (y up from the feet, facing +z).
		pose.translate(0, 1.5f, 0);
		pose.scale(1, -1, -1);
		pose.translate(p.pivot()[0] / 16, p.pivot()[1] / 16, p.pivot()[2] / 16);
		CustomEntityRenderer.animate(pose, p.role(), p.phase(), p.pivot()[0], s, s.isFallFlying);
		pose.rotateDegrees(Axis.YP, p.rotation()[1]);
		pose.rotateDegrees(Axis.XP, p.rotation()[0]);
		pose.rotateDegrees(Axis.ZP, p.rotation()[2]);
		if (p.skin() != null) {
			Morphs.Region r = Morphs.REGIONS.get(p.skin());
			pose.translate((p.center()[0] - p.pivot()[0]) / 16, (p.center()[1] - p.pivot()[1]) / 16, (p.center()[2] - p.pivot()[2]) / 16);
			pose.scale(p.scale()[0], p.scale()[1], p.scale()[2]);
			pose.scale(1, -1, -1); // back to model orientation, so the skin's face looks forward and its top up
			collector.submitModelPart(box(r, p.skin(), slim, false), pose, skinType, light, overlay, null);
			if (p.overlay() && showOverlay) collector.submitModelPart(box(r, p.skin(), slim, true), pose, skinType, light, overlay, null);
		} else {
			ClaudeCraftClient.submitMesh(pose, collector, p.mesh(), light, overlay);
		}
		pose.popPose();
	}
}
