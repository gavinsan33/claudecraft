package dev.claudecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** The MCP tools Claude uses to observe and act in the world. All world access runs on the server thread. */
public class GameTools {
	/** Thrown for user-facing tool errors (bad arguments etc.). */
	static class ToolError extends Exception {
		ToolError(String msg) { super(msg); }
	}

	@FunctionalInterface
	interface Handler {
		/** Returns a String, or a CompletableFuture<String> for work that finishes over later ticks. */
		Object run(JsonObject args) throws Exception;
	}

	record Tool(String name, String description, JsonObject schema, Handler handler) {}

	private final MinecraftServer server;
	private final Config cfg;
	private final UndoHistory undo;
	private final BuildQueue builds;
	private final Map<String, Tool> tools = new LinkedHashMap<>();
	/** Name of the player whose message Claude is currently handling. */
	private volatile String defaultPlayer;
	private final AtomicInteger buildIds = new AtomicInteger();
	/** Results of background builds, handed to Claude with its next tool result. */
	private final ConcurrentLinkedQueue<String> reports = new ConcurrentLinkedQueue<>();

	public GameTools(MinecraftServer server, Config cfg, UndoHistory undo, BuildQueue builds) {
		this.server = server;
		this.cfg = cfg;
		this.undo = undo;
		this.builds = builds;
		registerAll();
	}

	public void setDefaultPlayer(String name) {
		this.defaultPlayer = name;
	}

	public UndoHistory undoHistory() {
		return undo;
	}

	/** Completes once every queued build has been placed. Any thread. */
	public CompletableFuture<Void> buildsIdle() {
		return builds.idle();
	}

	// ---------------------------------------------------------------- MCP plumbing

	public JsonArray listTools() {
		JsonArray arr = new JsonArray();
		for (Tool t : tools.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", t.name());
			o.addProperty("description", t.description());
			o.add("inputSchema", t.schema());
			arr.add(o);
		}
		return arr;
	}

	public JsonObject call(String name, JsonObject args) {
		Tool tool = tools.get(name);
		String text;
		boolean error = false;
		if (tool == null) {
			text = "Unknown tool: " + name;
			error = true;
		} else {
			try {
				Object result = server.submit(() -> {
					try {
						return tool.handler().run(args);
					} catch (RuntimeException e) {
						throw e;
					} catch (Exception e) {
						throw new WrappedException(e);
					}
				}).get(120, TimeUnit.SECONDS);
				// Animated builds complete on a later tick; wait here (off the server thread) until they're done.
				text = result instanceof CompletableFuture<?> f ? (String) f.get(30, TimeUnit.MINUTES) : (String) result;
			} catch (ExecutionException e) {
				Throwable cause = e.getCause() instanceof WrappedException w ? w.getCause() : e.getCause();
				if (cause instanceof CompletionException ce && ce.getCause() != null) cause = ce.getCause();
				error = true;
				if (cause instanceof ToolError || cause instanceof CommandSyntaxException) {
					text = "Error: " + cause.getMessage();
				} else {
					ClaudeCraft.LOGGER.error("Tool {} failed", name, cause);
					text = "Internal error: " + cause;
				}
			} catch (TimeoutException e) {
				error = true;
				text = "Error: timed out waiting for the server thread.";
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				error = true;
				text = "Error: interrupted.";
			}
		}
		StringBuilder finished = new StringBuilder();
		for (String r; (r = reports.poll()) != null; ) finished.append('\n').append(r);
		if (!finished.isEmpty()) text += "\n\nFinished in the background:" + finished;
		JsonObject result = new JsonObject();
		JsonArray content = new JsonArray();
		JsonObject c = new JsonObject();
		c.addProperty("type", "text");
		c.addProperty("text", text);
		content.add(c);
		result.add("content", content);
		result.addProperty("isError", error);
		return result;
	}

	private static final class WrappedException extends RuntimeException {
		WrappedException(Throwable cause) { super(cause); }
	}

	private void register(String name, String description, String schemaJson, Handler handler) {
		JsonObject schema = JsonParser.parseString(schemaJson).getAsJsonObject();
		// Every placement tool (the ones with duration_seconds) also takes a mask.
		JsonObject props = schema.getAsJsonObject("properties");
		if (props != null && props.has("duration_seconds")) props.add("mask", JsonParser.parseString(MASK_SCHEMA));
		tools.put(name, new Tool(name, description, schema, handler));
	}

	// ---------------------------------------------------------------- tool definitions

	private static final String POS_SCHEMA = """
		{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"}},"required":["x","y","z"]}""";
	private static final String DIM_SCHEMA = """
		{"type":"string","description":"Dimension id, e.g. minecraft:the_nether. Defaults to the requesting player's dimension."}""";
	private static final String PATTERN_SCHEMA = """
		{"type":"string","description":"A block state, or a random mix in WorldEdit pattern syntax, e.g. \\"70%minecraft:stone_bricks,20%minecraft:mossy_stone_bricks,10%minecraft:cracked_stone_bricks\\"."}""";
	private static final String DURATION_SCHEMA = """
		{"type":"number","description":"Optional: stretch or squeeze this build's animation to take about this many seconds (max 120). Default pacing is fast."}""";
	private static final String MASK_SCHEMA = """
		{"type":"string","description":"Optional WorldEdit-style mask: only change blocks where it matches, checked as each block is placed. Space-separated terms must all match. A term is a comma list of blocks, block tags or partial states (minecraft:air, #minecraft:logs, minecraft:oak_stairs[half=top]), or #existing (not air), #solid, #liquid, #surface (solid with no solid block above). Prefix >X for 'directly above X', <X for 'directly below X', !X to negate. Examples: minecraft:air (never overwrite anything), !#existing >#solid (air resting on solid ground), #surface !#minecraft:logs."}""";
	private static final String QUEUED =
		" Returns immediately: blocks animate into place in the background, one queued build after another, while you keep working. "
			+ "Each build's result (block count, anything that couldn't survive) is attached to a later tool result. Undoable.";

	private void registerAll() {
		register("get_context",
			"Get the current state around a player: exact position, facing, what block they are looking at, dimension, biome, "
				+ "game mode, health, held item and inventory, time, weather, other online players, and nearby entities.",
			"""
				{"type":"object","properties":{
				  "player":{"type":"string","description":"Player name. Defaults to the player who sent the current message."},
				  "entity_radius":{"type":"integer","description":"Radius for nearby entities (default 24, max 128)."}
				}}""",
			this::getContext);

		register("run_commands",
			"Run one or more Minecraft server commands (with or without leading slash), in order, and return each command's output. "
				+ "Commands execute as the requesting player: @s is that player and ~ ~ ~ is their position and rotation. "
				+ "Use this for anything without a dedicated tool: give, summon, effect, enchant, tp, time, weather, gamerule, "
				+ "particle, playsound, tellraw, data, attribute, execute, item, and more. Block changes made here cannot be undone.",
			"""
				{"type":"object","properties":{
				  "commands":{"type":"array","items":{"type":"string"},"description":"Commands to run in order."},
				  "player":{"type":"string","description":"Run as this player instead of the requester."}
				},"required":["commands"]}""",
			this::runCommands);

		register("place_blueprint",
			"Build a 3D structure from a character blueprint. palette maps single characters to block states "
				+ "(e.g. \"s\":\"minecraft:stone_bricks\", \"D\":\"minecraft:oak_door[facing=north,half=lower]\"). "
				+ "layers is bottom-to-top: layers[i] is placed at y = origin.y + i. Each layer is a list of rows: row j is at "
				+ "z = origin.z + j, and character k in a row is at x = origin.x + k. '.' and ' ' leave the existing block "
				+ "untouched unless you define them in the palette. Map a character to minecraft:air to clear blocks. "
				+ "Palette values may be random mixes like \"80%minecraft:stone_bricks,20%minecraft:mossy_stone_bricks\"." + QUEUED,
			"""
				{"type":"object","properties":{
				  "origin":%s,
				  "palette":{"type":"object","additionalProperties":{"type":"string"}},
				  "layers":{"type":"array","items":{"type":"array","items":{"type":"string"}}},
				  "duration_seconds":%s,
				  "dimension":%s
				},"required":["origin","palette","layers"]}""".formatted(POS_SCHEMA, DURATION_SCHEMA, DIM_SCHEMA),
			this::placeBlueprint);

		register("fill_region",
			"Fill the box between two corners (inclusive) with one block state. Modes: solid (whole box), hollow (shell of "
				+ "the block with air inside), outline (shell only, inside untouched), walls (four vertical sides only). "
				+ "Optional replace only changes blocks matching it (e.g. minecraft:grass_block); it's shorthand for a mask. "
				+ "Use block minecraft:air to clear space." + QUEUED,
			"""
				{"type":"object","properties":{
				  "from":%s,"to":%s,
				  "block":%s,
				  "mode":{"type":"string","enum":["solid","hollow","outline","walls"]},
				  "replace":{"type":"string","description":"Only replace blocks matching this (same syntax as mask)."},
				  "duration_seconds":%s,
				  "dimension":%s
				},"required":["from","to","block"]}""".formatted(POS_SCHEMA, POS_SCHEMA, PATTERN_SCHEMA, DURATION_SCHEMA, DIM_SCHEMA),
			this::fillRegion);

		register("place_blocks",
			"Place individual blocks at absolute coordinates. Each entry is {\"x\":..,\"y\":..,\"z\":..,\"block\":\"minecraft:...\"} "
				+ "or a compact array [x, y, z, \"block\"]." + QUEUED,
			"""
				{"type":"object","properties":{
				  "blocks":{"type":"array","items":{}},
				  "duration_seconds":%s,
				  "dimension":%s
				},"required":["blocks"]}""".formatted(DURATION_SCHEMA, DIM_SCHEMA),
			this::placeBlocks);

		register("draw_shape",
			"Draw a WorldEdit-style shape. Shapes: sphere (center, radius, or radii [rx,ry,rz] for an ellipsoid), "
				+ "dome (top half of a sphere, center is the middle of its base), cylinder (center of the base, radius, height, "
				+ "axis y/x/z; negative height extends the other way), cone and pyramid (center of the base, radius = base "
				+ "half-width, height; negative height points down; a pyramid with height radius+1 has one-block steps), "
				+ "torus (center, major radius, minor radius, axis of the hole), line (points: a polyline, radius 0 = thin). "
				+ "hollow draws only a 1-block shell and leaves the inside untouched; hollow cylinders, cones and pyramids "
				+ "are open at the ends/base (tubes, roofs). Coordinates are block positions; radii may be decimals." + QUEUED,
			"""
				{"type":"object","properties":{
				  "shape":{"type":"string","enum":["sphere","dome","cylinder","cone","pyramid","torus","line"]},
				  "center":%s,
				  "radius":{"type":"number"},
				  "radii":{"type":"array","items":{"type":"number"},"description":"sphere/dome only: [rx, ry, rz]."},
				  "height":{"type":"integer","description":"cylinder, cone, pyramid. For cylinders along x or z, the length."},
				  "minor_radius":{"type":"number","description":"torus tube radius (default 1)."},
				  "axis":{"type":"string","enum":["y","x","z"],"description":"cylinder and torus orientation (default y)."},
				  "points":{"type":"array","items":%s,"description":"line only: two or more points."},
				  "hollow":{"type":"boolean"},
				  "block":%s,
				  "duration_seconds":%s,
				  "dimension":%s
				},"required":["shape","block"]}""".formatted(POS_SCHEMA, POS_SCHEMA, PATTERN_SCHEMA, DURATION_SCHEMA, DIM_SCHEMA),
			this::drawShape);

		register("generate",
			"Fill every block in a box where a math expression is true (WorldEdit //generate). Variables: x, y, z run from "
				+ "-1 to 1 across the box (0 = its center); bx, by, bz are block offsets from the minimum corner; sx, sy, sz are "
				+ "the box size. Operators + - * / % ^, < <= > >= == !=, && || !, functions abs sqrt sin cos tan asin acos atan "
				+ "atan2 min max floor ceil round sign exp log pow hypot, constants pi and e. Examples: a tapering curved tower "
				+ "leg \"abs(x) + abs(z) < (1 - (y+1)/2)^2 * 0.9\", a twisted spire, a wavy wall, an arch. Use it for curves the "
				+ "other tools can't express." + QUEUED,
			"""
				{"type":"object","properties":{
				  "from":%s,"to":%s,
				  "expression":{"type":"string"},
				  "block":%s,
				  "hollow":{"type":"boolean","description":"Only the shape's 1-block surface."},
				  "duration_seconds":%s,
				  "dimension":%s
				},"required":["from","to","expression","block"]}""".formatted(POS_SCHEMA, POS_SCHEMA, PATTERN_SCHEMA, DURATION_SCHEMA, DIM_SCHEMA),
			this::generate);

		register("clone_region",
			"Copy the blocks in a box (inclusive corners) so its minimum corner lands at dest, like WorldEdit copy/paste/stack. "
				+ "rotate (degrees clockwise seen from above: 0, 90, 180, 270) and mirror (x flips east-west, z flips north-south) "
				+ "turn stairs, doors and logs correctly. repeat N stacks N copies, each shifted again by the same offset "
				+ "(dest minus the box's minimum corner). Air in the source is skipped unless include_air. Chest contents and "
				+ "sign text aren't copied. Build one leg, wing or arch and clone it for symmetry. Waits for queued builds first, "
				+ "so it copies what they place." + QUEUED,
			"""
				{"type":"object","properties":{
				  "from":%s,"to":%s,"dest":%s,
				  "rotate":{"type":"integer","enum":[0,90,180,270]},
				  "mirror":{"type":"string","enum":["none","x","z"]},
				  "repeat":{"type":"integer","description":"Number of copies (default 1, max 64)."},
				  "include_air":{"type":"boolean"},
				  "duration_seconds":%s,
				  "dimension":%s
				},"required":["from","to","dest"]}""".formatted(POS_SCHEMA, POS_SCHEMA, POS_SCHEMA, DURATION_SCHEMA, DIM_SCHEMA),
			this::cloneRegion);

		register("wait_for_builds",
			"Wait until every queued build has finished animating, then return their results. Call it before your final "
				+ "message so you can fix anything that failed and so you don't announce a build that's still going up.",
			"""
				{"type":"object","properties":{}}""",
			args -> afterBuilds(() -> "All builds are finished."));

		register("scan_area",
			"Read the blocks in a box (inclusive corners) in the same layer/row/palette format place_blueprint uses, "
				+ "with origin at the minimum corner. '.' = air. Use it to inspect terrain, existing builds, or verify your work. "
				+ "Waits for queued builds to finish first.",
			"""
				{"type":"object","properties":{
				  "from":%s,"to":%s,
				  "ignore_properties":{"type":"boolean","description":"Show only block ids, not full states (smaller output)."},
				  "dimension":%s
				},"required":["from","to"]}""".formatted(POS_SCHEMA, POS_SCHEMA, DIM_SCHEMA),
			args -> afterBuilds(() -> scanArea(args)));

		register("get_terrain",
			"Get a surface map of a horizontal area: the Y of the top non-leaf solid or liquid block for every column, "
				+ "plus the surface block type. Use it to find the ground level and flat spots before building.",
			"""
				{"type":"object","properties":{
				  "from":{"type":"object","properties":{"x":{"type":"integer"},"z":{"type":"integer"}},"required":["x","z"]},
				  "to":{"type":"object","properties":{"x":{"type":"integer"},"z":{"type":"integer"}},"required":["x","z"]},
				  "step":{"type":"integer","description":"Sample every Nth column (default 1)."},
				  "dimension":%s
				},"required":["from","to"]}""".formatted(DIM_SCHEMA),
			this::getTerrain);

		register("undo",
			"Revert your most recent block changes made with the placement tools (anything except run_commands). "
				+ "Each step is one chat turn's worth of changes (including the current turn). Waits for queued builds first.",
			"""
				{"type":"object","properties":{"steps":{"type":"integer","description":"How many steps to undo (default 1)."}}}""",
			args -> undo(optInt(args, "steps", 1)));
	}

	// ---------------------------------------------------------------- helpers

	private ServerPlayer player(JsonObject args, String key) throws ToolError {
		if (args.has(key) && !args.get(key).getAsString().isBlank()) {
			String name = args.get(key).getAsString();
			ServerPlayer p = server.getPlayerList().getPlayerByName(name);
			if (p == null) throw new ToolError("No online player named " + name);
			return p;
		}
		if (defaultPlayer != null) {
			ServerPlayer p = server.getPlayerList().getPlayerByName(defaultPlayer);
			if (p != null) return p;
		}
		List<ServerPlayer> all = server.getPlayerList().getPlayers();
		return all.size() == 1 ? all.getFirst() : null;
	}

	private ServerLevel level(JsonObject args) throws ToolError {
		if (args.has("dimension") && !args.get("dimension").getAsString().isBlank()) {
			String dim = args.get("dimension").getAsString();
			for (ServerLevel l : server.getAllLevels()) {
				String id = l.dimension().identifier().toString();
				if (id.equals(dim) || l.dimension().identifier().getPath().equals(dim)) return l;
			}
			throw new ToolError("Unknown dimension " + dim);
		}
		ServerPlayer p = player(new JsonObject(), "player");
		return p != null ? p.level() : server.overworld();
	}

	private static int optInt(JsonObject o, String key, int def) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def;
	}

	private static BlockPos pos(JsonObject args, String key) throws ToolError {
		if (!args.has(key)) throw new ToolError("Missing " + key);
		JsonElement e = args.get(key);
		if (e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
		}
		JsonObject o = e.getAsJsonObject();
		return new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
	}

	private static final class BlockParser {
		private final HolderLookup<Block> lookup;
		private final Map<String, BlockState> cache = new HashMap<>();

		BlockParser(ServerLevel level) {
			this.lookup = level.holderLookup(Registries.BLOCK);
		}

		BlockState parse(String s) throws ToolError {
			BlockState cached = cache.get(s);
			if (cached != null) return cached;
			String norm = s.trim();
			try {
				BlockState st = BlockStateParser.parseForBlock(lookup, norm, false).blockState();
				cache.put(s, st);
				return st;
			} catch (CommandSyntaxException e) {
				throw new ToolError("Invalid block state '" + s + "': " + e.getMessage());
			}
		}

		/** "stone" or "70%stone_bricks,30%mossy_stone_bricks" (commas inside [...] belong to the block state). */
		Pattern pattern(String s) throws ToolError {
			List<BlockState> states = new ArrayList<>();
			List<Double> weights = new ArrayList<>();
			for (String part : splitTopLevel(s)) {
				var m = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)%\\s*(.+)$").matcher(part);
				double w = 1;
				if (m.matches()) {
					w = Double.parseDouble(m.group(1));
					part = m.group(2);
				}
				states.add(parse(part));
				weights.add(w);
			}
			if (states.isEmpty()) throw new ToolError("Empty block pattern");
			double[] cum = new double[weights.size()];
			double total = 0;
			for (int i = 0; i < cum.length; i++) cum[i] = total += weights.get(i);
			return new Pattern(states.toArray(BlockState[]::new), cum);
		}
	}

	/** One block state, or a weighted random mix of several. */
	record Pattern(BlockState[] states, double[] cumulative) {
		BlockState pick() {
			if (states.length == 1) return states[0];
			double r = ThreadLocalRandom.current().nextDouble() * cumulative[cumulative.length - 1];
			for (int i = 0; i < states.length; i++) if (r < cumulative[i]) return states[i];
			return states[states.length - 1];
		}
	}

	/** Split on commas that aren't inside [...] or {...} (those belong to a block state or NBT). */
	private static List<String> splitTopLevel(String s) {
		List<String> parts = new ArrayList<>();
		int depth = 0, start = 0;
		for (int i = 0; i <= s.length(); i++) {
			char c = i < s.length() ? s.charAt(i) : ',';
			if (c == '[' || c == '{') depth++;
			else if (c == ']' || c == '}') depth--;
			else if (c == ',' && depth == 0) {
				String part = s.substring(start, i).trim();
				start = i + 1;
				if (!part.isEmpty()) parts.add(part);
			}
		}
		return parts;
	}

	/** The mask (and fill_region's replace) from a tool call, or null for none. See MASK_SCHEMA for the syntax. */
	private static Predicate<BlockPos> mask(ServerLevel level, JsonObject args) throws ToolError {
		Predicate<BlockPos> all = null;
		for (String key : List.of("mask", "replace")) {
			if (!args.has(key) || args.get(key).getAsString().isBlank()) continue;
			for (String term : args.get(key).getAsString().trim().split("\\s+")) {
				Predicate<BlockPos> t = maskTerm(level, term);
				all = all == null ? t : all.and(t);
			}
		}
		return all;
	}

	private static Predicate<BlockPos> maskTerm(ServerLevel level, String term) throws ToolError {
		if (term.startsWith("!")) return maskTerm(level, term.substring(1)).negate();
		if (term.startsWith(">")) {
			Predicate<BlockPos> below = maskTerm(level, term.substring(1));
			return p -> below.test(p.below());
		}
		if (term.startsWith("<")) {
			Predicate<BlockPos> above = maskTerm(level, term.substring(1));
			return p -> above.test(p.above());
		}
		switch (term) {
			case "#existing": return p -> !level.getBlockState(p).isAir();
			case "#solid": return p -> level.getBlockState(p).isSolid();
			case "#liquid": return p -> level.getBlockState(p).liquid();
			case "#surface": return p -> level.getBlockState(p).isSolid() && !level.getBlockState(p.above()).isSolid();
			default: break;
		}
		// Block ids, #tags and partial states, parsed the way /execute if block does.
		List<Predicate<BlockInWorld>> any = new ArrayList<>();
		for (String part : splitTopLevel(term)) {
			try {
				StringReader reader = new StringReader(part);
				any.add(BlockPredicateArgument.parse(level.holderLookup(Registries.BLOCK), reader));
				if (reader.canRead()) throw new ToolError("Unexpected '" + reader.getRemaining() + "' in mask term '" + part + "'");
			} catch (CommandSyntaxException e) {
				throw new ToolError("Bad mask term '" + part + "': " + e.getMessage());
			}
		}
		if (any.isEmpty()) throw new ToolError("Empty mask term");
		return p -> {
			BlockInWorld b = new BlockInWorld(level, p, true);
			for (Predicate<BlockInWorld> m : any) if (m.test(b)) return true;
			return false;
		};
	}

	/** Queue a build and return right away; its result goes into {@link #reports} when it's done. */
	private String queueBuild(ServerLevel level, Map<BlockPos, BlockState> changes, JsonObject args) throws ToolError {
		int id = buildIds.incrementAndGet();
		double secs = args.has("duration_seconds") ? args.get("duration_seconds").getAsDouble() : 0;
		builds.enqueue(level, changes, undo, secs, r -> reports.add("Build #" + id + ": " + r), mask(level, args));
		return "Queued build #" + id + " (" + changes.size() + " blocks). Everything queued is done in about " + builds.secondsLeft()
			+ "s. It's animating now; keep working.";
	}

	/** Run on the server thread once queued builds have finished, so reads see what they placed. */
	private CompletableFuture<String> afterBuilds(Callable<Object> work) {
		return builds.idle().thenComposeAsync(v -> {
			try {
				Object r = work.call();
				return r instanceof CompletableFuture<?> f ? f.thenApply(o -> (String) o) : CompletableFuture.completedFuture((String) r);
			} catch (Exception e) {
				return CompletableFuture.failedFuture(e);
			}
		}, server);
	}

	private void checkSize(long n) throws ToolError {
		if (n > cfg.maxBlocksPerCall) {
			throw new ToolError("Too many blocks in one call (" + n + " > " + cfg.maxBlocksPerCall + "). Split it into smaller calls.");
		}
	}

	private static String fmt(BlockPos p) {
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}

	private static String dimName(Level level) {
		return level.dimension().identifier().toString();
	}

	// ---------------------------------------------------------------- get_context

	private String getContext(JsonObject args) throws ToolError {
		ServerPlayer p = player(args, "player");
		int radius = Math.max(1, Math.min(128, optInt(args, "entity_radius", 24)));
		StringBuilder sb = new StringBuilder();
		ServerLevel level = p != null ? p.level() : server.overworld();

		if (p == null) {
			sb.append("No specific player (request came from the server console).\n");
		} else {
			var v = p.position();
			BlockPos bp = p.blockPosition();
			sb.append("Player: ").append(p.getScoreboardName()).append('\n');
			sb.append(String.format(Locale.ROOT, "Position: x=%.1f y=%.1f z=%.1f (block %s) in %s%n", v.x, v.y, v.z, fmt(bp), dimName(level)));
			sb.append("Biome: ").append(level.getBiome(bp).unwrapKey().map(k -> k.identifier().toString()).orElse("unknown")).append('\n');
			sb.append(String.format(Locale.ROOT, "Facing: %s (yaw %.0f, pitch %.0f)%n", p.getDirection().getSerializedName(), p.getYRot(), p.getXRot()));
			HitResult hit = p.pick(64, 1.0f, false);
			if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
				sb.append("Looking at: ").append(BlockStateParser.serialize(level.getBlockState(bh.getBlockPos())))
					.append(" at ").append(fmt(bh.getBlockPos())).append(" (").append(bh.getDirection().getSerializedName()).append(" face)\n");
			} else {
				sb.append("Looking at: nothing within 64 blocks\n");
			}
			sb.append("Game mode: ").append(p.gameMode().getName())
				.append(String.format(Locale.ROOT, ", health %.0f/%.0f, food %d/20%n", p.getHealth(), p.getMaxHealth(), p.getFoodData().getFoodLevel()));
			Inventory inv = p.getInventory();
			sb.append("Held item: ").append(describe(inv.getSelectedItem())).append('\n');
			List<String> items = new ArrayList<>();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack s = inv.getItem(i);
				if (!s.isEmpty()) items.add("[" + i + "] " + describe(s));
			}
			sb.append("Inventory: ").append(items.isEmpty() ? "empty" : String.join(", ", items)).append('\n');
		}

		long time = Math.floorMod(level.getOverworldClockTime(), 24000L);
		String phase = time < 12000 ? "day" : time < 13800 ? "sunset" : time < 22200 ? "night" : "sunrise";
		sb.append("Time of day: ").append(time).append(" (").append(phase).append("), weather: ")
			.append(level.isThundering() ? "thunderstorm" : level.isRaining() ? "rain" : "clear").append('\n');

		List<String> others = new ArrayList<>();
		for (ServerPlayer o : server.getPlayerList().getPlayers()) {
			if (o == p) continue;
			others.add(o.getScoreboardName() + " at " + fmt(o.blockPosition()) + " in " + dimName(o.level()));
		}
		sb.append("Other players online: ").append(others.isEmpty() ? "none" : String.join("; ", others)).append('\n');

		if (p != null) {
			List<Entity> ents = level.getEntities(p, p.getBoundingBox().inflate(radius), e -> e.isAlive());
			Map<String, List<Entity>> byType = new TreeMap<>();
			for (Entity e : ents) {
				byType.computeIfAbsent(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), k -> new ArrayList<>()).add(e);
			}
			sb.append("Nearby entities (within ").append(radius).append("): ");
			if (byType.isEmpty()) sb.append("none");
			List<String> parts = new ArrayList<>();
			for (var en : byType.entrySet()) {
				List<Entity> list = en.getValue();
				list.sort((a, b) -> Double.compare(a.distanceToSqr(p), b.distanceToSqr(p)));
				StringBuilder part = new StringBuilder(en.getKey()).append(" x").append(list.size()).append(" (");
				for (int i = 0; i < Math.min(3, list.size()); i++) {
					Entity e = list.get(i);
					if (i > 0) part.append(", ");
					if (e.hasCustomName()) part.append('"').append(e.getDisplayName().getString()).append("\" ");
					part.append("at ").append(fmt(e.blockPosition()));
				}
				if (list.size() > 3) part.append(", ...");
				parts.add(part.append(')').toString());
			}
			sb.append(String.join("; ", parts)).append('\n');
		}
		return sb.toString();
	}

	private static String describe(ItemStack s) {
		if (s.isEmpty()) return "nothing";
		return BuiltInRegistries.ITEM.getKey(s.getItem()) + " x" + s.getCount();
	}

	// ---------------------------------------------------------------- run_commands

	private String runCommands(JsonObject args) throws ToolError {
		if (!args.has("commands") || !args.get("commands").isJsonArray()) throw new ToolError("commands must be an array of strings");
		JsonArray cmds = args.getAsJsonArray("commands");
		if (cmds.size() > 2000) throw new ToolError("At most 2000 commands per call.");
		ServerPlayer p = player(args, "player");
		CommandSourceStack base = (p != null ? p.createCommandSourceStack() : server.createCommandSourceStack())
			.withPermission(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(Math.max(0, Math.min(4, cfg.commandPermissionLevel)))));

		StringBuilder sb = new StringBuilder();
		int failures = 0;
		for (int i = 0; i < cmds.size(); i++) {
			String cmd = cmds.get(i).getAsString().trim();
			List<String> out = new ArrayList<>();
			boolean[] result = {false, false}; // [called, success]
			int[] value = {0};
			CommandSource capture = new CommandSource() {
				@Override public void sendSystemMessage(Component c) { out.add(c.getString()); }
				@Override public boolean acceptsSuccess() { return true; }
				@Override public boolean acceptsFailure() { return true; }
				@Override public boolean shouldInformAdmins() { return false; }
			};
			CommandSourceStack src = base.withSource(capture).withCallback((ok, v) -> {
				result[0] = true;
				result[1] = ok;
				value[0] = v;
			});
			server.getCommands().performPrefixedCommand(src, cmd);
			boolean ok = result[0] ? result[1] : out.isEmpty();
			if (!ok) failures++;
			// Keep big batches compact: only show details for failures or commands with output.
			if (cmds.size() > 50 && ok && out.isEmpty()) continue;
			sb.append(i + 1).append(". ").append(cmd).append(" -> ").append(ok ? "ok" : "FAILED");
			if (result[0] && ok) sb.append(" (result ").append(value[0]).append(')');
			if (!out.isEmpty()) sb.append(": ").append(String.join(" | ", out));
			sb.append('\n');
		}
		sb.append(cmds.size()).append(" command(s) run, ").append(failures).append(" failed.");
		return sb.toString();
	}

	// ---------------------------------------------------------------- placement tools

	private String placeBlueprint(JsonObject args) throws ToolError {
		ServerLevel level = level(args);
		BlockPos origin = pos(args, "origin");
		if (!args.has("palette") || !args.has("layers")) throw new ToolError("palette and layers are required");
		BlockParser parser = new BlockParser(level);
		Map<Integer, Pattern> palette = new HashMap<>();
		for (var e : args.getAsJsonObject("palette").entrySet()) {
			if (e.getKey().codePointCount(0, e.getKey().length()) != 1) {
				throw new ToolError("Palette keys must be single characters, got '" + e.getKey() + "'");
			}
			palette.put(e.getKey().codePointAt(0), parser.pattern(e.getValue().getAsString()));
		}
		JsonArray layers = args.getAsJsonArray("layers");
		LinkedHashMap<BlockPos, BlockState> changes = new LinkedHashMap<>();
		java.util.Set<String> unknown = new java.util.TreeSet<>();
		for (int y = 0; y < layers.size(); y++) {
			JsonArray rows = layers.get(y).getAsJsonArray();
			for (int z = 0; z < rows.size(); z++) {
				String row = rows.get(z).getAsString();
				int x = 0;
				for (int i = 0; i < row.length(); ) {
					int cp = row.codePointAt(i);
					i += Character.charCount(cp);
					Pattern st = palette.get(cp);
					if (st != null) {
						changes.put(origin.offset(x, y, z), st.pick());
					} else if (cp != '.' && cp != ' ') {
						unknown.add(new String(Character.toChars(cp)));
					}
					x++;
				}
			}
			checkSize(changes.size());
		}
		if (!unknown.isEmpty()) throw new ToolError("Characters not in palette: " + String.join(" ", unknown) + " (nothing was placed)");
		return queueBuild(level, changes, args);
	}

	private String fillRegion(JsonObject args) throws ToolError {
		ServerLevel level = level(args);
		BlockPos a = pos(args, "from"), b = pos(args, "to");
		BlockParser parser = new BlockParser(level);
		Pattern state = parser.pattern(args.get("block").getAsString());
		String mode = args.has("mode") ? args.get("mode").getAsString() : "solid";
		BlockState air = Blocks.AIR.defaultBlockState();

		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
		checkSize((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1));

		LinkedHashMap<BlockPos, BlockState> changes = new LinkedHashMap<>();
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					boolean edgeXZ = x == x0 || x == x1 || z == z0 || z == z1;
					boolean shell = edgeXZ || y == y0 || y == y1;
					BlockState target = switch (mode) {
						case "solid" -> state.pick();
						case "hollow" -> shell ? state.pick() : air;
						case "outline" -> shell ? state.pick() : null;
						case "walls" -> edgeXZ ? state.pick() : null;
						default -> throw new ToolError("Unknown mode " + mode);
					};
					if (target == null) continue;
					changes.put(new BlockPos(x, y, z), target);
				}
			}
		}
		return queueBuild(level, changes, args);
	}

	private String placeBlocks(JsonObject args) throws ToolError {
		ServerLevel level = level(args);
		if (!args.has("blocks") || !args.get("blocks").isJsonArray()) throw new ToolError("blocks must be an array");
		JsonArray arr = args.getAsJsonArray("blocks");
		checkSize(arr.size());
		BlockParser parser = new BlockParser(level);
		LinkedHashMap<BlockPos, BlockState> changes = new LinkedHashMap<>();
		for (JsonElement e : arr) {
			BlockPos p;
			String block;
			if (e.isJsonArray()) {
				JsonArray a = e.getAsJsonArray();
				if (a.size() != 4) throw new ToolError("Array entries must be [x, y, z, block]");
				p = new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
				block = a.get(3).getAsString();
			} else {
				JsonObject o = e.getAsJsonObject();
				p = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
				block = o.get("block").getAsString();
			}
			changes.put(p, parser.parse(block));
		}
		return queueBuild(level, changes, args);
	}

	private static final long MAX_SHAPE_VOLUME = 4_000_000;

	private String drawShape(JsonObject args) throws ToolError {
		ServerLevel level = level(args);
		String shape = args.has("shape") ? args.get("shape").getAsString() : "";
		Pattern block = new BlockParser(level).pattern(args.get("block").getAsString());
		boolean hollow = args.has("hollow") && args.get("hollow").getAsBoolean();
		List<int[]> voxels;
		if (shape.equals("line")) {
			if (!args.has("points") || !args.get("points").isJsonArray() || args.getAsJsonArray("points").isEmpty()) {
				throw new ToolError("line needs points: [{x,y,z}, ...]");
			}
			List<int[]> pts = new ArrayList<>();
			JsonObject wrap = new JsonObject();
			for (JsonElement e : args.getAsJsonArray("points")) {
				wrap.add("p", e);
				BlockPos p = pos(wrap, "p");
				pts.add(new int[]{p.getX(), p.getY(), p.getZ()});
			}
			voxels = Shapes.line(pts, Math.max(0, optDouble(args, "radius", 0)));
		} else {
			BlockPos c = pos(args, "center");
			double r = optDouble(args, "radius", -1);
			char axis = args.has("axis") ? args.get("axis").getAsString().charAt(0) : 'y';
			if ("xyz".indexOf(axis) < 0) throw new ToolError("axis must be x, y or z");
			Shapes.Shape s = switch (shape) {
				case "sphere", "dome" -> {
					double rx = r, ry = r, rz = r;
					if (args.has("radii")) {
						JsonArray a = args.getAsJsonArray("radii");
						if (a.size() != 3) throw new ToolError("radii must be [rx, ry, rz]");
						rx = a.get(0).getAsDouble(); ry = a.get(1).getAsDouble(); rz = a.get(2).getAsDouble();
					}
					if (rx < 0 || ry < 0 || rz < 0) throw new ToolError(shape + " needs radius or radii");
					yield Shapes.sphere(c.getX(), c.getY(), c.getZ(), rx, ry, rz, shape.equals("dome"));
				}
				case "cylinder" -> Shapes.cylinder(c.getX(), c.getY(), c.getZ(), needRadius(r, shape), needHeight(args, shape), axis);
				case "cone" -> Shapes.cone(c.getX(), c.getY(), c.getZ(), needRadius(r, shape), needHeight(args, shape));
				case "pyramid" -> Shapes.pyramid(c.getX(), c.getY(), c.getZ(), needRadius(r, shape),
					args.has("height") ? needHeight(args, shape) : (int) Math.round(r) + 1);
				case "torus" -> Shapes.torus(c.getX(), c.getY(), c.getZ(), needRadius(r, shape), optDouble(args, "minor_radius", 1), axis);
				default -> throw new ToolError("Unknown shape '" + shape + "'");
			};
			if (s.volume() > MAX_SHAPE_VOLUME) throw new ToolError("Shape is too big (" + s.volume() + " blocks in its bounding box).");
			voxels = s.voxels(hollow);
		}
		checkSize(voxels.size());
		LinkedHashMap<BlockPos, BlockState> changes = new LinkedHashMap<>();
		for (int[] v : voxels) changes.put(new BlockPos(v[0], v[1], v[2]), block.pick());
		return queueBuild(level, changes, args);
	}

	private static double needRadius(double r, String shape) throws ToolError {
		if (r < 0) throw new ToolError(shape + " needs a radius");
		return r;
	}

	private static int needHeight(JsonObject args, String shape) throws ToolError {
		int h = optInt(args, "height", 0);
		if (h == 0) throw new ToolError(shape + " needs a non-zero height");
		return h;
	}

	private static double optDouble(JsonObject o, String key, double def) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : def;
	}

	private String generate(JsonObject args) throws ToolError {
		ServerLevel level = level(args);
		BlockPos a = pos(args, "from"), b = pos(args, "to");
		Pattern block = new BlockParser(level).pattern(args.get("block").getAsString());
		boolean hollow = args.has("hollow") && args.get("hollow").getAsBoolean();
		int x0 = Math.min(a.getX(), b.getX()), y0 = Math.min(a.getY(), b.getY()), z0 = Math.min(a.getZ(), b.getZ());
		int sx = Math.abs(a.getX() - b.getX()) + 1, sy = Math.abs(a.getY() - b.getY()) + 1, sz = Math.abs(a.getZ() - b.getZ()) + 1;
		if ((long) sx * sy * sz > MAX_SHAPE_VOLUME) throw new ToolError("Box is too big (" + (long) sx * sy * sz + " blocks).");
		Shapes.Expr expr;
		try {
			expr = Shapes.parse(args.get("expression").getAsString(), List.of("x", "y", "z", "bx", "by", "bz", "sx", "sy", "sz"));
		} catch (IllegalArgumentException e) {
			throw new ToolError("Bad expression: " + e.getMessage());
		}
		double[] v = new double[]{0, 0, 0, 0, 0, 0, sx, sy, sz};
		Shapes.Solid solid = (x, y, z) -> {
			int bx = x - x0, by = y - y0, bz = z - z0;
			if (bx < 0 || by < 0 || bz < 0 || bx >= sx || by >= sy || bz >= sz) return false;
			v[0] = sx == 1 ? 0 : 2.0 * bx / (sx - 1) - 1;
			v[1] = sy == 1 ? 0 : 2.0 * by / (sy - 1) - 1;
			v[2] = sz == 1 ? 0 : 2.0 * bz / (sz - 1) - 1;
			v[3] = bx; v[4] = by; v[5] = bz;
			double r = expr.eval(v);
			return r != 0 && !Double.isNaN(r);
		};
		List<int[]> voxels = new Shapes.Shape(x0, y0, z0, x0 + sx - 1, y0 + sy - 1, z0 + sz - 1, solid, (char) 0).voxels(hollow);
		checkSize(voxels.size());
		LinkedHashMap<BlockPos, BlockState> changes = new LinkedHashMap<>();
		for (int[] p : voxels) changes.put(new BlockPos(p[0], p[1], p[2]), block.pick());
		return queueBuild(level, changes, args);
	}

	private Object cloneRegion(JsonObject args) throws ToolError {
		ServerLevel level = level(args);
		BlockPos a = pos(args, "from"), b = pos(args, "to"), dest = pos(args, "dest");
		int rot = optInt(args, "rotate", 0);
		Rotation rotation = switch (Math.floorMod(rot, 360)) {
			case 0 -> Rotation.NONE;
			case 90 -> Rotation.CLOCKWISE_90;
			case 180 -> Rotation.CLOCKWISE_180;
			case 270 -> Rotation.COUNTERCLOCKWISE_90;
			default -> throw new ToolError("rotate must be 0, 90, 180 or 270");
		};
		String mir = args.has("mirror") ? args.get("mirror").getAsString() : "none";
		Mirror mirror = switch (mir) {
			case "none", "" -> Mirror.NONE;
			case "x" -> Mirror.FRONT_BACK;
			case "z" -> Mirror.LEFT_RIGHT;
			default -> throw new ToolError("mirror must be none, x or z");
		};
		int repeat = optInt(args, "repeat", 1);
		if (repeat < 1 || repeat > 64) throw new ToolError("repeat must be 1..64");
		boolean includeAir = args.has("include_air") && args.get("include_air").getAsBoolean();
		BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		int sx = Math.abs(a.getX() - b.getX()) + 1, sy = Math.abs(a.getY() - b.getY()) + 1, sz = Math.abs(a.getZ() - b.getZ()) + 1;
		checkSize((long) sx * sy * sz * repeat);
		BlockPos step = dest.subtract(min);

		return afterBuilds(() -> {
			LinkedHashMap<BlockPos, BlockState> changes = new LinkedHashMap<>();
			BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
			for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
				BlockState st = level.getBlockState(mp.set(min.getX() + x, min.getY() + y, min.getZ() + z));
				if (st.isAir() && !includeAir) continue;
				int mx = mirror == Mirror.FRONT_BACK ? sx - 1 - x : x, mz = mirror == Mirror.LEFT_RIGHT ? sz - 1 - z : z;
				// Rotate within the box so the result's minimum corner stays at the origin.
				int tx, tz;
				switch (rotation) {
					case CLOCKWISE_90 -> { tx = sz - 1 - mz; tz = mx; }
					case CLOCKWISE_180 -> { tx = sx - 1 - mx; tz = sz - 1 - mz; }
					case COUNTERCLOCKWISE_90 -> { tx = mz; tz = sx - 1 - mx; }
					default -> { tx = mx; tz = mz; }
				}
				BlockState out = st.mirror(mirror).rotate(rotation);
				for (int k = 1; k <= repeat; k++) {
					changes.put(min.offset(step.getX() * k + tx, step.getY() * k + y, step.getZ() * k + tz), out);
				}
			}
			return queueBuild(level, changes, args);
		});
	}

	/** Must be called on the server thread. Waits for queued builds, then completes once the (animated) revert has finished. */
	public CompletableFuture<String> undo(int steps) {
		return afterBuilds(() -> undoNow(steps));
	}

	private CompletableFuture<String> undoNow(int steps) {
		List<UndoHistory.Entry> entries = undo.pop(Math.max(1, steps));
		if (entries.isEmpty()) return CompletableFuture.completedFuture("Nothing to undo.");
		List<CompletableFuture<String>> parts = new ArrayList<>();
		for (UndoHistory.Entry entry : entries) {
			for (var dimEntry : entry.originals.entrySet()) {
				ServerLevel lvl = server.getLevel(dimEntry.getKey());
				if (lvl != null) parts.add(builds.enqueue(lvl, dimEntry.getValue(), null, 0, null, null));
			}
		}
		int n = entries.size();
		return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).thenApply(v -> {
			StringBuilder sb = new StringBuilder();
			for (CompletableFuture<String> part : parts) sb.append(part.join()).append('\n');
			sb.append("Undid ").append(n).append(" step(s). ").append(undo.depth()).append(" step(s) remain.");
			return sb.toString();
		});
	}

	// ---------------------------------------------------------------- observation tools

	private static final String PALETTE_CHARS =
		"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#@$%&*+=-~^!?<>(){}[]|/:;,'_";

	private static String paletteChar(int index) {
		if (index < PALETTE_CHARS.length()) return String.valueOf(PALETTE_CHARS.charAt(index));
		return new String(Character.toChars(0x00C0 + index - PALETTE_CHARS.length()));
	}

	private String scanArea(JsonObject args) throws ToolError {
		ServerLevel level = level(args);
		BlockPos a = pos(args, "from"), b = pos(args, "to");
		boolean idsOnly = args.has("ignore_properties") && args.get("ignore_properties").getAsBoolean();
		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int y0 = Math.max(level.getMinY(), Math.min(a.getY(), b.getY()));
		int y1 = Math.min(level.getMaxY(), Math.max(a.getY(), b.getY()));
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
		long volume = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
		if (volume > cfg.maxScanVolume) {
			throw new ToolError("Scan volume " + volume + " exceeds limit " + cfg.maxScanVolume + ". Scan a smaller box or use get_terrain.");
		}

		Map<String, String> palette = new LinkedHashMap<>();
		StringBuilder body = new StringBuilder();
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		for (int y = y0; y <= y1; y++) {
			StringBuilder layer = new StringBuilder();
			boolean allAir = true;
			for (int z = z0; z <= z1; z++) {
				StringBuilder row = new StringBuilder();
				for (int x = x0; x <= x1; x++) {
					BlockState st = level.getBlockState(mp.set(x, y, z));
					if (st.isAir()) {
						row.append('.');
						continue;
					}
					allAir = false;
					String key = idsOnly ? BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString() : BlockStateParser.serialize(st);
					row.append(palette.computeIfAbsent(key, k -> paletteChar(palette.size())));
				}
				layer.append("  ").append(row).append('\n');
			}
			body.append("y=").append(y).append(" (layer ").append(y - y0).append(")");
			if (allAir) body.append(": all air\n");
			else body.append(":\n").append(layer);
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Region x ").append(x0).append("..").append(x1).append(", y ").append(y0).append("..").append(y1)
			.append(", z ").append(z0).append("..").append(z1).append(" in ").append(dimName(level)).append('\n');
		sb.append("Blueprint origin = ").append(x0).append(' ').append(y0).append(' ').append(z0)
			.append(". Layers bottom to top, rows go along +z (first row z=").append(z0)
			.append("), characters go along +x (first char x=").append(x0).append("). '.' = air.\n");
		sb.append("Palette:\n");
		palette.forEach((k, v) -> sb.append("  ").append(v).append(" = ").append(k).append('\n'));
		sb.append(body);
		return sb.toString();
	}

	private String getTerrain(JsonObject args) throws ToolError {
		ServerLevel level = level(args);
		JsonObject f = args.getAsJsonObject("from"), t = args.getAsJsonObject("to");
		int x0 = Math.min(f.get("x").getAsInt(), t.get("x").getAsInt()), x1 = Math.max(f.get("x").getAsInt(), t.get("x").getAsInt());
		int z0 = Math.min(f.get("z").getAsInt(), t.get("z").getAsInt()), z1 = Math.max(f.get("z").getAsInt(), t.get("z").getAsInt());
		int step = Math.max(1, optInt(args, "step", 1));
		long cells = (long) ((x1 - x0) / step + 1) * ((z1 - z0) / step + 1);
		if (cells > 10_000) throw new ToolError("Area too large (" + cells + " columns). Use a larger step or a smaller area.");

		Map<String, String> palette = new LinkedHashMap<>();
		StringBuilder heights = new StringBuilder();
		StringBuilder blocks = new StringBuilder();
		int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		for (int z = z0; z <= z1; z += step) {
			StringBuilder hRow = new StringBuilder();
			StringBuilder bRow = new StringBuilder();
			for (int x = x0; x <= x1; x += step) {
				// level.getHeight() reports the world floor for unloaded chunks, so load the chunk explicitly.
				int top = level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
				min = Math.min(min, top);
				max = Math.max(max, top);
				BlockState st = level.getBlockState(mp.set(x, top, z));
				String key = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
				if (hRow.length() > 0) hRow.append(' ');
				hRow.append(top);
				bRow.append(palette.computeIfAbsent(key, k -> paletteChar(palette.size())));
			}
			heights.append(hRow).append('\n');
			blocks.append(bRow).append('\n');
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Terrain x ").append(x0).append("..").append(x1).append(", z ").append(z0).append("..").append(z1);
		if (step > 1) sb.append(" (every ").append(step).append(" blocks)");
		sb.append(" in ").append(dimName(level)).append(". Rows go along +z (north to south), columns along +x (west to east).\n");
		sb.append("Surface Y ranges ").append(min).append("..").append(max).append(". Each value is the Y of the top ground or liquid block; build on Y+1.\n");
		sb.append("Heights:\n").append(heights);
		sb.append("Surface blocks:\n");
		palette.forEach((k, v) -> sb.append("  ").append(v).append(" = ").append(k).append('\n'));
		sb.append(blocks);
		return sb.toString();
	}

}
