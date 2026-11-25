package dev.claudecraft.mixin;

import dev.claudecraft.CustomEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Riding something with its own inventory screen, the inventory key asks the server to open that instead of yours.
 * Every custom creature has one (one class for all), so only hand it over when this one actually has storage.
 */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
	@Inject(method = "isServerControlledInventory", at = @At("RETURN"), cancellable = true)
	private void claudecraft$onlyWithStorage(CallbackInfoReturnable<Boolean> cir) {
		var player = Minecraft.getInstance().player;
		if (cir.getReturnValue() && player != null && player.getVehicle() instanceof CustomEntities.CustomEntity ce && ce.storageSlots() <= 0) {
			cir.setReturnValue(false);
		}
	}
}
