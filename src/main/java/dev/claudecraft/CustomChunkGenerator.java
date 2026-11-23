package dev.claudecraft;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Terrain for Claude's dimensions. The generator is registered once at startup (claudecraft:custom); what it builds
 * comes from the dimension's definition, read whenever a chunk is generated, so redesigning a dimension changes the
 * chunks generated after that (reset wipes the old ones).
 *
 * terrain: { style: hills | mountains | flat | ocean | islands | caves | void, height (surface y), variation
 *   (blocks up and down), scale (feature size in blocks), surface, under, depth, stone, fluid, sea_level,
 *   bedrock, carve_caves, decorate, structures, height_expression (x, z, n), solid_expression (x, y, z, n, h) }
 */
public final class CustomChunkGenerator extends ChunkGenerator {
	public static final MapCodec<CustomChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
		Codec.STRING.fieldOf("dimension").forGetter(g -> g.dimensionId),
		Codec.LONG.fieldOf("seed").forGetter(g -> g.seed),
		Codec.INT.fieldOf("min_y").forGetter(g -> g.minY),
		Codec.INT.fieldOf("height").forGetter(g -> g.height)
	).apply(i, i.stable(CustomChunkGenerator::new)));

	static final List<String> STYLES = List.of("hills", "mountains", "flat", "ocean", "islands", "caves", "void");

	final String dimensionId;
	private final long seed;
	final int minY, height;
	private final SimplexNoise[] noise2 = new SimplexNoise[4], noise3 = new SimplexNoise[3];
	private final SimplexNoise tunnelA, tunnelB;
	/** The parsed terrain and the definition it came from, swapped whenever the definition changes. */
	private volatile Terrain terrain;
	private volatile JsonObject terrainSource;

	public CustomChunkGenerator(BiomeSource biomes, String dimensionId, long seed, int minY, int height) {
		super(biomes);
		this.dimensionId = dimensionId;
		this.seed = seed;
		this.minY = minY;
		this.height = height;
		RandomSource r = RandomSource.create(seed ^ dimensionId.hashCode() * 0x9E3779B97F4A7C15L);
		for (int i = 0; i < noise2.length; i++) noise2[i] = new SimplexNoise(r);
		for (int i = 0; i < noise3.length; i++) noise3[i] = new SimplexNoise(r);
		tunnelA = new SimplexNoise(r);
		tunnelB = new SimplexNoise(r);
	}

	// ------------------------------------------------------------ settings

	/** A dimension's parsed terrain settings. */
	record Terrain(String style, int base, int sea, double variation, double scale, BlockState surface, BlockState under, int depth,
		BlockState stone, BlockState fluid, boolean bedrock, boolean caves, boolean decorate, boolean structures,
		Shapes.Expr heightExpr, Shapes.Expr solidExpr) {}

	/** Parse a terrain object; throws IllegalArgumentException with a readable problem. */
	static Terrain parse(JsonObject t, int minY) {
		String style = str(t, "style", "hills");
		if (!STYLES.contains(style)) throw new IllegalArgumentException("terrain.style must be one of " + String.join(", ", STYLES));
		int base = t.has("height") ? t.get("height").getAsInt() : switch (style) {
			case "islands" -> 100;
			case "ocean" -> 50;
			case "caves" -> minY + 128;
			default -> 64;
		};
		double variation = t.has("variation") ? t.get("variation").getAsDouble() : switch (style) {
			case "mountains" -> 80;
			case "islands" -> 30;
			case "ocean" -> 20;
			case "flat", "void", "caves" -> 0;
			default -> 14;
		};
		double scale = Math.max(4, t.has("scale") ? t.get("scale").getAsDouble() : switch (style) {
			case "mountains" -> 220;
			case "islands" -> 70;
			case "caves" -> 50;
			default -> 110;
		});
		int sea = t.has("sea_level") ? t.get("sea_level").getAsInt() : switch (style) {
			case "hills", "mountains", "ocean" -> 62;
			default -> Integer.MIN_VALUE; // no sea
		};
		Shapes.Expr heightExpr = t.has("height_expression") ? Shapes.parse(t.get("height_expression").getAsString(), List.of("x", "z", "n")) : null;
		Shapes.Expr solidExpr = t.has("solid_expression") ? Shapes.parse(t.get("solid_expression").getAsString(), List.of("x", "y", "z", "n", "h")) : null;
		boolean cave = style.equals("caves");
		return new Terrain(style, base, sea, variation, scale,
			block(str(t, "surface", cave ? "minecraft:stone" : "minecraft:grass_block")),
			block(str(t, "under", cave ? "minecraft:stone" : "minecraft:dirt")),
			Math.max(0, Math.min(16, t.has("depth") ? t.get("depth").getAsInt() : 3)),
			block(str(t, "stone", "minecraft:stone")),
			block(str(t, "fluid", "minecraft:water")),
			bool(t, "bedrock", !style.equals("islands") && !style.equals("void")),
			bool(t, "carve_caves", style.equals("hills") || style.equals("mountains")),
			bool(t, "decorate", !style.equals("void")),
			bool(t, "structures", false),
			heightExpr, solidExpr);
	}

	private static String str(JsonObject o, String k, String d) {
		return o.has(k) ? o.get(k).getAsString() : d;
	}

	private static boolean bool(JsonObject o, String k, boolean d) {
		return o.has(k) ? o.get(k).getAsBoolean() : d;
	}

	/** A block state: vanilla syntax, or custom:<id> for a custom block. */
	static BlockState block(String s) {
		s = s.trim();
		if (s.startsWith("custom:")) {
			JsonObject def = CustomItems.DEFS.get(s.substring(7));
			if (!CustomBlocks.isBlock(def)) throw new IllegalArgumentException("No custom block " + s);
			return CustomBlocks.state(def);
		}
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState();
		} catch (Exception e) {
			throw new IllegalArgumentException("Unknown block " + s);
		}
	}

	private Terrain terrain() {
		JsonObject def = CustomItems.DEFS.get(dimensionId);
		Terrain cached = terrain;
		if (cached != null && def == terrainSource) return cached;
		JsonObject t = def != null && def.has("terrain") ? def.getAsJsonObject("terrain") : new JsonObject();
		try {
			cached = parse(t, minY);
		} catch (RuntimeException e) {
			ClaudeCraft.LOGGER.warn("Dimension {} has bad terrain settings ({}); generating plain hills", dimensionId, e.getMessage());
			cached = parse(new JsonObject(), minY);
		}
		terrain = cached;
		terrainSource = def;
		return cached;
	}

	// ------------------------------------------------------------ shape

	/** Fractal noise in about -1..1 over (x, z) at the given feature size. */
	private double fbm2(double x, double z, double scale) {
		double sum = 0, amp = 1, norm = 0, f = 1 / scale;
		for (SimplexNoise n : noise2) {
			sum += n.get(x * f, z * f) * amp;
			norm += amp;
			amp *= 0.5;
			f *= 2;
		}
		return sum / norm * 1.6;
	}

	private double fbm3(double x, double y, double z, double scale) {
		double sum = 0, amp = 1, norm = 0, f = 1 / scale;
		for (SimplexNoise n : noise3) {
			sum += n.get(x * f, y * f, z * f) * amp;
			norm += amp;
			amp *= 0.5;
			f *= 2;
		}
		return sum / norm * 1.6;
	}

	private int surfaceHeight(Terrain t, int x, int z) {
		double n = fbm2(x, z, t.scale());
		if (t.heightExpr() != null) return (int) Math.floor(t.heightExpr().eval(new double[]{x, z, n}));
		return switch (t.style()) {
			case "mountains" -> {
				double ridge = 1 - Math.abs(n);
				yield (int) Math.round(t.base() + t.variation() * (ridge * ridge * 1.4 - 0.35));
			}
			case "hills", "ocean" -> (int) Math.round(t.base() + t.variation() * n);
			default -> t.base();
		};
	}

	private boolean solid(Terrain t, int x, int y, int z, int h) {
		if (t.solidExpr() != null) {
			double n = fbm3(x, y, z, t.scale());
			return t.solidExpr().eval(new double[]{x, y, z, n, h}) > 0;
		}
		switch (t.style()) {
			case "void":
				return false;
			case "islands": {
				// Blobs of 3D noise, strongest at the island height and fading above and below it.
				double band = 1 - Math.abs(y - t.base()) / Math.max(4, t.variation());
				if (band <= 0) return false;
				double d = fbm3(x, y * 1.6, z, t.scale());
				return d + band * 0.9 - 0.75 > 0;
			}
			case "caves": {
				if (y <= minY + 3 || y >= minY + height - 4) return true; // floor and ceiling
				double cavern = fbm3(x, y * 1.4, z, t.scale());
				return cavern < 0.35 && !tunnel(x, y, z);
			}
			default:
				if (y >= h) return false;
				return !(t.caves() && y > minY + 5 && y < h - 6 && tunnel(x, y, z));
		}
	}

	/** Winding tunnels where two noise fields are both near zero (like vanilla's spaghetti caves). */
	private boolean tunnel(int x, int y, int z) {
		double a = tunnelA.get(x / 40.0, y / 28.0, z / 40.0), b = tunnelB.get(x / 40.0, y / 28.0, z / 40.0);
		return Math.abs(a) < 0.07 && Math.abs(b) < 0.07;
	}

	/** The blocks of one column, from minY up. */
	private BlockState[] column(Terrain t, int x, int z) {
		BlockState[] out = new BlockState[height];
		BlockState air = Blocks.AIR.defaultBlockState();
		java.util.Arrays.fill(out, air);
		int h = surfaceHeight(t, x, z);
		boolean simple = t.solidExpr() == null && !List.of("islands", "caves", "void").contains(t.style());
		int top = minY + height - 1;
		if (simple) top = Math.min(top, Math.max(h, t.sea() == Integer.MIN_VALUE ? h : t.sea()) + 1);
		int run = 0; // solid blocks in a row from the top of the current solid stretch
		for (int y = top; y >= minY; y--) {
			int i = y - minY;
			if (solid(t, x, y, z, h)) {
				out[i] = run == 0 ? (y >= t.sea() - 1 ? t.surface() : t.under()) : run <= t.depth() ? t.under() : t.stone();
				run++;
			} else {
				run = 0;
				if (t.sea() != Integer.MIN_VALUE && y < t.sea()) out[i] = t.fluid();
			}
		}
		if (t.bedrock()) {
			out[0] = Blocks.BEDROCK.defaultBlockState();
			if (t.style().equals("caves")) out[height - 1] = Blocks.BEDROCK.defaultBlockState();
		}
		return out;
	}

	// ------------------------------------------------------------ ChunkGenerator

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structures,
			BiomeManager biomes, WorldGenRegion carverRegion, Set<Holder<Biome>> possibleBiomes) {
		Terrain t = terrain();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
		int bottom = Math.max(minY, chunk.getMinY()), last = Math.min(minY + height, chunk.getMinY() + chunk.getHeight());
		for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
			BlockState[] col = column(t, x0 + dx, z0 + dz);
			for (int y = bottom; y < last; y++) {
				BlockState st = col[y - minY];
				if (st.isAir()) continue;
				chunk.setBlockState(pos.set(dx, y, dz), st);
				oceanFloor.update(dx, y, dz, st);
				worldSurface.update(dx, y, dz, st);
			}
		}
		return CompletableFuture.completedFuture(chunk);
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
		BlockState[] col = column(terrain(), x, z);
		for (int i = col.length - 1; i >= 0; i--) if (type.isOpaque().test(col[i])) return minY + i + 1;
		return minY;
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
		return new NoiseColumn(minY, column(terrain(), x, z));
	}

	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
		if (terrain().decorate()) super.applyBiomeDecoration(level, chunk, structures);
	}

	@Override
	public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> sets, RandomState randomState, long levelSeed) {
		if (terrain().structures()) return super.createState(sets, randomState, levelSeed);
		return ChunkGeneratorStructureState.createForFlat(randomState, levelSeed, getOrigin(randomState), biomeSource, Stream.empty());
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
		if (!terrain().decorate()) return;
		var center = region.getCenter();
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
		random.setDecorationSeed(region.getSeed(), center.getMinBlockX(), center.getMinBlockZ());
		NaturalSpawner.spawnMobsForChunkGeneration(region, center.getWorldPosition().atY(region.getMaxY()), center, random);
	}

	@Override
	public int getGenDepth() {
		return height;
	}

	@Override
	public int getSeaLevel() {
		int sea = terrain().sea();
		return sea == Integer.MIN_VALUE ? minY - 1 : sea;
	}

	@Override
	public int getMinY() {
		return minY;
	}

	@Override
	public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos, SamplerContext ctx) {
		result.add(String.format(Locale.ROOT, "ClaudeCraft dimension %s (%s)", dimensionId, terrain().style()));
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}
}
