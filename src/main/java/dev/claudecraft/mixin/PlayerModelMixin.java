package dev.claudecraft.mixin;

import dev.claudecraft.client.MorphLayer;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the vanilla body parts a player's morph replaces. setupAnim runs again right before each player is drawn. */
@Mixin(PlayerModel.class)
abstract class PlayerModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void claudecraft$hideMorphedParts(AvatarRenderState state, CallbackInfo ci) {
		MorphLayer.hideParts((PlayerModel) (Object) this, state);
	}
}
