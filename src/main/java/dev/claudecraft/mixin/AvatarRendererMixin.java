package dev.claudecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.claudecraft.client.MorphLayer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The first-person hand follows the local player's morph: no vanilla arm if the morph hides it, plus parts attached to it. */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
	@Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
	private void claudecraft$hiddenArm(PoseStack pose, SubmitNodeCollector collector, int light, Identifier skin, ModelPart arm, boolean sleeve, CallbackInfo ci) {
		PlayerModel model = claudecraft$model();
		String name = MorphLayer.armName(model, arm);
		if (!MorphLayer.firstPersonArmHidden(name)) return;
		// Pose the arm the way vanilla does for the hand, so attached parts sit where it would be, but don't draw it.
		arm.resetPose();
		arm.zRot = arm == model.rightArm ? 0.1f : -0.1f;
		MorphLayer.firstPersonParts(pose, collector, light, skin, model, name, sleeve);
		ci.cancel();
	}

	@Inject(method = "renderHand", at = @At("TAIL"))
	private void claudecraft$armParts(PoseStack pose, SubmitNodeCollector collector, int light, Identifier skin, ModelPart arm, boolean sleeve, CallbackInfo ci) {
		PlayerModel model = claudecraft$model();
		MorphLayer.firstPersonParts(pose, collector, light, skin, model, MorphLayer.armName(model, arm), sleeve);
	}

	@Unique
	private PlayerModel claudecraft$model() {
		return ((AvatarRenderer<?>) (Object) this).getModel();
	}
}
