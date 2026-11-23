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
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import dev.claudecraft.CustomEntities;
import dev.claudecraft.EntityModel;
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
	static final Identifier WHITE = Identifier.fromNamespaceAndPath("claudecraft", "textures/item/white.png");
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
		EntityRendererRegistry.register(CustomEntities.TYPE, CustomEntityRenderer::new);
		EntityRendererRegistry.register(dev.claudecraft.CustomProjectiles.TYPE, ThrownRenderer::new);
		EntityRendererRegistry.register(dev.claudecraft.Effects.TYPE, EffectRenderer::new);
		AnimatedBlocks.register();
		CustomItems.onClientChange = blocks -> {
			MESHES.clear();
			BLOCK_MESHES.clear();
			AnimatedBlocks.clear();
			CustomEntityRenderer.PARTS.clear();
			Minecraft mc = Minecraft.getInstance();
			if (mc.level != null) {
				for (var e : mc.level.entitiesForRendering()) if (e instanceof CustomEntities.CustomEntity) e.refreshDimensions(); // new hitbox sizes
			}
			if (blocks && mc.level != null && mc.levelRenderer.viewArea() != null) {
				// Rebuild every loaded section so placed blocks pick up the new designs.
				var area = mc.levelRenderer.viewArea();
				var cam = area.getCameraSectionPos();
				int d = area.getViewDistance();
				mc.level.setSectionRangeDirty(cam.x() - d, area.minSectionY(), cam.z() - d, cam.x() + d, area.maxSectionY(), cam.z() + d);
			}
			if (mc.level != null && mc.player != null) {
				// Refill the ClaudeCraft creative tab with the current designs.
				dev.claudecraft.CustomCreativeTab.TAB.buildContents(new net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters(
					mc.level.enabledFeatures(), mc.player.canUseGameMasterBlocks(), mc.level.registryAccess()));
			}
		};
		ModelLoadingPlugin.register(ctx -> ctx.registerBlockStateResolver(CustomBlocks.BLOCK, resolver -> {
			for (BlockState st : CustomBlocks.BLOCK.getStateDefinition().getPossibleStates()) resolver.setModel(st, new VoxelBlockModel.Unbaked());
		}));
		ClientPlayNetworking.registerGlobalReceiver(CustomItems.DefinitionsPayload.TYPE, (payload, ctx) -> CustomItems.receive(payload));
	}

	record Mesh(List<VoxelModel.Quad> opaque, List<VoxelModel.Quad> translucent) {
		static Mesh of(VoxelModel m) {
			return split(m.mesh(false));
		}

		static Mesh split(List<VoxelModel.Quad> all) {
			return new Mesh(all.stream().filter(q -> !q.translucent()).toList(), all.stream().filter(VoxelModel.Quad::translucent).toList());
		}
	}

	static Mesh missingMesh() {
		return MISSING;
	}

	/** Draw a mesh at the current pose with entity lighting: opaque faces, then see-through ones. */
	static void submitMesh(PoseStack pose, SubmitNodeCollector collector, Mesh m, int light, int overlay) {
		if (!m.opaque().isEmpty()) VoxelRenderer.submit(pose, collector, RenderTypes.entityCutout(WHITE), m.opaque(), light, overlay);
		if (!m.translucent().isEmpty()) VoxelRenderer.submit(pose, collector, RenderTypes.entityTranslucent(WHITE), m.translucent(), light, overlay);
	}

	private static Mesh mesh(String id) {
		if (id == null) return MISSING;
		JsonObject def = CustomItems.DEFS.get(id);
		if (def == null) return MISSING;
		// Animated designs move in the hand and inventory too: one cached mesh per frame.
		int frame = 0;
		if (def.has("model") && def.get("model").isJsonObject()) {
			Minecraft mc = Minecraft.getInstance();
			long time = mc.level != null ? mc.level.getGameTime() : System.currentTimeMillis() / 50;
			frame = VoxelModel.frameAt(def.getAsJsonObject("model"), time, 0);
		}
		int f = frame;
		return MESHES.computeIfAbsent(f == 0 ? id : id + "#" + f, k -> {
			try {
				if (def.has("kind") && def.get("kind").getAsString().equals("entity")) return Mesh.of(spawnEgg(id));
				return Mesh.of(VoxelModel.parse(def.getAsJsonObject("model"), f));
			} catch (RuntimeException e) {
				return MISSING; // the server validates models, so this only happens with a corrupt save
			}
		});
	}

	/** A spawn egg in the creature's two most common colors: the main color with spots of the second. */
	static VoxelModel spawnEgg(String id) {
		EntityModel m = CustomEntities.model(id);
		Map<Integer, Integer> counts = new java.util.HashMap<>();
		if (m != null) for (int c : m.flatten()) if ((c >>> 24) != 0) counts.merge(c | 0xFF000000, 1, Integer::sum);
		List<Integer> byCount = counts.entrySet().stream().sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
		int base = byCount.isEmpty() ? 0xFF9A9A9A : byCount.get(0);
		int spots = byCount.size() > 1 ? byCount.get(1) : shade(base, 0.7f);
		String[] rows = {
			"................", "......kkkk......", ".....kbbbbk.....", "....kbbsbbbk....", "....kbbbbbbk....", "...kbsbbbbsbk...",
			"...kbbbbbbbbk...", "...kbbbbsbbbk...", "..kbbsbbbbbbbk..", "..kbbbbbbbsbbk..", "..kbbbbbbbbbbk..", "..kbsbbbsbbbbk..",
			"...kbbbbbbbbk...", "....kbbbbbbk....", ".....kkkkkk.....", "................"};
		com.google.gson.JsonObject palette = new com.google.gson.JsonObject();
		palette.addProperty("b", hex(base));
		palette.addProperty("s", hex(spots));
		palette.addProperty("k", hex(shade(base, 0.55f)));
		com.google.gson.JsonArray pixels = new com.google.gson.JsonArray();
		for (String r : rows) pixels.add(r);
		return VoxelModel.parse(VoxelModel.fromPixels(palette, pixels, 1));
	}

	private static int shade(int argb, float f) {
		int r = (int) (((argb >> 16) & 0xFF) * f), g = (int) (((argb >> 8) & 0xFF) * f), b = (int) ((argb & 0xFF) * f);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static String hex(int argb) {
		return String.format(java.util.Locale.ROOT, "#%06X", argb & 0xFFFFFF);
	}

	/** Draws a custom item's voxel model inside the item's unit cube, with normal lighting. */
	static final class VoxelRenderer implements SpecialModelRenderer<String> {
		private static final float[] U = {0, 1, 1, 0}, V = {1, 1, 0, 0};

		@Override
		public void submit(String id, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outlineColor) {
			submitMesh(pose, collector, mesh(id), light, overlay);
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

	/** VoxelModel's face order as directions. */
	static final Direction[] DIRS = {Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN, Direction.SOUTH, Direction.NORTH};

	/** Turn a side the way a block model is turned (its south side faces `facing`). */
	static Direction turn(Direction d, Direction facing) {
		if (d.getAxis().isVertical()) return d;
		return switch (facing) {
			case EAST -> d.getCounterClockWise();
			case WEST -> d.getClockWise();
			case NORTH -> d.getOpposite();
			default -> d;
		};
	}

	/**
	 * Model for one custom_block state: looks up its variant's definition each time a chunk is built. Animated
	 * designs put only their unchanging faces here; AnimatedBlocks draws the rest.
	 */
	static final class VoxelBlockModel implements BlockStateModel {
		private static final float[] U = {0, 1, 1, 0}, V = {1, 1, 0, 0};
		private final int variant;
		private final Direction facing;
		private final Material.Baked white;

		VoxelBlockModel(int variant, Direction facing, Material.Baked white) {
			this.variant = variant;
			this.facing = facing;
			this.white = white;
		}

		private Direction turn(Direction d) {
			return ClaudeCraftClient.turn(d, facing);
		}

		@Override
		public void emitQuads(QuadEmitter e, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
			String id = CustomBlocks.idForSlot(variant);
			AnimatedBlocks.Anim anim = AnimatedBlocks.anim(id);
			if (anim != null) AnimatedBlocks.seen(pos);
			for (VoxelModel.Quad q : anim != null ? anim.fixed() : blockMesh(id)) {
				if (q.boundary() >= 0 && cullTest.test(turn(DIRS[q.boundary()]))) continue; // hidden by the neighboring block
				float[] c = q.corners();
				for (int k = 0; k < 4; k++) {
					double[] xz = CustomBlocks.rotate(c[k * 3], c[k * 3 + 2], facing);
					e.pos(k, (float) xz[0], c[k * 3 + 1], (float) xz[1]).color(k, q.argb()).uv(k, U[k], V[k]);
				}
				e.materialBake(white, MutableQuadView.BAKE_NORMALIZED);
				e.nominalFace(turn(DIRS[q.face()]));
				if (q.diagonal()) { // plants and fire: no side shading or corner shadows
					e.shadeDirectionOverride(Direction.UP);
					e.ambientOcclusion(net.fabricmc.fabric.api.util.TriState.FALSE);
				}
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
				AnimatedBlocks.white = white.sprite();
				return new VoxelBlockModel(state.getValue(CustomBlocks.VARIANT), state.getValue(CustomBlocks.FACING), white);
			}

			@Override
			public Object visualEqualityGroup(BlockState state) {
				// Light and power don't change how it looks.
				return state.getValue(CustomBlocks.VARIANT) * 4 + state.getValue(CustomBlocks.FACING).get2DDataValue();
			}

			@Override
			public void resolveDependencies(ResolvableModel.Resolver resolver) {
			}
		}
	}
}
