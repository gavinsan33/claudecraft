package dev.claudecraft.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.claudecraft.CustomBlocks;
import dev.claudecraft.CustomItems;
import dev.claudecraft.VoxelModel;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Animated custom blocks, like fire and portals. Chunk meshes are built once and kept, so they can't move: an
 * animated design's faces that are the same in every frame go into the chunk like any block's, and the rest are
 * drawn here every frame, for the frame the game clock is on.
 *
 * Which blocks to draw: chunk building reports every animated block it meets (emitQuads runs for each block in a
 * section), and each frame those positions are checked again, so blocks that were broken, unloaded or redesigned
 * drop out on their own.
 */
final class AnimatedBlocks {
	private AnimatedBlocks() {}

	/** Moving faces aren't drawn farther away than this (or the render distance). */
	private static final double MAX_DISTANCE = 128;

	/** A design's animation: the faces every frame shares, and each frame's other faces. */
	record Anim(List<VoxelModel.Quad> fixed, List<List<VoxelModel.Quad>> moving) {}

	private static final Map<String, Optional<Anim>> ANIMS = new ConcurrentHashMap<>();
	/** Positions (BlockPos.asLong) where chunk building met an animated block. Written from chunk-building threads. */
	private static final Set<Long> SEEN = ConcurrentHashMap.newKeySet();
	/** The white block-atlas sprite every custom block is painted on (vertex colors do the rest). */
	static volatile TextureAtlasSprite white;

	static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(AnimatedBlocks::submit);
	}

	/** Definitions changed: animations are rebuilt the next time they're needed. */
	static void clear() {
		ANIMS.clear();
	}

	/** Chunk building met an animated block here. */
	static void seen(BlockPos pos) {
		SEEN.add(pos.asLong());
	}

	/** The design's animation, or null if it doesn't move. Safe from any thread. */
	static Anim anim(String id) {
		if (id == null) return null;
		return ANIMS.computeIfAbsent(id, k -> {
			JsonObject def = CustomItems.DEFS.get(k);
			if (!CustomBlocks.animated(def)) return Optional.empty();
			try {
				return Optional.of(build(def.getAsJsonObject("model")));
			} catch (RuntimeException e) {
				return Optional.empty();
			}
		}).orElse(null);
	}

	private static Anim build(JsonObject model) {
		int n = Math.min(VoxelModel.frameCount(model), VoxelModel.MAX_FRAMES);
		List<List<VoxelModel.Quad>> frames = new ArrayList<>();
		for (int f = 0; f < n; f++) frames.add(VoxelModel.parse(model, f).mesh(true));
		// A face is fixed when every frame has exactly that face; each frame's mesh covers its surface once, so
		// fixed faces plus that frame's moving ones draw the frame with nothing doubled.
		Map<String, Integer> counts = new HashMap<>();
		for (List<VoxelModel.Quad> fr : frames) for (VoxelModel.Quad q : fr) counts.merge(key(q), 1, Integer::sum);
		List<VoxelModel.Quad> fixed = new ArrayList<>();
		for (VoxelModel.Quad q : frames.getFirst()) if (counts.get(key(q)) == n) fixed.add(q);
		List<List<VoxelModel.Quad>> moving = new ArrayList<>();
		for (List<VoxelModel.Quad> fr : frames) moving.add(fr.stream().filter(q -> counts.get(key(q)) < n).toList());
		return new Anim(fixed, moving);
	}

	private static String key(VoxelModel.Quad q) {
		return q.argb() + "/" + q.face() + "/" + q.boundary() + Arrays.toString(q.corners());
	}

	/** One block's moving faces this frame, ready to draw. */
	private record Draw(float x, float y, float z, Direction facing, VoxelModel.Quad quad, int light, float shade) {}

	private static void submit(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		TextureAtlasSprite sprite = white;
		if (level == null || sprite == null || SEEN.isEmpty()) return;
		CameraRenderState cam = ctx.levelState().cameraRenderState;
		Vec3 c = cam.pos;
		Frustum frustum = cam.cullFrustum;
		double max = Math.min(MAX_DISTANCE, mc.options.getEffectiveRenderDistance() * 16.0);
		long time = level.getGameTime();
		var shading = level.cardinalLighting();
		List<Draw> solid = new ArrayList<>(), seeThrough = new ArrayList<>();
		for (Iterator<Long> it = SEEN.iterator(); it.hasNext(); ) {
			BlockPos pos = BlockPos.of(it.next());
			if (!level.hasChunkAt(pos)) {
				it.remove();
				continue;
			}
			BlockState state = level.getBlockState(pos);
			JsonObject def = CustomBlocks.definition(state);
			Anim a = def == null ? null : anim(def.get("id").getAsString());
			if (a == null) {
				it.remove(); // broken, replaced, or no longer animated
				continue;
			}
			if (pos.distToCenterSqr(c) > max * max || !frustum.isVisible(new AABB(pos))) continue;
			int frame = VoxelModel.frameAt(def.getAsJsonObject("model"), time, CustomBlocks.frameOffset(def, pos));
			List<VoxelModel.Quad> quads = a.moving().get(frame % a.moving().size());
			if (quads.isEmpty()) continue;
			Direction facing = state.getValue(CustomBlocks.FACING);
			boolean glow = CustomBlocks.glows(def);
			int inner = glow ? LightCoordsUtil.FULL_BRIGHT : LightCoordsUtil.getLightCoords(level, pos);
			float x = (float) (pos.getX() - c.x), y = (float) (pos.getY() - c.y), z = (float) (pos.getZ() - c.z);
			int[] sideLight = new int[6];
			byte[] sideShown = new byte[6]; // 0 unknown, 1 shown, 2 hidden by the neighbor
			for (VoxelModel.Quad q : quads) {
				int light = inner;
				if (q.boundary() >= 0) {
					Direction side = ClaudeCraftClient.turn(ClaudeCraftClient.DIRS[q.boundary()], facing);
					int i = side.ordinal();
					if (sideShown[i] == 0) {
						BlockPos n = pos.relative(side);
						sideShown[i] = (byte) (Block.shouldRenderFace(state, level.getBlockState(n), side) ? 1 : 2);
						sideLight[i] = glow ? LightCoordsUtil.FULL_BRIGHT : LightCoordsUtil.getLightCoords(level, n);
					}
					if (sideShown[i] == 2) continue;
					light = sideLight[i];
				}
				float shade = q.diagonal() ? 1 : shading.byFace(ClaudeCraftClient.turn(ClaudeCraftClient.DIRS[q.face()], facing));
				(q.translucent() ? seeThrough : solid).add(new Draw(x, y, z, facing, q, light, shade));
			}
		}
		PoseStack pose = ctx.poseStack();
		float u = sprite.getU(0.5f), v = sprite.getV(0.5f);
		if (!solid.isEmpty()) ctx.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.cutoutMovingBlock(), (p, vc) -> emit(p, vc, solid, u, v));
		if (!seeThrough.isEmpty()) ctx.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.translucentMovingBlock(), (p, vc) -> emit(p, vc, seeThrough, u, v));
	}

	private static void emit(PoseStack.Pose p, VertexConsumer vc, List<Draw> draws, float u, float v) {
		for (Draw d : draws) {
			VoxelModel.Quad q = d.quad();
			float[] c = q.corners();
			int argb = shade(q.argb(), d.shade());
			double[] o = CustomBlocks.rotate(0, 0, d.facing()), n = CustomBlocks.rotate(q.nx(), q.nz(), d.facing());
			float nx = (float) (n[0] - o[0]), nz = (float) (n[1] - o[1]);
			for (int k = 0; k < 4; k++) {
				double[] xz = CustomBlocks.rotate(c[k * 3], c[k * 3 + 2], d.facing());
				vc.addVertex(p, d.x() + (float) xz[0], d.y() + c[k * 3 + 1], d.z() + (float) xz[1])
					.setColor(argb).setUv(u, v).setLight(d.light()).setNormal(p, nx, q.ny(), nz);
			}
		}
	}

	/** Darken a color for the side it faces, as the game does for block faces (tops brightest, bottoms darkest). */
	private static int shade(int argb, float f) {
		int r = (int) (((argb >> 16) & 0xFF) * f), g = (int) (((argb >> 8) & 0xFF) * f), b = (int) ((argb & 0xFF) * f);
		return (argb & 0xFF000000) | r << 16 | g << 8 | b;
	}
}
