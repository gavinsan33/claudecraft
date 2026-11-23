package dev.claudecraft.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import dev.claudecraft.CustomItems;
import dev.claudecraft.VoxelModel;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperties;
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperty;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Client half of custom items: receives definitions from the server and draws each item's voxel model. The
 * item's model file (assets/claudecraft/items/custom_item.json) picks how it's held from the definition's style,
 * then hands drawing to the "claudecraft:voxel" special renderer below.
 */
public class ClaudeCraftClient implements ClientModInitializer {
	private static final Identifier WHITE = Identifier.fromNamespaceAndPath("claudecraft", "textures/item/white.png");
	/** Shown for a stack whose definition this client doesn't have (yet). */
	private static final Mesh MISSING = Mesh.of(missingModel());

	/** An 8x8x8 magenta and black checkerboard cube, like Minecraft's missing texture. */
	private static VoxelModel missingModel() {
		StringBuilder layers = new StringBuilder("[");
		for (int y = 0; y < 8; y++) {
			layers.append(y > 0 ? ",[" : "[");
			for (int z = 0; z < 8; z++) {
				StringBuilder row = new StringBuilder();
				for (int x = 0; x < 8; x++) row.append(((x >> 2) + (y >> 2) + (z >> 2)) % 2 == 0 ? 'm' : 'k');
				layers.append(z > 0 ? "," : "").append('"').append(row).append('"');
			}
			layers.append(']');
		}
		return VoxelModel.parse(JsonParser.parseString(
			"{\"palette\":{\"m\":\"#F800F8\",\"k\":\"#000000\"},\"layers\":" + layers + "]}").getAsJsonObject());
	}

	/** Meshes by definition id, built the first time an item is drawn and dropped when definitions change. */
	private static final Map<String, Mesh> MESHES = new ConcurrentHashMap<>();

	@Override
	public void onInitializeClient() {
		SpecialModelRenderers.ID_MAPPER.put(Identifier.fromNamespaceAndPath("claudecraft", "voxel"), VoxelRenderer.Unbaked.MAP_CODEC);
		SelectItemModelProperties.ID_MAPPER.put(Identifier.fromNamespaceAndPath("claudecraft", "style"), StyleProperty.TYPE);
		CustomItems.onClientChange = MESHES::clear;
		ClientPlayNetworking.registerGlobalReceiver(CustomItems.DefinitionsPayload.TYPE, (payload, ctx) -> CustomItems.receive(payload));
	}

	record Mesh(List<VoxelModel.Quad> opaque, List<VoxelModel.Quad> translucent) {
		static Mesh of(VoxelModel m) {
			List<VoxelModel.Quad> all = m.mesh();
			return new Mesh(all.stream().filter(q -> !q.translucent()).toList(), all.stream().filter(VoxelModel.Quad::translucent).toList());
		}
	}

	private static Mesh mesh(String id) {
		if (id == null) return MISSING;
		JsonObject def = CustomItems.DEFS.get(id);
		if (def == null) return MISSING;
		return MESHES.computeIfAbsent(id, k -> {
			try {
				return Mesh.of(VoxelModel.parse(def.getAsJsonObject("model")));
			} catch (RuntimeException e) {
				return MISSING; // the server validates models, so this only happens with a corrupt save
			}
		});
	}

	/** Draws a custom item's voxel model inside the item's unit cube, with normal lighting. */
	static final class VoxelRenderer implements SpecialModelRenderer<String> {
		private static final float[] U = {0, 1, 1, 0}, V = {1, 1, 0, 0};

		@Override
		public void submit(String id, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outlineColor) {
			Mesh m = mesh(id);
			if (!m.opaque().isEmpty()) submit(pose, collector, RenderTypes.entityCutout(WHITE), m.opaque(), light, overlay);
			if (!m.translucent().isEmpty()) submit(pose, collector, RenderTypes.entityTranslucent(WHITE), m.translucent(), light, overlay);
		}

		private static void submit(PoseStack pose, SubmitNodeCollector collector, RenderType type, List<VoxelModel.Quad> quads, int light, int overlay) {
			collector.submitCustomGeometry(pose, type, (p, vc) -> {
				for (VoxelModel.Quad q : quads) {
					float[] c = q.corners();
					for (int k = 0; k < 4; k++) {
						vc.addVertex(p, c[k * 3], c[k * 3 + 1], c[k * 3 + 2]).setColor(q.argb()).setUv(U[k], V[k])
							.setOverlay(overlay).setLight(light).setNormal(p, q.nx(), q.ny(), q.nz());
					}
				}
			});
		}

		@Override
		public void getExtents(Consumer<Vector3fc> out) {
			for (int i = 0; i < 8; i++) out.accept(new Vector3f(i & 1, (i >> 1) & 1, (i >> 2) & 1));
		}

		@Override
		public String extractArgument(ItemStack stack) {
			return stack.get(CustomItems.DEFINITION);
		}

		record Unbaked() implements SpecialModelRenderer.Unbaked<String> {
			static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

			@Override
			public SpecialModelRenderer<String> bake(SpecialModelRenderer.BakingContext ctx) {
				return new VoxelRenderer();
			}

			@Override
			public MapCodec<Unbaked> type() {
				return MAP_CODEC;
			}
		}
	}

	/** "claudecraft:style" for select item models: the definition's style (item, handheld or block). */
	record StyleProperty() implements SelectItemModelProperty<String> {
		static final SelectItemModelProperty.Type<StyleProperty, String> TYPE =
			SelectItemModelProperty.Type.create(MapCodec.unit(new StyleProperty()), Codec.STRING);

		@Override
		public String get(ItemStack stack, ClientLevel level, LivingEntity entity, int seed, ItemDisplayContext ctx) {
			JsonObject def = CustomItems.definition(stack);
			return def != null && def.has("style") ? def.get("style").getAsString() : "item";
		}

		@Override
		public Codec<String> valueCodec() {
			return Codec.STRING;
		}

		@Override
		public SelectItemModelProperty.Type<StyleProperty, String> type() {
			return TYPE;
		}
	}
}
