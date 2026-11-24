package dev.claudecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.claudecraft.CustomItems;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/**
 * Draws worn custom armor (armor.worn) on anything humanoid: players, zombies, skeletons, armor stands. Each part
 * follows the body part it's attached to. Vanilla draws nothing for these items: their equipment asset has no layers.
 */
final class WornLayer<S extends HumanoidRenderState, M extends HumanoidModel<S>> extends RenderLayer<S, M> {
	WornLayer(RenderLayerParent<S, M> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, S s, float yRot, float xRot) {
		if (s.isInvisible) return;
		for (ItemStack stack : new ItemStack[]{s.headEquipment, s.chestEquipment, s.legsEquipment, s.feetEquipment}) {
			if (stack == null || stack.isEmpty() || !stack.has(DataComponents.EQUIPPABLE)) continue;
			MorphLayer.Parsed worn = MorphLayer.worn(CustomItems.definition(stack));
			if (worn == null) continue;
			for (MorphLayer.Part p : worn.parts()) MorphLayer.draw(pose, collector, light, OverlayTexture.NO_OVERLAY, p, null, false, getParentModel(), s, false);
		}
	}
}
