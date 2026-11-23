package dev.claudecraft.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.claudecraft.CustomItems;
import dev.claudecraft.CustomProjectiles;
import dev.claudecraft.Effects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector3f;

/** Thrown items, plus the effect they carry as they fly: an orb around them, sparks, or a beam streaking behind. */
public class ThrownRenderer extends ThrownItemRenderer<CustomProjectiles.Thrown> {
	public static class State extends ThrownItemRenderState {
		String trail = "";
		final Vector3f behind = new Vector3f();
		float age;
		int seed;
	}

	public ThrownRenderer(EntityRendererProvider.Context ctx) {
		super(ctx);
	}

	@Override
	public ThrownItemRenderState createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(CustomProjectiles.Thrown e, ThrownItemRenderState s, float pt) {
		super.extractRenderState(e, s, pt);
		if (s instanceof State st) {
			st.trail = e.trail();
			var v = e.getDeltaMovement();
			st.behind.set((float) -v.x * 3, (float) -v.y * 3, (float) -v.z * 3); // a beam part trails three ticks of flight
			st.age = e.tickCount + pt;
			st.seed = e.getId();
		}
	}

	@Override
	protected boolean affectedByCulling(CustomProjectiles.Thrown e) {
		return e.trail().isEmpty();
	}

	@Override
	public void submit(ThrownItemRenderState s, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		super.submit(s, pose, collector, camera);
		if (!(s instanceof State st) || st.trail.isEmpty()) return;
		JsonObject def = CustomItems.DEFS.get(st.trail);
		if (!Effects.isEffect(def)) return;
		Vector3f cam = new Vector3f((float) (camera.pos.x - s.x), (float) (camera.pos.y - s.y), (float) (camera.pos.z - s.z));
		EffectRenderer.draw(def, st.behind, st.age, st.seed, true, cam, pose, collector);
	}
}
