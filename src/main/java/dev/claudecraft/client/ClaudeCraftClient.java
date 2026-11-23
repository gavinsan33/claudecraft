package dev.claudecraft.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import dev.claudecraft.CustomBlocks;
import dev.claudecraft.CustomItems;
import dev.claudecraft.VoxelModel;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
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
import java.util.function.Predicate;

/**
 * Client half of custom items and blocks: receives definitions from the server and draws their voxel models.
 * Items: the item model files (assets/claudecraft/items/) pick how it's held from the definition's style, then
 * hand drawing to the "claudecraft:voxel" special renderer below. Blocks: every custom_block state gets a model
 * that emits its definition's quads straight into the chunk mesh, so placed blocks cost what vanilla blocks do.
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
	/** Placed-block quads by definition id (sitting on the floor, unscaled). Read from chunk-building threads. */
	private static final Map<String, List<VoxelModel.Quad>> BLOCK_MESHES = new ConcurrentHashMap<>();
	private static final List<VoxelModel.Quad> MISSING_BLOCK = missingModel().mesh(true);

	@Override
	public void onInitializeClient() {
		SpecialModelRenderers.ID_MAPPER.put(Identifier.fromNamespaceAndPath("claudecraft", "voxel"), VoxelRenderer.Unbaked.MAP_CODEC);
		SelectItemModelProperties.ID_MAPPER.put(Identifier.fromNamespaceAndPath("claudecraft", "style"), StyleProperty.TYPE);
		CustomItems.onClientChange = blocks -> {
			MESHES.clear();
			BLOCK_MESHES.clear();
			Minecraft mc = Minecraft.getInstance();
			if (blocks && mc.level != null && mc.levelRenderer.viewArea() != null) {
				// Rebuild every loaded section so placed blocks pick up the new designs.
				var area = mc.levelRenderer.viewArea();
				var cam = area.getCameraSectionPos();
				int d = area.getViewDistance();
				mc.level.setSectionRangeDirty(cam.x() - d, area.minSectionY(), cam.z() - d, cam.x() + d, area.maxSectionY(), cam.z() + d);
			}
		};
		ModelLoadingPlugin.register(ctx -> ctx.registerBlockStateResolver(CustomBlocks.BLOCK, resolver -> {
			for (BlockState st : CustomBlocks.BLOCK.getStateDefinition().getPossibleStates()) resolver.setModel(st, new VoxelBlockModel.Unbaked());
		}));
		ClientPlayNetworking.registerGlobalReceiver(CustomItems.DefinitionsPayload.TYPE, (payload, ctx) -> CustomItems.receive(payload));
	}

	record Mesh(List<VoxelModel.Quad> opaque, List<VoxelModel.Quad> translucent) {
		static Mesh of(VoxelModel m) {
			List<VoxelModel.Quad> all = m.mesh(false);
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
			JsonObject def = CustomItems.definition(stack); // block drops name their definition only by variant
			return def != null ? def.get("id").getAsString() : stack.get(CustomItems.DEFINITION);
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

	private static List<VoxelModel.Quad> blockMesh(String id) {
		JsonObject def = id == null ? null : CustomItems.DEFS.get(id);
		if (def == null) return MISSING_BLOCK;
		return BLOCK_MESHES.computeIfAbsent(id, k -> {
			try {
				return VoxelModel.parse(def.getAsJsonObject("model")).mesh(true);
			} catch (RuntimeException e) {
				return MISSING_BLOCK;
			}
		});
	}

	/** Model for one custom_block state: looks up its variant's definition each time a chunk is built. */
	static final class VoxelBlockModel implements BlockStateModel {
		private static final Direction[] DIRS = {Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN, Direction.SOUTH, Direction.NORTH};
		private static final float[] U = {0, 1, 1, 0}, V = {1, 1, 0, 0};
		private final int variant;
		private final Material.Baked white;

		VoxelBlockModel(int variant, Material.Baked white) {
			this.variant = variant;
			this.white = white;
		}

		@Override
		public void emitQuads(QuadEmitter e, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
			for (VoxelModel.Quad q : blockMesh(CustomBlocks.idForSlot(variant))) {
				if (q.boundary() >= 0 && cullTest.test(DIRS[q.boundary()])) continue; // hidden by the neighboring block
				float[] c = q.corners();
				for (int k = 0; k < 4; k++) e.pos(k, c[k * 3], c[k * 3 + 1], c[k * 3 + 2]).color(k, q.argb()).uv(k, U[k], V[k]);
				e.materialBake(white, MutableQuadView.BAKE_NORMALIZED);
				e.nominalFace(DIRS[q.face()]);
				e.chunkLayer(q.translucent() ? ChunkSectionLayer.TRANSLUCENT : ChunkSectionLayer.SOLID);
				e.emit();
			}
		}

		@Override
		public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
			// Everything is emitted by emitQuads.
		}

		@Override
		public Material.Baked particleMaterial() {
			return white;
		}

		@Override
		public int materialFlags() {
			return 0;
		}

		record Unbaked() implements BlockStateModel.UnbakedRoot {
			@Override
			public BlockStateModel bake(BlockState state, ModelBaker baker) {
				Material.Baked white = baker.materials().get(new Material(Identifier.fromNamespaceAndPath("claudecraft", "block/white")), () -> "claudecraft:custom_block");
				return new VoxelBlockModel(state.getValue(CustomBlocks.VARIANT), white);
			}

			@Override
			public Object visualEqualityGroup(BlockState state) {
				return state.getValue(CustomBlocks.VARIANT); // light doesn't change how it looks
			}

			@Override
			public void resolveDependencies(ResolvableModel.Resolver resolver) {
			}
		}
	}
}
