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
import net.minecraft.tags.BlockTags;
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
public class GameTools implements McpServer.Tools {
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

	/** A tool result with pictures: PNGs shown to Claude, followed by the text. */
	record Images(List<byte[]> pngs, String text) {}

	private final MinecraftServer server;
	private final Config cfg;
	private final UndoHistory undo;
	private final BuildQueue builds;
	private final Map<String, Tool> tools = new LinkedHashMap<>();
	private final AtomicInteger buildIds = new AtomicInteger();

	/** State for one concurrent Claude session. Its tool calls say which session they're from (see {@link McpServer}). */
	private static final class Session {
		final int id;
		/** Name of the player whose message this session is currently handling. */
		volatile String player;
		/** Results of background builds, handed to Claude with its next tool result. */
		final ConcurrentLinkedQueue<String> reports = new ConcurrentLinkedQueue<>();
		/** Builds run in order, so this session's newest build finishing means all of its builds are done. */
		volatile CompletableFuture<String> lastBuild;

		Session(int id) { this.id = id; }
	}

	private final Map<Integer, Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();
	/** The session whose tool is running on the server thread right now; null for player commands. Server thread only. */
	private Session active;

	public GameTools(MinecraftServer server, Config cfg, UndoHistory undo, BuildQueue builds) {
		this.server = server;
		this.cfg = cfg;
		this.undo = undo;
		this.builds = builds;
		registerAll();
	}

	private Session session(int id) {
		return sessions.computeIfAbsent(id, Session::new);
	}

	/** The session a tool call is running for; tests that call handlers directly get session 0. */
	private Session active() {
		return active != null ? active : session(0);
	}

	public void setDefaultPlayer(int session, String name) {
		session(session).player = name;
	}

	public UndoHistory undoHistory() {
		return undo;
	}

	/** Whether blocks this session queued are still going up. Any thread. */
	public boolean isBuilding(int session) {
		CompletableFuture<String> l = session(session).lastBuild;
		return l != null && !l.isDone();
	}

	/** Completes once every build this session queued so far has been placed (or cancelled). Any thread. */
	public CompletableFuture<Void> buildsIdle(int session) {
		CompletableFuture<String> l = session(session).lastBuild;
		return l == null ? CompletableFuture.completedFuture(null) : l.handle((r, e) -> null);
	}

	// ---------------------------------------------------------------- MCP plumbing

	@Override
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

	/** Run a tool's handler directly on the current (server) thread, as session 0. For tests. */
	Object runTool(String name, JsonObject args) throws Exception {
		return tools.get(name).handler().run(args);
	}

	@Override
	public JsonObject call(int sessionId, String name, JsonObject args) {
		Session session = session(sessionId);
		Tool tool = tools.get(name);
		String text;
		List<byte[]> images = List.of();
		boolean error = false;
		if (tool == null) {
			text = "Unknown tool: " + name;
			error = true;
		} else {
			try {
				Object result = server.submit(() -> {
					Session prev = active;
					active = session;
					try {
						return tool.handler().run(args);
					} catch (RuntimeException e) {
						throw e;
					} catch (Exception e) {
						throw new WrappedException(e);
					} finally {
						active = prev;
					}
				}).get(120, TimeUnit.SECONDS);
				// Animated builds complete on a later tick; wait here (off the server thread) until they're done.
				Object out = result instanceof CompletableFuture<?> f ? f.get(30, TimeUnit.MINUTES) : result;
				if (out instanceof Images im) {
					images = im.pngs();
					text = im.text();
				} else {
					text = (String) out;
				}
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
		return toolResult(text + drainReports(sessionId), images, error);
	}

	/** Background build results this session hasn't been told about yet, ready to append to a tool result. */
	public String drainReports(int sessionId) {
		StringBuilder finished = new StringBuilder();
		for (String r; (r = session(sessionId).reports.poll()) != null; ) finished.append('\n').append(r);
		return finished.isEmpty() ? "" : "\n\nFinished in the background:" + finished;
	}

	/** An MCP tools/call result: pictures first, then the text. */
	public static JsonObject toolResult(String text, List<byte[]> images, boolean error) {
		JsonObject result = new JsonObject();
		JsonArray content = new JsonArray();
		for (byte[] png : images) {
			JsonObject img = new JsonObject();
			img.addProperty("type", "image");
			img.addProperty("data", java.util.Base64.getEncoder().encodeToString(png));
			img.addProperty("mimeType", "image/png");
			content.add(img);
		}
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
	private static final String AREA_SCHEMA = """
		{"type":"object","properties":{"x":{"type":"integer"},"z":{"type":"integer"}},"required":["x","z"]}""";
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
				+ "torus (center, major radius, minor radius, axis of the hole), line (points: a polyline, radius 0 = thin), "
				+ "curve (points: a smooth spline through every point, like WorldEdit //curve; for arches, cables, curved legs). "
				+ "hollow draws only a 1-block shell and leaves the inside untouched; hollow cylinders, cones and pyramids "
				+ "are open at the ends/base (tubes, roofs), while hollow lines and curves are closed shells, e.g. a two-point "
				+ "line with radius 3 is a capsule. Coordinates are block positions; radii may be decimals." + QUEUED,
			"""
				{"type":"object","properties":{
				  "shape":{"type":"string","enum":["sphere","dome","cylinder","cone","pyramid","torus","line","curve"]},
				  "center":%s,
				  "radius":{"type":"number"},
				  "radii":{"type":"array","items":{"type":"number"},"description":"sphere/dome only: [rx, ry, rz]."},
				  "height":{"type":"integer","description":"cylinder, cone, pyramid. For cylinders along x or z, the length."},
				  "minor_radius":{"type":"number","description":"torus tube radius (default 1)."},
				  "axis":{"type":"string","enum":["y","x","z"],"description":"cylinder and torus orientation (default y)."},
				  "points":{"type":"array","items":%s,"description":"line and curve: two or more points."},
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

		register("overlay",
			"Cover the ground of a horizontal area, following the terrain like WorldEdit //overlay. Each column's ground is its "
				+ "top solid block, ignoring leaves, plants and snow layers; columns topped by water, lava or a tree trunk are "
				+ "skipped. mode above (default) places the block on top of the ground: snow, moss carpet, scattered flowers "
				+ "(\"92%minecraft:air,8%minecraft:poppy\"). mode replace swaps the top depth blocks of the ground itself: a "
				+ "minecraft:dirt_path or gravel road, a sandy clearing. Waits for queued builds first, so it sees them." + QUEUED,
			"""
				{"type":"object","properties":{
				  "from":%s,"to":%s,
				  "block":%s,
				  "mode":{"type":"string","enum":["above","replace"]},
				  "depth":{"type":"integer","description":"replace mode: how many ground blocks to replace (default 1)."},
				  "duration_seconds":%s,
				  "dimension":%s
				},"required":["from","to","block"]}""".formatted(AREA_SCHEMA, AREA_SCHEMA, PATTERN_SCHEMA, DURATION_SCHEMA, DIM_SCHEMA),
			args -> afterBuilds(() -> overlay(args)));

		register("smooth_terrain",
			"Smooth the ground's height over a horizontal area, like WorldEdit //smooth: bumps come down and dips fill in so "
				+ "slopes become gentle and blend into the surrounding land. Each column keeps its own surface and filler blocks. "
				+ "More iterations (default 2, max 10) give smoother ground. Trees and builds on changed ground aren't moved, so "
				+ "clear them first or use a mask. Water columns are left alone. Good for prepping a build site or tidying "
				+ "terrain after digging. Waits for queued builds first." + QUEUED,
			"""
				{"type":"object","properties":{
				  "from":%s,"to":%s,
				  "iterations":{"type":"integer"},
				  "duration_seconds":%s,
				  "dimension":%s
				},"required":["from","to"]}""".formatted(AREA_SCHEMA, AREA_SCHEMA, DURATION_SCHEMA, DIM_SCHEMA),
			args -> afterBuilds(() -> smoothTerrain(args)));

		register("naturalize",
			"Make the ground in a box look natural, like WorldEdit //naturalize. In each column, the topmost exposed natural "
				+ "ground block becomes top (default minecraft:grass_block), the next depth blocks (default 3) become under "
				+ "(default minecraft:dirt), and deeper ones become deep (default minecraft:stone). Only natural terrain changes "
				+ "(dirt and grass types, stone types, sand, gravel); builds, ores and anything under water are left alone. "
				+ "Use it after smoothing or reshaping terrain; top minecraft:sand with under minecraft:sandstone makes a beach. "
				+ "Waits for queued builds first." + QUEUED,
			"""
				{"type":"object","properties":{
				  "from":%s,"to":%s,
				  "top":%s,"under":%s,"deep":%s,
				  "depth":{"type":"integer"},
				  "duration_seconds":%s,
				  "dimension":%s
				},"required":["from","to"]}""".formatted(POS_SCHEMA, POS_SCHEMA, PATTERN_SCHEMA, PATTERN_SCHEMA, PATTERN_SCHEMA,
				DURATION_SCHEMA, DIM_SCHEMA),
			args -> afterBuilds(() -> naturalize(args)));

		register("wait_for_builds",
			"Wait until every queued build has finished animating, then return their results. Call it before your final "
				+ "message so you can fix anything that failed and so you don't announce a build that's still going up.",
			"""
				{"type":"object","properties":{}}""",
			args -> afterBuilds(() -> "All builds are finished."));

		register("view_build",
			"See a rendered picture of a box (inclusive corners), to judge how a build looks: proportions, silhouette, colors, "
				+ "symmetry, gaps. Blocks are drawn as flat-shaded cubes in their map colors, without textures: slabs, stairs and "
				+ "fences look like full cubes, while glass, plants, torches and other blocks you can walk through aren't drawn. "
				+ "views: southeast, southwest, northwest, northeast (isometric, named for where the camera stands) and top "
				+ "(plan view, north up, higher blocks brighter). Default: southeast. Include a layer of ground under the build. "
				+ "Each picture costs about a thousand tokens of context, so use one or two views per look. Waits for queued "
				+ "builds to finish first.",
			"""
				{"type":"object","properties":{
				  "from":%s,"to":%s,
				  "views":{"type":"array","items":{"type":"string","enum":["southeast","southwest","northwest","northeast","top"]}},
				  "dimension":%s
				},"required":["from","to"]}""".formatted(POS_SCHEMA, POS_SCHEMA, DIM_SCHEMA),
			this::viewBuild);

		String commandsNote = " Commands may name custom things as custom:<id>, as in run_commands; @target stands for the other entity involved.";
		register("define_item",
			"Create a custom item, or update one by reusing its id. It appears for every player right away, drawn from "
				+ "your voxel model with real lighting. Draw flat items as pixel art in pixels (rows top to bottom, characters "
				+ "left to right, like a 16x16 vanilla item sprite, extruded thickness voxels deep), or give a 3D model in layers "
				+ "(place_blueprint format: layers bottom to top, rows along +z, characters along +x). palette maps characters "
				+ "to #RRGGBB or #AARRGGBB colors (alpha below FF is see-through); '.' and ' ' are empty. Up to 32 voxels per "
				+ "side; 16 is one item's normal size and bigger models are scaled to fit. style picks how it's held: item "
				+ "(flat items like food, gems, potions), handheld (tools and weapons, held like a sword; draw them diagonally, "
				+ "bottom-left to top-right like vanilla), block (chunky 3D objects held like a block). properties set behavior: "
				+ "weapons, food, tools that really mine (tool), wearable armor (armor), launchers and throwables (shoot), "
				+ "commands on right-click, on hit, on a block or creature, and every second while held or worn, fuel, anvil "
				+ "repair, enchanting, and crafting recipes. Updating a definition updates every copy, including ones players "
				+ "already have." + commandsNote,
			"""
				{"type":"object","properties":{
				  "id":{"type":"string","description":"Lowercase letters, digits and underscores, e.g. ruby_sword."},
				  "name":{"type":"string"},
				  "description":{"type":"string","description":"Tooltip text; newlines make extra lines."},
				  "style":{"type":"string","enum":["item","handheld","block"]},
				  "palette":{"type":"object","additionalProperties":{"type":"string"}},
				  "pixels":{"type":"array","items":{"type":"string"}},
				  "thickness":{"type":"integer","description":"pixels only: depth in voxels (default 1)."},
				  "layers":{"type":"array","items":{"type":"array","items":{"type":"string"}}},
				  "properties":{"type":"object","properties":{
				    "max_stack":{"type":"integer","description":"1 to 99, default 64."},
				    "durability":{"type":"integer","description":"Uses before it breaks; makes it unstackable."},
				    "attack_damage":{"type":"number","description":"Total melee damage, e.g. 7 like a diamond sword."},
				    "attack_speed":{"type":"number","description":"Attacks per second, e.g. 1.6 like a sword."},
				    "food":{"type":"object","properties":{"nutrition":{"type":"integer"},"saturation":{"type":"number"},"always_edible":{"type":"boolean"}}},
				    "tool":{"type":"object","description":"Mines like a vanilla tool of this type and tier (correct drops, speed).","properties":{
				      "type":{"type":"string","enum":%s},"tier":{"type":"string","enum":%s},"speed":{"type":"number","description":"Mining speed; default from tier (iron 6, diamond 8)."}}},
				    "armor":{"type":"object","description":"Wearable in a slot: right-click to put on.","properties":{
				      "slot":{"type":"string","enum":%s},"armor":{"type":"number","description":"Armor points; a diamond chestplate has 8, helmet 3."},
				      "toughness":{"type":"number"},"knockback_resistance":{"type":"number","description":"0 to 1."},
				      "look":{"type":"string","enum":%s,"description":"How it looks when worn: worn draws your worn parts (the default when you give them); model shows the item's own model on the head, one block big (head slot only); the others reuse that vanilla armor's look. Otherwise: model for head, leather elsewhere."},
				      "worn":{"type":"object","description":"Your own design for how it looks on the wearer, at any size: a tall top hat, a tank top, a crown, a cape, wings, a backpack, sneakers. Voxel parts in the wearer's space, like define_morph's: x = 0, z = 0 their center, y = 0 their feet, +z the way they face and +x their left, 16 voxels per block, so one voxel is one skin pixel. The body underneath: head 8x8x8 from -4 24 -4, body 8x12x4 from -4 12 -2, right arm 4x12x4 from -8 12 -2, left arm from 4 12 -2, right leg 4x12x4 from -4 0 -2, left leg from 0 0 -2. Clothes are a 1-voxel shell around that (a shirt's body is 10x12x6 from -5 12 -3, hollow). Give each part attach so it moves with that body part. Works on players, mobs and armor stands. Check it with view_item worn: true.","properties":{
				        "palette":{"type":"object","additionalProperties":{"type":"string"},"description":"Default: the item's palette."},
				        "parts":{"type":"array","items":{"type":"object","properties":{
				          "layers":{"type":"array","items":{"type":"array","items":{"type":"string"}}},
				          "origin":{"type":"array","items":{"type":"integer"},"description":"Where its layers start: the minimum corner."},
				          "attach":{"type":"string","enum":["head","body","right_arm","left_arm","right_leg","left_leg"]},
				          "pivot":{"type":"array","items":{"type":"number"}},"role":{"type":"string","enum":["none","wing","tail"],"description":"Optional motion of its own: wing flaps, tail sways."}
				        },"required":["layers","origin"]}}
				      }},
				      "color":{"type":"string","description":"#RRGGBB tint for the leather look; default is the model's average color."}}},
				    "shoot":%s,
				    "use_commands":{"type":"array","items":{"type":"string"},"description":"Run as the player on right-click (or when eaten, for food). @s is the player, ~ ~ ~ their position."},
				    "use_on_block_commands":{"type":"array","items":{"type":"string"},"description":"Run as the player when they right-click a block with it; ~ ~ ~ is the block."},
				    "use_on_entity_commands":{"type":"array","items":{"type":"string"},"description":"Run as the player when they right-click a creature with it; @target is the creature, ~ ~ ~ its position."},
				    "hit_commands":{"type":"array","items":{"type":"string"},"description":"Run as the player when they hit something with it; @target is what was hit, ~ ~ ~ its position."},
				    "held_commands":{"type":"array","items":{"type":"string"},"description":"Run as the player every second while it's in a hand, e.g. effect give @s night_vision 3 0 true."},
				    "worn_commands":{"type":"array","items":{"type":"string"},"description":"Run as the player every second while it's worn (armor only)."},
				    "cooldown_seconds":{"type":"number"},
				    "glint":{"type":"boolean"},
				    "rarity":{"type":"string","enum":["common","uncommon","rare","epic"]},
				    "fuel_seconds":{"type":"number","description":"Burns in a furnace this long; coal is 80."},
				    "repair_with":{"type":"array","items":{"type":"string"},"description":"Items or #tags that repair it in an anvil, e.g. [\\"minecraft:diamond\\"]."},
				    "enchantability":{"type":"integer","description":"Makes it enchantable at an enchanting table; iron is 14, gold 22. It takes the enchantments of the vanilla items it behaves like (sword, tool, armor piece, bow)."},
				    "recipes":%s
				  }}
				},"required":["id","name","palette"]}""".formatted(json(CustomItems.TOOL_TYPES), json(CustomItems.TIERS), json(CustomItems.ARMOR_SLOTS),
				json(CustomItems.ARMOR_LOOKS), SHOOT_SCHEMA, RECIPES_SCHEMA),
			this::defineItem);

		register("define_block",
			"Create a custom block, or update one by reusing its id. Every player sees it right away. Either paint a full "
				+ "cube like a vanilla block with faces (16 rows of 16 characters per face, seen from outside with up toward the "
				+ "sky; give all, or side plus top and bottom, or individual north/south/east/west/top/bottom), or sculpt any "
				+ "shape in layers (place_blueprint format, at most 16 voxels per side; 16 fills the block, smaller models sit "
				+ "on the floor centered, e.g. a lamp, a statue, a crystal cluster). palette maps characters to #RRGGBB or "
				+ "#AARRGGBB colors (alpha below FF is see-through, like glass). Place it with any placement tool as "
				+ "custom:<id> (patterns and masks too), or give it with give_item. properties: light (0 to 15), hardness "
				+ "(seconds-ish to mine; 1.5 is stone, 0 instant, -1 unbreakable), sound, collision (model: the voxel shape, "
				+ "full, or none to walk through), rotate (turns to face whoever places it; the model's south side is its "
				+ "front), the tool that mines it and what it drops (ores: tool pickaxe, tier iron, drops custom:ruby 1-2, xp), "
				+ "storage (a chest-like inventory), redstone (power emitted, commands when powered or unpowered), commands "
				+ "on right-click, when stepped on and on random ticks (about once a minute per block, for growth or "
				+ "spreading), natural generation as an ore in the world (generate), and recipes. Animated blocks like fire, "
				+ "portals, lava or blinking machines: give a palette color a list of colors to cycle through (one per frame; "
				+ "several letters cycling the same colors at shifted starting points make swirls and flowing bands, which is "
				+ "cheap and looks great), and/or frames (whole faces or layers drawings to step through, e.g. 4 flickering "
				+ "flame pictures). frame_time is game ticks per frame (default 2; 20 ticks is a second). shape cross draws "
				+ "faces.cross (16x16, '.' see-through) on two crossed diagonal planes like flowers and fire. Lively touches: "
				+ "glow (full brightness in the dark), particles (flame, smoke, portal, soul_fire_flame, end_rod, dust with a "
				+ "color...), ambient_sound, burns (sets things alight like fire), portal (stand in it to travel to a "
				+ "dimension and back, like a nether portal; a matching return portal is built on the far side) and "
				+ "inside_commands. Updating changes blocks already placed; light applies to blocks placed afterwards. Up to "
				+ CustomBlocks.SLOTS + " block designs per world."
				+ commandsNote,
			"""
				{"type":"object","properties":{
				  "id":{"type":"string","description":"Lowercase letters, digits and underscores, e.g. ruby_ore."},
				  "name":{"type":"string"},
				  "description":{"type":"string"},
				  "palette":{"type":"object","additionalProperties":{"anyOf":[{"type":"string"},{"type":"array","items":{"type":"string"}}]},
				    "description":"Character to color. A list of colors cycles one per animation frame, e.g. \\"p\\": [\\"#5A1A9A\\", \\"#7B2FD0\\", \\"#A060FF\\", \\"#7B2FD0\\"]."},
				  "faces":{"type":"object","additionalProperties":{"type":"array","items":{"type":"string"}}},
				  "layers":{"type":"array","items":{"type":"array","items":{"type":"string"}}},
				  "shape":{"type":"string","enum":["cube","cross"],"description":"cross: faces.cross drawn on two crossed diagonal planes (flowers, fire, cobwebs). Default cube."},
				  "frames":{"type":"array","items":{"type":"object","properties":{
				    "faces":{"type":"object","additionalProperties":{"type":"array","items":{"type":"string"}}},
				    "layers":{"type":"array","items":{"type":"array","items":{"type":"string"}}}}},
				    "description":"Animation frames, each a whole drawing (faces or layers, same palette). Replaces the top-level faces/layers."},
				  "frame_time":{"type":"integer","description":"Game ticks per animation frame, default 2 (10 frames a second)."},
				  "desync":{"type":"boolean","description":"Each placed block starts its animation at a different point (good for fire fields; leave off for portals so their patterns line up)."},
				  "properties":{"type":"object","properties":{
				    "light":{"type":"integer"},
				    "glow":{"type":"boolean","description":"Drawn at full brightness even in the dark, like lava. Pair with light."},
				    "particles":{"type":"array","items":{"type":"object","properties":{
				      "type":{"type":"string","description":"flame, small_flame, soul_fire_flame, smoke, large_smoke, campfire_cosy_smoke, portal, reverse_portal, enchant, end_rod, electric_spark, glow, dripping_lava, dripping_water, falling_water, snowflake, cherry_leaves, spore_blossom_air, firefly, note, heart, happy_villager, or dust (with color)."},
				      "where":{"type":"string","enum":%s,"description":"inside the model (default), rising off the top, or drifting off the sides."},
				      "count":{"type":"integer","description":"Per burst, default 1 (a nether portal uses 4). Bursts come about twice a second per block near players."},
				      "chance":{"type":"number","description":"Chance of each burst, 0 to 1 (default 1)."},
				      "speed":{"type":"number","description":"Default 0.02."},
				      "color":{"type":"string"},"size":{"type":"number"}}}},
				    "ambient_sound":{"type":"object","properties":{"sound":{"type":"string","description":"e.g. block.fire.ambient, block.portal.ambient, block.lava.pop, block.beacon.ambient."},"chance":{"type":"number","description":"Per burst, default 0.05."},"volume":{"type":"number"}}},
				    "burns":{"description":"true (or damage per hit) to set entities inside it on fire and hurt them, like fire. Use collision none."},
				    "portal":{"type":"string","description":"Dimension it leads to (a custom dimension id, the_nether, the_end or overworld). Standing in it travels there after a few seconds (instantly in creative); in that dimension it leads back to the overworld. Use collision none and a frame of other blocks around it."},
				    "inside_commands":{"type":"array","items":{"type":"string"},"description":"Run as whatever is inside the block (@s), at most once a second each; use collision none."},
				    "hardness":{"type":"number"},
				    "sound":{"type":"string","enum":%s},
				    "collision":{"type":"string","enum":["model","full","none"]},
				    "rotate":{"type":"boolean","description":"Face the player who places it."},
				    "tool":{"type":"string","enum":%s,"description":"The tool that mines it fast. With requires_tool (default true when tool is set), nothing drops without it."},
				    "tier":{"type":"string","enum":%s,"description":"Pickaxe tier needed, e.g. iron for diamond-like ores."},
				    "requires_tool":{"type":"boolean"},
				    "drops":{"type":"array","items":{"type":"string"},"description":"Instead of itself, e.g. [\\"custom:ruby 1-2\\"]. Silk Touch still drops the block."},
				    "xp":{"description":"Experience when mined: a number or [min, max]."},
				    "storage":{"type":"integer","description":"Rows of inventory (1 to 6, a chest has 3); right-click opens it."},
				    "power":{"type":"integer","description":"Redstone power it gives off, 0 to 15 (a redstone block is 15)."},
				    "powered_commands":{"type":"array","items":{"type":"string"},"description":"Run at the block when it receives redstone power."},
				    "unpowered_commands":{"type":"array","items":{"type":"string"},"description":"Run at the block when it loses redstone power."},
				    "use_commands":{"type":"array","items":{"type":"string"},"description":"Run as the player who right-clicks it, at the block (~ ~ ~)."},
				    "step_commands":{"type":"array","items":{"type":"string"},"description":"Run as whatever steps on it (@s), at most once a second each."},
				    "random_tick_commands":{"type":"array","items":{"type":"string"},"description":"Run at the block (~ ~ ~) on random ticks, about once a minute; e.g. grow: setblock ~ ~1 ~ custom:crystal keep."},
				    "fuel_seconds":{"type":"number","description":"Burns in a furnace this long."},
				    "generate":{"type":"object","description":"Generate it as ore veins in the world: in new chunks and in loaded chunks near players that don't have it yet.","properties":{
				      "replace":{"type":"array","items":{"type":"string"},"description":"Blocks or #tags it may replace. Default: stone and deepslate (#minecraft:stone_ore_replaceables, #minecraft:deepslate_ore_replaceables). netherrack for the Nether."},
				      "min_y":{"type":"integer"},"max_y":{"type":"integer"},
				      "veins_per_chunk":{"type":"integer","description":"Default 6; iron is about 10, diamonds about 4."},
				      "vein_size":{"type":"integer","description":"Blocks per vein, default 6."},
				      "biomes":{"type":"array","items":{"type":"string"}},
				      "dimension":{"type":"string","description":"Default minecraft:overworld."}}},
				    "recipes":%s
				  }}
				},"required":["id","name","palette"]}""".formatted(json(CustomBlocks.PARTICLE_PLACES), json(CustomBlocks.SOUNDS), json(CustomBlocks.TOOLS), json(CustomItems.TIERS), RECIPES_SCHEMA),
			this::defineBlock);

		register("define_entity",
			"Create a custom creature, or update one by reusing its id (creatures already spawned change too). Build it from "
				+ "voxel parts that are animated by role: body (still), head (turns to look around), leg (swings while walking; "
				+ "legs with the same phase move together, so give four legs diagonal pairs), arm (swings opposite the legs), "
				+ "wing (flaps), tail (sways), none (still). Coordinates are voxels in the creature's own space: x = 0, z = 0 "
				+ "is its center, y = 0 its feet, +z is the way it faces (its front) and +x its left side. Each part has "
				+ "layers (place_blueprint format: layers bottom to top, rows along +z, characters along +x), origin (where "
				+ "its layers start: the minimum corner) and pivot (the point it rotates around: a hip, shoulder, neck or wing "
				+ "root; default the top center of the part). One palette of #RRGGBB / #AARRGGBB colors is shared by all parts. "
				+ "scale is voxels per block (default 16, like vanilla mobs: a pig is about 14 long and 14 tall). The hitbox "
				+ "comes from the model. Up to " + EntityModel.MAX_PARTS + " parts and " + EntityModel.MAX_SPAN + " voxels per side. "
				+ "properties make it a real mob: stats and behavior, walking, flying, swimming or hopping, food it follows "
				+ "and breeds with (babies grow up), taming (tamed ones follow, sit when their owner right-clicks, and defend "
				+ "them), riding, ranged attacks, timed abilities, commands when it attacks, is hurt or dies, natural "
				+ "spawning in biomes, and recipes for its spawn egg. give_item with its id gives its spawn egg." + commandsNote,
			"""
				{"type":"object","properties":{
				  "id":{"type":"string","description":"Lowercase letters, digits and underscores, e.g. frost_wolf."},
				  "name":{"type":"string"},
				  "palette":{"type":"object","additionalProperties":{"type":"string"}},
				  "parts":{"type":"array","items":{"type":"object","properties":{
				    "name":{"type":"string"},
				    "role":{"type":"string","enum":["body","head","leg","arm","wing","tail","none"]},
				    "origin":{"type":"array","items":{"type":"integer"}},
				    "pivot":{"type":"array","items":{"type":"number"}},
				    "phase":{"type":"integer","description":"legs and arms: 0 or 1."},
				    "layers":{"type":"array","items":{"type":"array","items":{"type":"string"}}}
				  },"required":["origin","layers"]}},
				  "scale":{"type":"number"},
				  "properties":{"type":"object","properties":{
				    "health":{"type":"number","description":"Default 10 (a pig); a zombie has 20."},
				    "speed":{"type":"number","description":"Default 0.25 (a pig); 0.35 is quick."},
				    "behavior":{"type":"string","enum":["passive","neutral","hostile"],"description":"passive flees when hurt, neutral fights back, hostile hunts players."},
				    "attack_damage":{"type":"number"},
				    "movement":{"type":"string","enum":%s,"description":"swim creatures live in water; hop ones bounce along like rabbits."},
				    "sounds":{"type":"string","description":"Borrow a vanilla mob's sounds, e.g. pig, wolf, parrot, zombie."},
				    "drops":{"type":"array","items":{"type":"string"},"description":"e.g. \\"minecraft:bone 0-2\\", \\"custom:ruby\\"."},
				    "food":{"type":"array","items":{"type":"string"},"description":"Items it follows and breeds with (feed two adults to get a baby), and that heal it once tamed, e.g. [\\"minecraft:wheat\\"]."},
				    "tame":{"type":"object","description":"Make it tamable: feed it these until it's tamed (chance per item, default 0.33).","properties":{
				      "items":{"type":"array","items":{"type":"string"}},"chance":{"type":"number"}}},
				    "rideable":{"type":"boolean","description":"Players (its owner, if tamable) ride it by right-clicking; it goes where they look. Flying mounts climb with jump."},
				    "vehicle":{"type":"object","description":"Make it a vehicle instead of a creature: no AI (it never wanders, flees or fights), players get in by right-clicking and drive it, and it moves with its own physics. thrust controls are for rockets, jetpacks, hover-bikes, spaceships and hot-air balloons: hold jump to thrust up, W/A/S/D push it forward, left, back and right, it turns to face where the rider looks, gravity pulls it back down and it leans the way it moves. drive controls are for cars, karts, tractors and buggies: W/S drive and reverse, A/D steer, it stays on the ground and climbs single blocks.","properties":{
				      "controls":{"type":"string","enum":["thrust","drive"]},
				      "max_speed":{"type":"number","description":"Blocks per second. Default 12 (thrust) or 10 (drive); a sprinting player is about 6."},
				      "acceleration":{"type":"number","description":"How fast WASD speed it up, blocks per second squared. Default 12 (thrust) or 8 (drive)."},
				      "thrust":{"type":"number","description":"thrust: upward push while holding jump, blocks per second squared. Default 28; it must beat gravity to climb."},
				      "gravity":{"type":"number","description":"Blocks per second squared. Default 16 (thrust: it sinks back down when you let go) or 32 (drive); 0 makes a thrust vehicle hover."},
				      "tilt":{"type":"number","description":"Degrees it leans at top speed: banks toward where it drifts, tips toward where it's going. Default 25 (thrust) or 0 (drive)."},
				      "turn":{"type":"number","description":"thrust: how quickly it turns to where the rider looks, 0 to 1 (default 0.15). drive: degrees per tick at speed (default 4)."},
				      "exhaust":{"type":"string","description":"An effect (from define_effect) played under it while the rider thrusts (thrust) or presses forward (drive): flames, smoke. Make it short (0.2 to 0.4 seconds, it repeats) with trail: true particles so the smoke stays behind."},
				      "exhaust_at":{"type":"array","items":{"type":"number"},"description":"Where the exhaust comes out, in the model's voxels (default 0 0 0, the bottom center); e.g. 0 2 -10 for a car's tailpipe."}}},
				    "ranged":{"type":"object","description":"Attack from range instead of melee (neutral or hostile).","properties":{
				      "projectile":{"type":"string","description":"beam (instant ray), arrow, fireball, small_fireball, wind_charge, or an item id like minecraft:snowball or custom:<id> that flies as that item."},
				      "effect":{"type":"string","description":"Effect drawn along a beam, or carried by a flying item."},"hit_effect":{"type":"string"},
				      "damage":{"type":"number"},"speed":{"type":"number"},"interval_seconds":{"type":"number","description":"Default 2."},
				      "range":{"type":"number","description":"Default 16 blocks."},"gravity":{"type":"boolean"},
				      "hit_commands":{"type":"array","items":{"type":"string"}}}},
				    "abilities":{"type":"array","description":"Commands it runs every so often, as itself (@s) where it stands; @target is what it's fighting.","items":{"type":"object","properties":{
				      "every_seconds":{"type":"number"},"when":{"type":"string","enum":["always","fighting","idle"]},
				      "commands":{"type":"array","items":{"type":"string"}}}}},
				    "attack_commands":{"type":"array","items":{"type":"string"},"description":"Run as the creature when its melee attack lands; @target and ~ ~ ~ are the victim."},
				    "hurt_commands":{"type":"array","items":{"type":"string"},"description":"Run as the creature when it's hurt; @target is the attacker."},
				    "death_commands":{"type":"array","items":{"type":"string"},"description":"Run as the creature when it dies; @target is the killer."},
				    "interact_commands":{"type":"array","items":{"type":"string"},"description":"Run as the player when they right-click it, at the creature (~ ~ ~); @target is the creature."},
				    "spawning":{"type":"object","description":"Appear naturally near players.","properties":{
				      "biomes":{"type":"array","items":{"type":"string"},"description":"Biome ids or #tags, e.g. minecraft:plains, #minecraft:is_forest. Default: any overworld biome."},
				      "where":{"type":"string","enum":%s,"description":"Default from movement."},
				      "time":{"type":"string","enum":%s,"description":"Default night for hostile, any otherwise."},
				      "chance":{"type":"number","description":"Chance per player every 2 seconds, default 0.1."},
				      "group":{"type":"array","items":{"type":"integer"},"description":"[min, max], default [1, 3]."},
				      "max_nearby":{"type":"integer","description":"Default 6 within 64 blocks."}}},
				    "recipes":%s
				  }}
				},"required":["id","name","palette","parts"]}""".formatted(json(CustomEntities.MOVEMENTS), json(CreatureSpawning.WHERE),
				json(CreatureSpawning.TIMES), RECIPES_SCHEMA),
			this::defineEntity);

		register("spawn_entity",
			"Spawn custom creatures made with define_entity. Default: one, 3 blocks in front of the requesting player. "
				+ "(In commands, summon custom:<id> works too.)",
			"""
				{"type":"object","properties":{
				  "id":{"type":"string"},
				  "count":{"type":"integer","description":"1 to 20."},
				  "baby":{"type":"boolean","description":"Spawn babies (they grow up in about 20 minutes)."},
				  "tamed_by":{"type":"string","description":"Spawn already tamed by this player (tamable creatures)."},
				  "position":%s,
				  "dimension":%s
				},"required":["id"]}""".formatted(POS_SCHEMA, DIM_SCHEMA),
			this::spawnEntity);

		register("give_item",
			"Give a player copies of a custom item or block, or a custom creature's spawn egg. (In commands, give @s custom:<id> works too.)",
			"""
				{"type":"object","properties":{
				  "id":{"type":"string"},
				  "count":{"type":"integer","description":"Default 1."},
				  "player":{"type":"string","description":"Defaults to the player who sent the current message."}
				},"required":["id"]}""",
			this::giveItem);

		register("view_item",
			"See a picture of a custom item's, block's or creature's voxel model (creatures at rest), drawn like view_build (flat-shaded cubes, see-through colors shown "
				+ "solid). Use it to check a new design before giving it out. worn: true shows a wearable's worn parts on a gray "
				+ "player, facing south (+z), to check the fit.",
			"""
				{"type":"object","properties":{
				  "id":{"type":"string"},
				  "views":{"type":"array","items":{"type":"string","enum":["southeast","southwest","northwest","northeast","top"]}},
				  "worn":{"type":"boolean"}
				},"required":["id"]}""",
			this::viewItem);

		register("define_effect",
			"Design a visual effect drawn by every player's game: glowing beams (straight lasers, crackling lightning, waves, "
				+ "spirals), orbs with a halo and a spinning ring, expanding shockwave rings, sparks that burst out, drift "
				+ "along a beam or swirl inward, and particles: real particles whose motion you design, for smoke, dust, fire, "
				+ "debris, rain, leaves, magic and anything that should move like a fluid. A particles part emits a burst of count "
				+ "and/or rate per second while it lasts. Each particle appears at spawn, starts at velocity, then is pulled by "
				+ "gravity, slowed by drag and pushed every tick by flow, a velocity field that makes swirls, vortices, updrafts and "
				+ "orbits. spawn, velocity and flow are x/y/z math expressions (generate's syntax) in blocks and blocks per second "
				+ "over: x y z (the particle's offset from where the part plays; 0 in spawn), t (seconds into the effect), i "
				+ "(particle number) and n (how many the part makes), age (seconds) and life (0 to 1 over its lifetime), and r1 r2 "
				+ "r3 r4 (random 0 to 1, fixed per particle). Its colors and sizes run across each particle's life and tint the "
				+ "sprite (most are white or gray). Glows add up like light, and an orb can be solid black, so a black hole is a black "
				+ "orb with a purple halo, a bright tilted ring and sparks swirling in. An effect runs from a start point to an end "
				+ "point (for beams; parts sit at either end with at), for duration_seconds. Add a field to make it pull or push "
				+ "things and hurt them. Use effects in items' and creatures' shoot/ranged (effect, hit_effect), show one with "
				+ "play_effect, and in commands: claude effect <id> <from> [to], or summon custom:<id> for one at a point.",
			"""
				{"type":"object","properties":{
				  "id":{"type":"string"},"name":{"type":"string"},"description":{"type":"string"},
				  "duration_seconds":{"type":"number","description":"Default 1. A zap is 0.2 to 0.4, a black hole 5 to 15."},
				  "sound":{"type":"string","description":"Vanilla sound played at the start, e.g. entity.lightning_bolt.thunder, entity.warden.sonic_boom, block.beacon.activate."},
				  "sound_volume":{"type":"number"},
				  "parts":{"type":"array","items":{"type":"object","properties":{
				    "type":{"type":"string","enum":%s},
				    "at":{"type":"string","enum":%s,"description":"Orbs, rings, sparks and particles: at the start (default), the end, or along the beam."},
				    "start_seconds":{"type":"number"},"end_seconds":{"type":"number","description":"When this part shows, within the effect's time."},
				    "fade":{"type":"number","description":"Fraction of its time spent fading out at the end, default 0.3; 0 for none."},
				    "color":{"type":"string","description":"#RRGGBB or #AARRGGBB (alpha makes it fainter)."},
				    "style":{"type":"string","enum":%s,"description":"beam: straight (laser), lightning (jagged, re-crackling, with branches), wave or spiral."},
				    "width":{"type":"number","description":"beam: core thickness in blocks, default 0.12."},
				    "glow":{"type":"number","description":"How far the glow spreads, as a multiple of width (beam, default 3.5) or radius (orb, default 2.2); 0 for none."},
				    "core_color":{"type":"string","description":"beam: the hot center line, default white."},
				    "jitter":{"type":"number","description":"lightning: how wildly it zigzags."},"branches":{"type":"integer","description":"lightning: forks, default 3."},
				    "flicker":{"type":"boolean","description":"lightning: re-crackle every couple of ticks (default true)."},
				    "amplitude":{"type":"number"},"frequency":{"type":"number","description":"wave/spiral: waves per block."},"speed":{"type":"number","description":"wave/spiral: how fast it flows; sparks: how fast they fly, blocks per second."},
				    "grow_seconds":{"type":"number","description":"beam: time to shoot out to full length; orb: time to swell up."},
				    "pulse":{"type":"number","description":"0 to 1: throb in size."},
				    "radius":{"type":"number","description":"orb size (default 0.5), or how far out swirling sparks start."},
				    "solid":{"type":"boolean","description":"orb: draw its solid body (default true); false for pure light."},
				    "glow_color":{"type":"string"},
				    "ring":{"type":"number","description":"orb: outer radius of a flat spinning ring, as a multiple of the orb's radius (e.g. 3 for an accretion disk)."},
				    "ring_color":{"type":"string"},"tilt":{"type":"number","description":"Ring tilt in degrees."},"spin":{"type":"number","description":"Ring turns per second; particles: turns per second, each a random way."},
				    "from_radius":{"type":"number"},"to_radius":{"type":"number","description":"ring: a shockwave expanding between these radii."},
				    "axis":{"type":"string","enum":["up","facing","camera"],"description":"ring: flat on the ground (up), facing along the beam, or toward the viewer."},
				    "count":{"type":"integer","description":"sparks: how many (up to 400); particles: a burst when the part starts."},"size":{"type":"number","description":"sparks: size in blocks, default 0.08."},
				    "gravity":{"type":"number","description":"sparks and particles: blocks per second squared they fall (negative rises)."},
				    "inward":{"type":"boolean","description":"sparks: swirl in toward the center from radius, endlessly, instead of bursting out."},
				    "texture":{"type":"string","description":"particles: a vanilla particle sprite: generic (small smoke puffs), big_smoke, gust, small_gust, explosion, sonic_boom, spark, glitter, spell, effect, flash, glow, flame, soul_fire_flame, copper_fire_flame, soul, sculk_soul, leaf, cherry, pale_oak, splash, bubble, bubble_pop, drip_fall, lava, heart, note, angry, critical_hit, enchanted_hit, firefly, sweep, shriek, sga_a..sga_z (enchanting glyphs). Names with numbered frames (big_smoke_0..11) play through them over each particle's life; name one frame (generic_7) to use just it. Or block:<block id> for chips of a block's texture (debris: block:minecraft:dirt). Default generic."},
				    "frames":{"type":"string","enum":["life","random"],"description":"particles: play an animated sprite's frames over its life (default) or pick one at random (leaves)."},
				    "colors":{"type":"array","items":{"type":"string"},"description":"particles: #RRGGBB or #AARRGGBB across its life, e.g. [#FFE080, #FF6020, #00402020] to fade out. Default color, or white."},
				    "sizes":{"type":"array","items":{"type":"number"},"description":"particles: width in blocks across its life, e.g. [0.5, 2]. Default 0.2."},
				    "lifetime":{"type":"array","items":{"type":"number"},"description":"particles: [min, max] seconds. Default 1."},
				    "rate":{"type":"number","description":"particles: how many per second while the part lasts (up to %d), on top of a burst of count (up to %d)."},
				    "spawn":%s,"velocity":%s,"flow":%s,
				    "drag":{"type":"number","description":"particles: 0 to 1, the share of its own speed it loses each second."},
				    "emissive":{"type":"boolean","description":"particles: full brightness, even at night (fire, magic, sparks)."},
				    "collide":{"type":"boolean","description":"particles: stop at blocks (rain, debris)."},
				    "trail":{"type":"boolean","description":"particles: when the effect follows someone, leave particles where they appeared (footprints, a smoke trail) instead of carrying them along (an aura)."}
				  },"required":["type"]}},
				  "field":{"type":"object","description":"Makes the effect act on the world while it lasts.","properties":{
				    "radius":{"type":"number","description":"Default 6."},
				    "pull":{"type":"number","description":"Pull strength toward the center (1 is a gentle tug, 4 a black hole); negative pushes away."},
				    "damage":{"type":"number","description":"Damage per second to creatures in range."},
				    "commands":{"type":"array","items":{"type":"string"},"description":"Run every second as the effect, at its center."},
				    "spare_owner":{"type":"boolean","description":"Leave whoever fired it alone (default true)."}
				  }}
				},"required":["id","name","parts"]}""".formatted(json(Effects.PART_TYPES), json(Effects.PLACES), json(Effects.BEAM_STYLES),
					CustomParticles.MAX_RATE, CustomParticles.MAX_BURST, VEC_EXPR, VEC_EXPR, VEC_EXPR),
			this::defineEffect);

		register("define_morph",
			"Reshape how a player looks, using their own skin: morph them into a spider, give them a giant stomach, a "
				+ "huge head, long arms, a tail or horns. hide removes vanilla body parts (a list of head, body, right_arm, "
				+ "left_arm, right_leg, left_leg, or all), and parts add new ones. A skin part is a box cut from the player's "
				+ "skin, textured like that vanilla part (and its outer layer): head 8x8x8 voxels, body 8x12x4, arms and legs "
				+ "4x12x4; scale stretches it, center places it and rotation turns it [pitch, yaw, roll] in degrees about its "
				+ "pivot (default its top center). Voxel parts are like define_entity's (layers, origin, pivot, colors from "
				+ "palette) for what a skin doesn't have: eyes, fangs, horns, wings. Coordinates are define_entity's: voxels "
				+ "with x = 0, z = 0 the player's center, y = 0 their feet, +z the way they face and +x their left. Standing "
				+ "player parts are centered at head 0 28 0, body 0 18 0, right_arm -6 18 0, left_arm 6 18 0, right_leg "
				+ "-2 6 0, left_leg 2 6 0. attach makes a part move with a vanilla body part (a belly with the body, horns "
				+ "with the head; parts on an arm also show as the hand in first person); otherwise role animates it like a creature part (head looks around, legs and arms swing "
				+ "with phase 0 or 1, wings flap, tails sway). Every part must move with what it sits on: attach parts on the head to head, on an arm "
				+ "to that arm, on a leg to that leg, on the torso to body; the result flags parts that don't. A morph that hides an arm must "
				+ "give it a first-person hand: a part attached to that arm, placed where the arm was (view first_person for one that only "
				+ "shows as the hand, like a spider leg). Morphed players don't show armor. Morph players with run_commands: claude morph <id> [players] "
				+ "and claude unmorph [players]; it lasts through deaths and relogs. It changes looks only: for size, also "
				+ "set the minecraft:scale attribute. Updating a morph changes everyone wearing it.",
			"""
				{"type":"object","properties":{
				  "id":{"type":"string","description":"Lowercase letters, digits and underscores, e.g. spider_morph."},
				  "name":{"type":"string"},
				  "hide":{"description":"Vanilla parts to hide: all, or a list like [body]. Default none.","anyOf":[{"type":"string"},{"type":"array","items":{"type":"string","enum":["head","body","right_arm","left_arm","right_leg","left_leg"]}}]},
				  "palette":{"type":"object","additionalProperties":{"type":"string"},"description":"Colors for voxel parts, #RRGGBB or #AARRGGBB."},
				  "parts":{"type":"array","items":{"type":"object","properties":{
				    "skin":{"type":"string","enum":["head","body","right_arm","left_arm","right_leg","left_leg"],"description":"A skin part: which piece of the skin to use."},
				    "center":{"type":"array","items":{"type":"number"},"description":"skin parts: the box's center. Default where that vanilla part stands."},
				    "scale":{"type":"array","items":{"type":"number"},"description":"skin parts: stretch [x, y, z], e.g. [1.6, 1, 3] for a big round belly."},
				    "overlay":{"type":"boolean","description":"skin parts: include the skin's outer layer (default true)."},
				    "layers":{"type":"array","items":{"type":"array","items":{"type":"string"}},"description":"A voxel part, like define_entity's."},
				    "origin":{"type":"array","items":{"type":"integer"},"description":"voxel parts: where its layers start (the minimum corner)."},
				    "pivot":{"type":"array","items":{"type":"number"},"description":"The point it turns around: a hip, shoulder, neck or wing root."},
				    "rotation":{"type":"array","items":{"type":"number"},"description":"[pitch, yaw, roll] degrees at rest, e.g. a spider leg splayed out sideways."},
				    "role":{"type":"string","enum":["body","head","leg","arm","wing","tail","none"]},
				    "phase":{"type":"integer","description":"legs and arms: 0 or 1."},
				    "attach":{"type":"string","enum":["head","body","right_arm","left_arm","right_leg","left_leg"],"description":"Move with this vanilla body part (even a hidden one) instead of by role."},
				    "view":{"type":"string","enum":["both","first_person","third_person"],"description":"Where it shows: both (default), first_person (only as the hand you see) or third_person (only on your body)."}
				  }}}
				},"required":["id","name","parts"]}""",
			this::defineMorph);

		register("play_effect",
			"Play an effect so players can see it. By default it fires from the player's hand to what they're looking at (up "
				+ "to 24 blocks), which suits beams; give from and to (the same point for orbs and bursts) to place it. follow "
				+ "makes it move with a player or creature (an aura, a storm cloud over someone's head, a burning mob) until it "
				+ "ends or they die; in commands that's claude effect <id> on <targets> [height].",
			"""
				{"type":"object","properties":{
				  "id":{"type":"string"},
				  "from":%s,"to":%s,
				  "player":{"type":"string","description":"Whose hand and view to use when from/to are left out."},
				  "follow":{"type":"string","description":"A player name or entity selector (e.g. @e[type=custom:hippo,sort=nearest,limit=1]) to attach it to. It starts at their feet unless from is given, and keeps that offset."},
				  "height":{"type":"number","description":"With follow and no from: blocks above their feet, e.g. 2.2 for over a player's head."},
				  "dimension":%s
				},"required":["id"]}""".formatted(VEC_SCHEMA, VEC_SCHEMA, DIM_SCHEMA),
			this::playEffect);

		register("define_dimension",
			"Create a whole new dimension, or redesign one by reusing its id. It exists right away, no restart. sky picks the "
				+ "kind of world: daylight (sun, moon, day and night, weather), cavern (a closed world with a rock ceiling and "
				+ "dim light, like the Nether) or starry (an endless dark sky, like the End). biome sets its colors (sky, fog, "
				+ "water, grass, leaves), its weather and which vanilla mobs, trees and plants appear: any vanilla biome, or the "
				+ "mod's alien (purple sky, teal grass), crimson (red), frozen (white, snowy), toxic (green fog) and golden. "
				+ "terrain shapes the land; blocks can be vanilla or custom:<id>, so a dimension can be made of your own blocks. "
				+ "Redesigning changes chunks generated afterwards; reset: true wipes the old terrain so it all regenerates. "
				+ "Get there with visit_dimension, or in commands as custom:<id> (execute in custom:moon run ...). Portals: a "
				+ "custom block whose step_commands or use_commands run claude visit <id> (and claude visit overworld to come back).",
			"""
				{"type":"object","properties":{
				  "id":{"type":"string","description":"Lowercase letters, digits and underscores, e.g. moon."},
				  "name":{"type":"string"},
				  "description":{"type":"string"},
				  "sky":{"type":"string","enum":%s,"description":"Default daylight."},
				  "biome":{"type":"string","description":"e.g. alien, crimson, frozen, toxic, golden, plains, desert, cherry_grove, mushroom_fields, crimson_forest, the_end. Default plains."},
				  "terrain":{"type":"object","properties":{
				    "style":{"type":"string","enum":%s,"description":"hills (rolling land, default), mountains (tall ridges), flat, ocean (sea with scattered islands), islands (floating islands over the void), caves (solid rock full of caverns and tunnels; use with the cavern sky), void (nothing; build it yourself)."},
				    "height":{"type":"integer","description":"Surface level (or the islands' level). Defaults: 64; islands 100; ocean 50."},
				    "variation":{"type":"number","description":"How far the land rises and falls, in blocks."},
				    "scale":{"type":"number","description":"Size of hills, islands or caverns, in blocks (bigger is broader)."},
				    "surface":{"type":"string","description":"Top block, default minecraft:grass_block."},
				    "under":{"type":"string","description":"Blocks just under the top, default minecraft:dirt."},
				    "depth":{"type":"integer","description":"How deep the under layer goes, default 3."},
				    "stone":{"type":"string","description":"Everything deeper, default minecraft:stone."},
				    "fluid":{"type":"string","description":"Fills below sea_level, default minecraft:water (lava for a molten world)."},
				    "sea_level":{"type":"integer","description":"Default 62 for hills, mountains and ocean; none otherwise."},
				    "bedrock":{"type":"boolean"},
				    "carve_caves":{"type":"boolean","description":"Tunnels under hills and mountains (default true)."},
				    "decorate":{"type":"boolean","description":"Grow the biome's trees, plants and ores and spawn its animals (default true; void false)."},
				    "structures":{"type":"boolean","description":"Allow vanilla structures like villages where the biome has them (default false)."},
				    "height_expression":{"type":"string","description":"Surface height as math over x, z and n (smooth noise, about -1 to 1, at scale), e.g. 70 + 25*n + 10*sin(x/40). Overrides style's height."},
				    "solid_expression":{"type":"string","description":"Full 3D control: a block is solid where this is true, over x, y, z, n (3D noise at scale) and h (the surface height), e.g. y < h || (y > 140 && y < 150 && n > 0.3) for land with a floating layer."}
				  }},
				  "reset":{"type":"boolean","description":"Delete the terrain generated so far so everything regenerates from this design. Players inside are brought back to its new spawn."}
				},"required":["id","name"]}""".formatted(json(Dimensions.SKIES), json(CustomChunkGenerator.STYLES)),
			this::defineDimension);

		register("visit_dimension",
			"Take a player to a dimension (a custom one by id, or overworld, the_nether, the_end), landing on safe ground "
				+ "near x, z (default: the dimension's center, or the overworld's spawn).",
			"""
				{"type":"object","properties":{
				  "id":{"type":"string"},
				  "player":{"type":"string","description":"Defaults to the player who sent the current message."},
				  "x":{"type":"integer"},"z":{"type":"integer"}
				},"required":["id"]}""",
			this::visitDimension);

		register("list_definitions",
			"List this world's custom items, blocks and creatures (id, name and what they do, including recipes), and the "
				+ "designs saved in the library that any world can load.",
			"""
				{"type":"object","properties":{
				  "id":{"type":"string","description":"Show one definition in full (its whole JSON) instead of the list."}
				}}""",
			this::listDefinitions);

		register("delete_definition",
			"Delete a custom item, block or creature from this world. Copies that exist stay but look like a missing "
				+ "texture and do nothing until something with that id is defined again; its recipes go away.",
			"""
				{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}""",
			this::deleteDefinition);

		register("library",
			"The design library shared by every world on this computer. save copies a definition from this world into it; "
				+ "load copies one from it into this world (replacing any with the same id); list shows what's in it.",
			"""
				{"type":"object","properties":{
				  "action":{"type":"string","enum":["save","load","list"]},
				  "id":{"type":"string"}
				},"required":["action"]}""",
			this::library);

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
		String defaultPlayer = active().player;
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
			if (norm.startsWith("custom:")) {
				JsonObject def = CustomItems.DEFS.get(norm.substring("custom:".length()));
				if (!CustomBlocks.isBlock(def)) throw new ToolError("No custom block " + norm + " (make one with define_block)");
				BlockState st = CustomBlocks.state(def);
				cache.put(s, st);
				return st;
			}
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

	/** Queue a build and return right away; its result goes into the session's reports when it's done. */
	private String queueBuild(ServerLevel level, Map<BlockPos, BlockState> changes, JsonObject args) throws ToolError {
		int id = buildIds.incrementAndGet();
		double secs = args.has("duration_seconds") ? args.get("duration_seconds").getAsDouble() : 0;
		Session s = active();
		s.lastBuild = builds.enqueue(level, changes, undo.recorder(s.id), secs, r -> s.reports.add("Build #" + id + ": " + r),
			mask(level, args));
		return "Queued build #" + id + " (" + changes.size() + " blocks). Everything queued is done in about " + builds.secondsLeft()
			+ "s. It's animating now; keep working.";
	}

	/** Run on the server thread once queued builds have finished, so reads see what they placed. */
	private CompletableFuture<String> afterBuilds(Callable<Object> work) {
		Session caller = active; // the work runs on a later tick; keep it attributed to the same session
		return builds.idle().thenComposeAsync(v -> {
			Session prev = active;
			active = caller;
			try {
				Object r = work.call();
				return r instanceof CompletableFuture<?> f ? f.thenApply(o -> (String) o) : CompletableFuture.completedFuture((String) r);
			} catch (Exception e) {
				return CompletableFuture.failedFuture(e);
			} finally {
				active = prev;
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
				sb.append("Looking at: ").append(blockName(level.getBlockState(bh.getBlockPos()), false))
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
				String type = e instanceof CustomEntities.CustomEntity ce ? "custom:" + ce.definitionId() : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
				byType.computeIfAbsent(type, k -> new ArrayList<>()).add(e);
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
		String custom = s.get(CustomItems.DEFINITION);
		if (custom != null) return "custom item " + custom + " x" + s.getCount();
		return BuiltInRegistries.ITEM.getKey(s.getItem()) + " x" + s.getCount();
	}

	// ---------------------------------------------------------------- run_commands

	private String runCommands(JsonObject args) throws ToolError {
		if (!args.has("commands") || !args.get("commands").isJsonArray()) throw new ToolError("commands must be an array of strings");
		JsonArray arr = args.getAsJsonArray("commands");
		if (arr.size() > 2000) throw new ToolError("At most 2000 commands per call.");
		List<String> cmds = new ArrayList<>();
		for (JsonElement e : arr) cmds.add(e.getAsString());
		return execute(player(args, "player"), cmds);
	}

	/** Run commands in order as the player (or the server, if null) at the configured permission level. Server thread. */
	String execute(ServerPlayer p, List<String> cmds) {
		return execute(p != null ? p.createCommandSourceStack() : server.createCommandSourceStack(), cmds);
	}

	/** Run commands from a source at the configured permission level. custom:<id> works in them (see CommandRewriter). */
	String execute(CommandSourceStack source, List<String> cmds) {
		CommandSourceStack base = source
			.withPermission(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(Math.max(0, Math.min(4, cfg.commandPermissionLevel)))));
		CommandRewriter.Items customItems = CustomItems.commandItems(server);

		StringBuilder sb = new StringBuilder();
		int failures = 0;
		for (int i = 0; i < cmds.size(); i++) {
			String cmd = CommandRewriter.rewrite(cmds.get(i).trim(), CustomItems.DEFS::get, customItems);
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
		if (shape.equals("line") || shape.equals("curve")) {
			if (!args.has("points") || !args.get("points").isJsonArray() || args.getAsJsonArray("points").isEmpty()) {
				throw new ToolError(shape + " needs points: [{x,y,z}, ...]");
			}
			List<int[]> pts = new ArrayList<>();
			JsonObject wrap = new JsonObject();
			for (JsonElement e : args.getAsJsonArray("points")) {
				wrap.add("p", e);
				BlockPos p = pos(wrap, "p");
				pts.add(new int[]{p.getX(), p.getY(), p.getZ()});
			}
			double radius = Math.max(0, optDouble(args, "radius", 0));
			voxels = shape.equals("curve") ? Shapes.curve(pts, radius) : Shapes.line(pts, radius);
			if (hollow) voxels = Shapes.hollow(voxels);
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

	// ---------------------------------------------------------------- terrain tools

	private static final int MAX_COLUMNS = 10_000;

	/** Y of the top block in a column (solid or liquid, ignoring leaves). Loads the chunk if needed. */
	private static int topY(ServerLevel level, int x, int z) {
		// level.getHeight() reports the world floor for unloaded chunks, so load the chunk explicitly.
		return level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
	}

	private record Area(int x0, int z0, int x1, int z1) {
		int w() { return x1 - x0 + 1; }
		int d() { return z1 - z0 + 1; }
	}

	private static Area area(JsonObject args) throws ToolError {
		if (!args.has("from") || !args.has("to")) throw new ToolError("from and to are required");
		JsonObject f = args.getAsJsonObject("from"), t = args.getAsJsonObject("to");
		Area a = new Area(Math.min(f.get("x").getAsInt(), t.get("x").getAsInt()), Math.min(f.get("z").getAsInt(), t.get("z").getAsInt()),
			Math.max(f.get("x").getAsInt(), t.get("x").getAsInt()), Math.max(f.get("z").getAsInt(), t.get("z").getAsInt()));
		if ((long) a.w() * a.d() > MAX_COLUMNS) throw new ToolError("Area too large (" + (long) a.w() * a.d() + " columns, max " + MAX_COLUMNS + ").");
		return a;
	}

	private String overlay(JsonObject args) throws ToolError {
		ServerLevel level = level(args);
		Area a = area(args);
		Pattern block = new BlockParser(level).pattern(args.get("block").getAsString());
		String mode = args.has("mode") ? args.get("mode").getAsString() : "above";
		if (!mode.equals("above") && !mode.equals("replace")) throw new ToolError("mode must be above or replace");
		int depth = Math.max(1, Math.min(16, optInt(args, "depth", 1)));
		LinkedHashMap<BlockPos, BlockState> changes = new LinkedHashMap<>();
		int skipped = 0;
		for (int z = a.z0(); z <= a.z1(); z++) for (int x = a.x0(); x <= a.x1(); x++) {
			int y = topY(level, x, z);
			BlockState top = level.getBlockState(new BlockPos(x, y, z));
			if (top.liquid() || top.is(BlockTags.LOGS) || top.isAir()) {
				skipped++;
				continue;
			}
			if (mode.equals("above")) changes.put(new BlockPos(x, y + 1, z), block.pick());
			else for (int k = 0; k < depth; k++) changes.put(new BlockPos(x, y - k, z), block.pick());
		}
		String out = queueBuild(level, changes, args);
		return skipped > 0 ? out + " Skipped " + skipped + " column(s) topped by water, lava or a tree trunk." : out;
	}

	/** Ground height for smoothing: the top block, stepping down through tree trunks. */
	private static int groundY(ServerLevel level, int x, int z) {
		int y = topY(level, x, z);
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(x, y, z);
		while (y > level.getMinY() && level.getBlockState(mp.setY(y)).is(BlockTags.LOGS)) y--;
		return y;
	}

	private String smoothTerrain(JsonObject args) throws ToolError {
		ServerLevel level = level(args);
		Area a = area(args);
		int iterations = Math.max(1, Math.min(10, optInt(args, "iterations", 2)));
		// One extra column all around, held fixed, so the edges blend into the land outside.
		double[][] h = new double[a.d() + 2][a.w() + 2];
		for (int j = 0; j < a.d() + 2; j++) for (int i = 0; i < a.w() + 2; i++) h[j][i] = groundY(level, a.x0() + i - 1, a.z0() + j - 1);
		double[][] s = Shapes.smooth(h, iterations);

		BlockState air = Blocks.AIR.defaultBlockState();
		LinkedHashMap<BlockPos, BlockState> changes = new LinkedHashMap<>();
		for (int j = 1; j <= a.d(); j++) for (int i = 1; i <= a.w(); i++) {
			int x = a.x0() + i - 1, z = a.z0() + j - 1;
			int old = (int) h[j][i], now = (int) Math.round(s[j][i]);
			if (now == old) continue;
			BlockState top = level.getBlockState(new BlockPos(x, old, z));
			if (top.liquid() || top.isAir()) continue;
			BlockState below = level.getBlockState(new BlockPos(x, old - 1, z));
			BlockState filler = below.isSolid() ? below : top;
			if (now > old) {
				for (int y = old; y < now; y++) changes.put(new BlockPos(x, y, z), filler);
			} else {
				for (int y = now + 1; y <= old; y++) changes.put(new BlockPos(x, y, z), air);
				BlockState plant = level.getBlockState(new BlockPos(x, old + 1, z));
				if (!plant.isAir() && plant.canBeReplaced()) changes.put(new BlockPos(x, old + 1, z), air); // grass tufts would float
			}
			changes.put(new BlockPos(x, now, z), top);
		}
		checkSize(changes.size());
		if (changes.isEmpty()) return "The ground there is already smooth; nothing to change.";
		return queueBuild(level, changes, args);
	}

	private static boolean natural(BlockState st) {
		return st.is(BlockTags.DIRT) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.SAND) || st.is(Blocks.GRAVEL);
	}

	private String naturalize(JsonObject args) throws ToolError {
		ServerLevel level = level(args);
		BlockPos a = pos(args, "from"), b = pos(args, "to");
		BlockParser parser = new BlockParser(level);
		Pattern top = parser.pattern(args.has("top") ? args.get("top").getAsString() : "minecraft:grass_block");
		Pattern under = parser.pattern(args.has("under") ? args.get("under").getAsString() : "minecraft:dirt");
		Pattern deep = parser.pattern(args.has("deep") ? args.get("deep").getAsString() : "minecraft:stone");
		int layers = Math.max(0, Math.min(16, optInt(args, "depth", 3)));
		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int y0 = Math.max(level.getMinY(), Math.min(a.getY(), b.getY())), y1 = Math.min(level.getMaxY(), Math.max(a.getY(), b.getY()));
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
		checkSize((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1));

		LinkedHashMap<BlockPos, BlockState> changes = new LinkedHashMap<>();
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
			// Start above the box so a box whose top is underground still knows how deep it is.
			int depth = 0;
			boolean wet = false;
			for (int y = Math.min(level.getMaxY(), y1 + layers + 1); y >= y0; y--) {
				BlockState st = level.getBlockState(mp.set(x, y, z));
				if (st.liquid()) { wet = true; continue; }
				if (!st.isSolid()) { // air, plants, snow layers: the next solid block is exposed
					depth = 0;
					if (st.isAir()) wet = false;
					continue;
				}
				depth++;
				if (y > y1 || wet || !natural(st)) continue;
				Pattern p = depth == 1 ? top : depth <= 1 + layers ? under : deep;
				changes.put(new BlockPos(x, y, z), p.pick());
			}
		}
		return queueBuild(level, changes, args);
	}

	/** Must be called on the server thread. Waits for queued builds, then completes once the (animated) revert has finished. */
	public CompletableFuture<String> undo(int steps) {
		return afterBuilds(() -> undoNow(steps));
	}

	private CompletableFuture<String> undoNow(int steps) {
		List<UndoHistory.Entry> entries = undo.pop(Math.max(1, steps), active == null ? -1 : active.id);
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

	// ---------------------------------------------------------------- custom items

	private static String json(List<String> values) {
		return new com.google.gson.Gson().toJson(values);
	}

	private static final String SHOOT_SCHEMA = """
		{"type":"object","description":"Right-click shoots: a bow, wand, blaster or throwable.","properties":{
		  "projectile":{"type":"string","description":"beam (an instant ray, like a laser or lightning gun; give it an effect to see it), arrow, fireball, small_fireball, wind_charge, self (throws the item itself, using one up, like a snowball or spear), or an item id like minecraft:snowball or custom:<id> that flies as that item."},
		  "effect":{"type":"string","description":"An effect made with define_effect: for beam, drawn from the hand to what it hits; for item projectiles, carried as they fly (an orb around them, sparks, a beam part trailing behind)."},
		  "hit_effect":{"type":"string","description":"An effect played where it hits: a burst, a shockwave, a black hole."},
		  "range":{"type":"number","description":"beam: how far it reaches, default 48."},
		  "damage":{"type":"number","description":"Damage on hit (an arrow from a full bow does about 9)."},
		  "speed":{"type":"number","description":"Default 2.5 for arrows, 1.5 otherwise."},
		  "gravity":{"type":"boolean","description":"false: item projectiles fly straight, like magic bolts."},
		  "ammo":{"type":"string","description":"Item used up per shot, e.g. minecraft:arrow or custom:<id>. Creative players need none."},
		  "hit_commands":{"type":"array","items":{"type":"string"},"description":"Item projectiles and beams: run as the shooter where it lands; @target is what it hit."}
		}}""";

	private static final String RECIPES_SCHEMA = """
		{"type":"array","description":"Ways to make this (the creature's spawn egg, for creatures). Ingredients are item ids, #tags or custom:<id>.","items":{"type":"object","properties":{
		  "type":{"type":"string","enum":["shaped","shapeless","smelting","blasting","smoking","campfire_cooking","stonecutting"]},
		  "pattern":{"type":"array","items":{"type":"string"},"description":"shaped: up to 3 rows of up to 3 characters; space is an empty slot."},
		  "key":{"type":"object","additionalProperties":{"type":"string"},"description":"shaped: character -> ingredient."},
		  "ingredients":{"type":"array","items":{"type":"string"},"description":"shapeless: 1 to 9 ingredients."},
		  "ingredient":{"type":"string","description":"smelting, blasting, smoking, campfire_cooking, stonecutting: the input."},
		  "count":{"type":"integer","description":"How many it makes (crafting and stonecutting)."},
		  "xp":{"type":"number","description":"Cooking: experience, e.g. 0.7 for iron."},
		  "seconds":{"type":"number","description":"Cooking time; default 10 (smelting)."}
		},"required":["type"]}}""";

	private static String checkId(JsonObject args) throws ToolError {
		String id = args.has("id") ? args.get("id").getAsString() : "";
		if (!id.matches("[a-z0-9_]{1,40}")) throw new ToolError("id must be 1 to 40 lowercase letters, digits or underscores");
		return id;
	}

	private static void oneOf(JsonObject o, String key, List<String> allowed, String what) throws ToolError {
		if (o.has(key) && !allowed.contains(o.get(key).getAsString())) throw new ToolError(what + " must be one of " + String.join(", ", allowed));
	}

	/** Particles, ambient sound, burning and portals on a block. */
	private void checkBlockLife(JsonObject props) throws ToolError {
		if (props.has("particles") && !props.get("particles").isJsonArray()) throw new ToolError("particles must be a list");
		for (JsonObject spec : CustomBlocks.particleSpecs(props)) {
			try {
				CustomBlocks.particle(spec);
			} catch (IllegalArgumentException | IllegalStateException | ClassCastException e) {
				throw new ToolError("particles: " + e.getMessage());
			}
			oneOf(spec, "where", CustomBlocks.PARTICLE_PLACES, "particles.where");
		}
		JsonObject amb = CustomBlocks.ambientSound(props);
		if (amb != null && (!amb.has("sound") || Effects.sound(amb.get("sound").getAsString()) == null)) {
			throw new ToolError("ambient_sound: unknown sound " + (amb.has("sound") ? amb.get("sound").getAsString() : "(missing)"));
		}
		if (props.has("burns") && !props.get("burns").isJsonPrimitive()) throw new ToolError("burns is true or a damage number");
		if (props.has("portal")) {
			String target = props.get("portal").getAsString();
			if (Dimensions.level(server, target) == null) {
				throw new ToolError("portal: no dimension " + target + " (use a custom dimension's id, overworld, the_nether or the_end)");
			}
		}
	}

	private static JsonObject obj(JsonObject o, String key) throws ToolError {
		if (!o.has(key)) return null;
		if (!o.get(key).isJsonObject()) throw new ToolError(key + " must be an object");
		return o.getAsJsonObject(key);
	}

	/** Check that every drop entry names a real item. */
	private static void checkDrops(JsonObject props) throws ToolError {
		for (String d : CustomItems.strings(props, "drops")) {
			String item = d.trim().split("\\s+")[0];
			if (CustomEntities.dropStack(item, 1) == null) throw new ToolError("Unknown drop item " + item);
		}
	}

	/** Check item lists like food or ammo name real items. */
	private void checkItems(List<String> items, String what) throws ToolError {
		for (String s : items) {
			try {
				CustomRecipes.ingredient(server, s);
			} catch (IllegalArgumentException e) {
				throw new ToolError(what + ": " + e.getMessage());
			}
		}
	}

	/** Turn every recipe into vanilla recipe JSON now, so mistakes come back to Claude instead of failing on reload. */
	private void checkRecipes(JsonObject def) throws ToolError {
		JsonObject p = CustomItems.props(def);
		if (!p.has("recipes")) return;
		if (!p.get("recipes").isJsonArray()) throw new ToolError("recipes must be a list");
		int i = 0;
		for (JsonElement e : p.getAsJsonArray("recipes")) {
			i++;
			try {
				CustomRecipes.toJson(server, def, e.getAsJsonObject());
			} catch (RuntimeException ex) {
				throw new ToolError("Recipe " + i + ": " + ex.getMessage());
			}
		}
	}

	private static String recipeNote(JsonObject def) {
		JsonObject p = CustomItems.props(def);
		return p.has("recipes") ? " Its recipes are loading (the server reloads its data for a moment)." : "";
	}

	private String defineItem(JsonObject args) throws ToolError {
		String id = checkId(args);
		String style = args.has("style") ? args.get("style").getAsString() : "item";
		if (!List.of("item", "handheld", "block").contains(style)) throw new ToolError("style must be item, handheld or block");
		JsonObject model;
		if (args.has("pixels")) {
			model = VoxelModel.fromPixels(args.getAsJsonObject("palette"), args.getAsJsonArray("pixels"), optInt(args, "thickness", 1));
		} else if (args.has("layers")) {
			model = new JsonObject();
			model.add("palette", args.get("palette"));
			model.add("layers", args.get("layers"));
		} else {
			throw new ToolError("Give the model as pixels or layers");
		}
		VoxelModel parsed;
		try {
			parsed = VoxelModel.parse(model);
		} catch (IllegalArgumentException | IllegalStateException | ClassCastException e) {
			throw new ToolError("Bad model: " + e.getMessage());
		}
		JsonObject props = args.has("properties") ? args.getAsJsonObject("properties") : new JsonObject();
		oneOf(props, "rarity", List.of("common", "uncommon", "rare", "epic"), "rarity");
		JsonObject tool = obj(props, "tool");
		if (tool != null) {
			oneOf(tool, "type", CustomItems.TOOL_TYPES, "tool.type");
			oneOf(tool, "tier", CustomItems.TIERS, "tool.tier");
		}
		JsonObject armor = obj(props, "armor");
		if (armor != null) {
			oneOf(armor, "slot", CustomItems.ARMOR_SLOTS, "armor.slot");
			oneOf(armor, "look", CustomItems.ARMOR_LOOKS, "armor.look");
			if (armor.has("look") && armor.get("look").getAsString().equals("model") && armor.has("slot") && !armor.get("slot").getAsString().equals("head")) {
				throw new ToolError("armor.look model only works on the head; give worn parts for a custom look, or use a vanilla look");
			}
			if (armor.has("look") && armor.get("look").getAsString().equals("worn") && !armor.has("worn")) throw new ToolError("armor.look worn needs armor.worn parts");
			JsonObject probe = new JsonObject();
			probe.addProperty("id", id);
			probe.add("model", model);
			probe.add("properties", props);
			String badWorn = Morphs.validateWorn(probe);
			if (badWorn != null) throw new ToolError(badWorn);
		}
		JsonObject shoot = obj(props, "shoot");
		if (shoot != null) {
			String bad = CustomProjectiles.validate(shoot);
			if (bad != null) throw new ToolError(bad);
			if (shoot.has("ammo")) checkItems(List.of(shoot.get("ammo").getAsString()), "shoot.ammo");
			checkEffects(shoot);
		}
		if (props.has("repair_with")) checkItems(CustomItems.strings(props, "repair_with"), "repair_with");

		JsonObject def = new JsonObject();
		def.addProperty("id", id);
		def.addProperty("name", args.get("name").getAsString());
		if (args.has("description")) def.addProperty("description", args.get("description").getAsString());
		def.addProperty("style", style);
		def.add("model", model);
		def.add("properties", props);
		JsonObject old = CustomItems.DEFS.get(id);
		if (old != null && (CustomBlocks.isBlock(old) || CustomEntities.isEntity(old))) throw new ToolError(id + " is already a custom block or creature; pick another id");
		checkRecipes(def);
		CustomItems.define(server, def);
		return (old != null ? "Updated" : "Defined") + " custom item " + id + " (" + parsed.sx + "x" + parsed.sy + "x" + parsed.sz
			+ " voxels). Hand it out with give_item, or check it with view_item." + recipeNote(def)
			+ (Morphs.worn(def, false) != null ? " Check the fit with view_item worn: true." + attachAdvice(Morphs.attachWarnings(Morphs.worn(def, false))) : "");
	}

	private String defineBlock(JsonObject args) throws ToolError {
		String id = checkId(args);
		JsonObject old = CustomItems.DEFS.get(id);
		if (old != null && !CustomBlocks.isBlock(old)) throw new ToolError(id + " is already a custom item or creature; pick another id");
		JsonObject model = new JsonObject();
		model.add("palette", args.get("palette"));
		boolean framed = args.has("frames") && args.get("frames").isJsonArray() && !args.getAsJsonArray("frames").isEmpty();
		if (framed) model.add("frames", args.get("frames"));
		else if (args.has("faces")) model.add("faces", args.get("faces"));
		else if (args.has("layers")) model.add("layers", args.get("layers"));
		else throw new ToolError("Give the block as faces (a painted cube), layers (a voxel shape) or frames (an animation)");
		oneOf(args, "shape", List.of("cube", "cross"), "shape");
		if (args.has("shape") && args.get("shape").getAsString().equals("cross")) model.addProperty("shape", "cross");
		if (args.has("frame_time")) model.addProperty("frame_time", Math.max(1, Math.min(200, args.get("frame_time").getAsInt())));
		if (args.has("desync") && args.get("desync").getAsBoolean()) model.addProperty("desync", true);
		int frames = VoxelModel.frameCount(model);
		if (frames > VoxelModel.MAX_FRAMES) {
			throw new ToolError("That animation repeats only every " + frames + " frames (at most " + VoxelModel.MAX_FRAMES
				+ "); give color lists and frames lengths that divide evenly, like 2, 4 and 8");
		}
		for (int f = 0; f < frames; f++) {
			VoxelModel parsed;
			try {
				parsed = VoxelModel.parse(model, f);
			} catch (IllegalArgumentException | IllegalStateException | ClassCastException | UnsupportedOperationException e) {
				throw new ToolError("Bad model" + (frames > 1 ? " (frame " + (f + 1) + ")" : "") + ": " + e.getMessage());
			}
			if (parsed.sx > 16 || parsed.sy > 16 || parsed.sz > 16) throw new ToolError("Blocks are at most 16 voxels per side");
		}
		JsonObject props = args.has("properties") ? args.getAsJsonObject("properties") : new JsonObject();
		checkBlockLife(props);
		oneOf(props, "sound", CustomBlocks.SOUNDS, "sound");
		oneOf(props, "collision", List.of("model", "full", "none"), "collision");
		oneOf(props, "tool", CustomBlocks.TOOLS, "tool");
		oneOf(props, "tier", CustomItems.TIERS, "tier");
		checkDrops(props);
		JsonObject gen = obj(props, "generate");
		if (gen != null) {
			for (String r : CustomItems.strings(gen, "replace")) {
				boolean ok = r.startsWith("#") || BuiltInRegistries.BLOCK.containsKey(net.minecraft.resources.Identifier.tryParse(r.contains(":") ? r : "minecraft:" + r));
				if (!ok) throw new ToolError("generate.replace: unknown block " + r);
			}
		}
		int slot = CustomBlocks.allocateSlot(id);
		if (slot < 0) throw new ToolError("This world already has " + CustomBlocks.SLOTS + " custom blocks; redefine or delete one instead");

		JsonObject def = new JsonObject();
		def.addProperty("id", id);
		def.addProperty("kind", "block");
		def.addProperty("slot", slot);
		def.addProperty("name", args.get("name").getAsString());
		if (args.has("description")) def.addProperty("description", args.get("description").getAsString());
		def.addProperty("style", "block");
		def.add("model", model);
		def.add("properties", props);
		checkRecipes(def);
		CustomItems.define(server, def);
		return (old != null ? "Updated" : "Defined") + " custom block " + id + ". Place it as custom:" + id
			+ " with any placement tool, or give it with give_item."
			+ (gen != null ? " Its ore veins appear in chunks around players over the next few seconds." : "") + recipeNote(def);
	}

	private String defineEntity(JsonObject args) throws ToolError {
		String id = checkId(args);
		JsonObject old = CustomItems.DEFS.get(id);
		if (old != null && !CustomEntities.isEntity(old)) throw new ToolError(id + " is already a custom item or block; pick another id");
		JsonObject model = new JsonObject();
		model.add("palette", args.get("palette"));
		model.add("parts", args.get("parts"));
		float scale = args.has("scale") ? args.get("scale").getAsFloat() : 16;
		EntityModel parsed;
		try {
			parsed = EntityModel.parse(model, scale);
		} catch (IllegalArgumentException | IllegalStateException | ClassCastException e) {
			throw new ToolError("Bad model: " + e.getMessage());
		}
		JsonObject props = args.has("properties") ? args.getAsJsonObject("properties") : new JsonObject();
		oneOf(props, "behavior", List.of("passive", "neutral", "hostile"), "behavior");
		oneOf(props, "movement", CustomEntities.MOVEMENTS, "movement");
		checkDrops(props);
		checkItems(CustomItems.strings(props, "food"), "food");
		JsonObject tame = obj(props, "tame");
		if (tame != null) {
			if (CustomItems.strings(tame, "items").isEmpty()) throw new ToolError("tame needs items to tame it with");
			checkItems(CustomItems.strings(tame, "items"), "tame.items");
		}
		JsonObject ranged = obj(props, "ranged");
		if (ranged != null) {
			String bad = CustomProjectiles.validate(ranged);
			if (bad != null) throw new ToolError(bad);
			checkEffects(ranged);
		}
		JsonObject vehicle = obj(props, "vehicle");
		if (vehicle != null) {
			oneOf(vehicle, "controls", List.of("thrust", "drive"), "vehicle.controls");
			if (vehicle.has("exhaust")) {
				String fx = vehicle.get("exhaust").getAsString().replaceFirst("^custom:", "");
				vehicle.addProperty("exhaust", fx);
				if (!Effects.isEffect(CustomItems.DEFS.get(fx))) throw new ToolError("vehicle.exhaust: no effect " + fx + " (make it with define_effect first)");
			}
		}
		JsonObject spawning = obj(props, "spawning");
		if (spawning != null) {
			oneOf(spawning, "where", CreatureSpawning.WHERE, "spawning.where");
			oneOf(spawning, "time", CreatureSpawning.TIMES, "spawning.time");
		}
		JsonObject def = new JsonObject();
		def.addProperty("id", id);
		def.addProperty("kind", "entity");
		def.addProperty("name", args.get("name").getAsString());
		def.addProperty("scale", scale);
		def.add("model", model);
		def.add("properties", props);
		checkRecipes(def);
		CustomItems.define(server, def);
		return String.format(Locale.ROOT, "%s custom creature %s (%d parts, %.2f x %.2f blocks). Spawn it with spawn_entity, give its spawn egg with give_item, or check it with view_item.%s",
			old != null ? "Updated" : "Defined", id, parsed.parts.size(), parsed.width(), parsed.height(), recipeNote(def));
	}

	private String spawnEntity(JsonObject args) throws ToolError {
		String id = args.has("id") ? args.get("id").getAsString() : "";
		JsonObject def = CustomItems.DEFS.get(id);
		if (!CustomEntities.isEntity(def)) throw new ToolError("No custom creature " + id + " (make one with define_entity)");
		int count = Math.max(1, Math.min(20, optInt(args, "count", 1)));
		ServerLevel level = level(args);
		net.minecraft.world.phys.Vec3 at;
		if (args.has("position")) {
			BlockPos p = pos(args, "position");
			at = new net.minecraft.world.phys.Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
		} else {
			ServerPlayer p = player(new JsonObject(), "player");
			if (p == null) throw new ToolError("Give a position (no player to spawn it near)");
			net.minecraft.world.phys.Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize().scale(3);
			at = p.position().add(look);
		}
		ServerPlayer owner = args.has("tamed_by") ? player(args, "tamed_by") : null;
		boolean baby = args.has("baby") && args.get("baby").getAsBoolean();
		var spawned = CustomEntities.spawn(level, def, at, count, baby, false);
		if (owner != null) for (var e : spawned) e.tame(owner);
		return String.format(Locale.ROOT, "Spawned %d %s%s at %.1f %.1f %.1f%s.", spawned.size(), baby ? "baby " : "", def.get("name").getAsString(),
			at.x, at.y, at.z, owner != null ? ", tamed by " + owner.getScoreboardName() : "");
	}

	/** How Claude names a block state: custom:<id> for custom blocks, the usual id[properties] otherwise. */
	static String blockName(BlockState st, boolean idsOnly) {
		if (st.is(CustomBlocks.BLOCK)) {
			String id = CustomBlocks.idForSlot(st.getValue(CustomBlocks.VARIANT));
			return id == null ? "custom:unknown" : "custom:" + id;
		}
		return idsOnly ? BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString() : BlockStateParser.serialize(st);
	}

	private static String id(JsonObject def) {
		return def.get("id").getAsString();
	}

	private JsonObject itemDef(JsonObject args) throws ToolError {
		String id = args.has("id") ? args.get("id").getAsString() : "";
		JsonObject def = CustomItems.DEFS.get(id);
		if (def == null) {
			throw new ToolError("No custom item " + id + ". Defined: " + (CustomItems.DEFS.isEmpty() ? "none" : String.join(", ", new java.util.TreeSet<>(CustomItems.DEFS.keySet()))));
		}
		return def;
	}

	private String giveItem(JsonObject args) throws ToolError {
		JsonObject def = itemDef(args);
		if (Dimensions.isDimension(def)) throw new ToolError(id(def) + " is a dimension; use visit_dimension");
		if (Effects.isEffect(def)) throw new ToolError(id(def) + " is an effect; use play_effect");
		if (Morphs.isMorph(def)) throw new ToolError(id(def) + " is a morph; use run_commands: claude morph " + id(def));
		ServerPlayer p = player(args, "player");
		if (p == null) throw new ToolError("No player to give it to");
		ItemStack stack = CustomItems.stack(def, optInt(args, "count", 1));
		int n = stack.getCount();
		String what = CustomEntities.isEntity(def) ? def.get("name").getAsString() + " Spawn Egg" : def.get("name").getAsString();
		if (!p.getInventory().add(stack)) p.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY); // full inventory: drop it at their feet
		return "Gave " + n + " " + what + " to " + p.getScoreboardName() + ".";
	}

	// ---------------------------------------------------------------- effects

	private static final String VEC_SCHEMA = """
		{"type":"object","properties":{"x":{"type":"number"},"y":{"type":"number"},"z":{"type":"number"}},"required":["x","y","z"]}""";

	private static net.minecraft.world.phys.Vec3 vec(JsonObject args, String key) {
		JsonObject o = args.getAsJsonObject(key);
		return new net.minecraft.world.phys.Vec3(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
	}

	/** Check that every effect a shot or attack names exists. */
	private static void checkEffects(JsonObject spec) throws ToolError {
		for (String k : List.of("effect", "hit_effect")) {
			if (!spec.has(k)) continue;
			String id = spec.get(k).getAsString().replaceFirst("^custom:", "");
			spec.addProperty(k, id);
			if (!Effects.isEffect(CustomItems.DEFS.get(id))) throw new ToolError(k + ": no effect " + id + " (make it with define_effect first)");
		}
	}

	private String defineEffect(JsonObject args) throws ToolError {
		String id = checkId(args);
		JsonObject old = CustomItems.DEFS.get(id);
		if (old != null && !Effects.isEffect(old)) throw new ToolError(id + " is already a custom " + kind(old) + "; pick another id");
		JsonObject def = args.deepCopy();
		def.addProperty("kind", "effect");
		String bad = Effects.validate(def);
		if (bad != null) throw new ToolError(bad);
		CustomItems.define(server, def);
		return (old != null ? "Updated" : "Defined") + " effect " + id + " (" + Effects.durationTicks(def) / 20.0 + " s). Show it with play_effect, "
			+ "use it in shoot/ranged as effect or hit_effect, or in commands: claude effect " + id + " <from> [to], summon custom:" + id + "."
			+ Effects.particleSummary(def);
	}

	private static final String VEC_EXPR = """
		{"type":"object","properties":{"x":{"type":"string"},"y":{"type":"string"},"z":{"type":"string"}},"description":"particles: x, y, z expressions; missing ones are 0."}""";

	private String defineMorph(JsonObject args) throws ToolError {
		String id = checkId(args);
		JsonObject old = CustomItems.DEFS.get(id);
		if (old != null && !Morphs.isMorph(old)) throw new ToolError(id + " is already a custom " + kind(old) + "; pick another id");
		JsonObject def = args.deepCopy();
		def.addProperty("kind", "morph");
		String bad = Morphs.validate(def);
		if (bad != null) throw new ToolError(bad);
		CustomItems.define(server, def);
		return (old != null ? "Updated" : "Defined") + " morph " + id + ". Apply it with run_commands: claude morph " + id
			+ " [players] (claude unmorph to undo). Pictures can't show morphs, so ask the player how it looks." + attachAdvice(Morphs.attachWarnings(def));
	}

	/** Movement problems worth fixing, as a sentence for a tool result; empty if there are none. */
	private static String attachAdvice(List<String> warnings) {
		return warnings.isEmpty() ? "" : " Check how it moves: " + String.join("; ", warnings) + ".";
	}

	private String playEffect(JsonObject args) throws ToolError {
		String id = args.has("id") ? args.get("id").getAsString().replaceFirst("^custom:", "") : "";
		JsonObject def = CustomItems.DEFS.get(id);
		if (!Effects.isEffect(def)) throw new ToolError("No effect " + id + " (make one with define_effect)");
		ServerLevel level = level(args);
		ServerPlayer p = player(args, "player");
		net.minecraft.world.phys.Vec3 from, to;
		if (args.has("from")) {
			from = vec(args, "from");
			to = args.has("to") ? vec(args, "to") : from;
		} else {
			if (p == null) throw new ToolError("Give from (no player to fire it from)");
			var look = p.getLookAngle();
			from = CustomProjectiles.muzzle(p, look);
			var eye = p.getEyePosition();
			var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, eye.add(look.scale(24)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, p));
			to = args.has("to") ? vec(args, "to") : hit.getLocation();
		}
		if (args.has("follow")) {
			List<? extends Entity> targets;
			try {
				var src = (p != null ? p.createCommandSourceStack() : server.createCommandSourceStack()).withPermission(LevelBasedPermissionSet.GAMEMASTER);
				targets = net.minecraft.commands.arguments.EntityArgument.entities().parse(new StringReader(CommandRewriter.rewrite(args.get("follow").getAsString(), CustomItems.DEFS::get, CustomItems.commandItems(server)))).findEntities(src);
			} catch (CommandSyntaxException e) {
				throw new ToolError("follow: " + e.getMessage());
			}
			if (targets.isEmpty()) throw new ToolError("follow: nobody matches " + args.get("follow").getAsString());
			for (Entity t : targets) {
				var start = args.has("from") ? from : t.position().add(0, args.has("height") ? args.get("height").getAsDouble() : 0, 0);
				var end = args.has("to") ? to : start;
				Effects.play((ServerLevel) t.level(), def, start, end, p, t);
			}
			return "Playing " + id + " on " + targets.size() + " " + (targets.size() == 1 ? targets.getFirst().getName().getString() : "entities") + ", following them.";
		}
		Effects.play(level, def, from, to, p);
		return String.format(Locale.ROOT, "Playing %s from %.1f %.1f %.1f to %.1f %.1f %.1f.", id, from.x, from.y, from.z, to.x, to.y, to.z);
	}

	// ---------------------------------------------------------------- dimensions

	private String defineDimension(JsonObject args) throws ToolError {
		String id = checkId(args);
		JsonObject old = CustomItems.DEFS.get(id);
		if (old != null && !Dimensions.isDimension(old)) throw new ToolError(id + " is already a custom " + kind(old) + "; pick another id");
		String sky = args.has("sky") ? args.get("sky").getAsString() : "daylight";
		String biome = args.has("biome") ? args.get("biome").getAsString() : "plains";
		JsonObject terrain = args.has("terrain") ? args.getAsJsonObject("terrain") : new JsonObject();
		try {
			var type = Dimensions.sky(server, sky);
			Dimensions.biome(server, biome);
			CustomChunkGenerator.parse(terrain, type.value().minY());
		} catch (IllegalArgumentException e) {
			throw new ToolError(e.getMessage());
		}
		JsonObject def = new JsonObject();
		def.addProperty("id", id);
		def.addProperty("kind", "dimension");
		def.addProperty("name", args.get("name").getAsString());
		if (args.has("description")) def.addProperty("description", args.get("description").getAsString());
		def.addProperty("sky", sky);
		def.addProperty("biome", biome);
		def.add("terrain", terrain);
		CustomItems.define(server, def);
		boolean reset = args.has("reset") && args.get("reset").getAsBoolean();
		ServerLevel level;
		try {
			level = reset ? Dimensions.reset(server, def) : Dimensions.ensure(server, def);
		} catch (java.io.IOException | RuntimeException e) {
			ClaudeCraft.LOGGER.error("Creating dimension {} failed", id, e);
			throw new ToolError("Couldn't create the dimension: " + e.getMessage());
		}
		var spot = Dimensions.safeSpot(level, 0, 0);
		return String.format(Locale.ROOT, "%s dimension %s (%s sky, %s biome)%s. Its center has ground at %.0f %.0f %.0f. Take players there with "
				+ "visit_dimension; in commands it's custom:%s (claudecraft:%s), and building tools take dimension %s.",
			old != null ? "Updated" : "Created", id, sky, biome, reset ? ", with its terrain regenerated" : old != null ? " (only chunks generated from now on use the new terrain; reset: true regenerates everything)" : "",
			spot.x, spot.y, spot.z, id, id, id);
	}

	private String visitDimension(JsonObject args) throws ToolError {
		String name = args.has("id") ? args.get("id").getAsString() : "";
		ServerLevel level = Dimensions.level(server, name);
		if (level == null) throw new ToolError("No dimension " + name + " (custom ids, or overworld, the_nether, the_end)");
		ServerPlayer p = player(args, "player");
		if (p == null) throw new ToolError("No player to send");
		BlockPos near = args.has("x") && args.has("z") ? new BlockPos(args.get("x").getAsInt(), 0, args.get("z").getAsInt()) : null;
		var at = Dimensions.visit(p, level, near);
		return String.format(Locale.ROOT, "Sent %s to %s at %.0f %.0f %.0f.", p.getScoreboardName(), level.dimension().identifier(), at.x, at.y, at.z);
	}

	// ---------------------------------------------------------------- managing definitions

	private static String kind(JsonObject def) {
		if (Dimensions.isDimension(def)) return "dimension";
		if (Effects.isEffect(def)) return "effect";
		if (Morphs.isMorph(def)) return "morph";
		return CustomBlocks.isBlock(def) ? "block" : CustomEntities.isEntity(def) ? "creature" : "item";
	}

	/** One line per definition: id, kind, name and which properties it uses. */
	private static String summary(JsonObject def) {
		JsonObject p = CustomItems.props(def);
		StringBuilder sb = new StringBuilder("- ").append(id(def)).append(" (").append(kind(def)).append("): ").append(def.get("name").getAsString());
		if (!p.keySet().isEmpty()) sb.append(" [").append(String.join(", ", new java.util.TreeSet<>(p.keySet()))).append(']');
		for (String r : CustomRecipes.describe(def)) sb.append("\n    recipe ").append(r);
		return sb.toString();
	}

	private String listDefinitions(JsonObject args) throws ToolError {
		if (args.has("id") && !args.get("id").getAsString().isBlank()) {
			JsonObject def = itemDef(args);
			JsonObject shown = def.deepCopy();
			shown.remove("model"); // the picture is what view_item is for; this keeps the answer small
			return new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(shown)
				+ "\n(model omitted; use view_item to see it)";
		}
		StringBuilder sb = new StringBuilder();
		List<JsonObject> defs = new ArrayList<>(CustomItems.DEFS.values());
		defs.sort(java.util.Comparator.comparing(GameTools::kind).thenComparing(GameTools::id));
		sb.append(defs.isEmpty() ? "This world has no custom items, blocks or creatures yet." : "This world's custom things:");
		for (JsonObject def : defs) sb.append('\n').append(summary(def));
		List<String> lib = Library.list();
		sb.append("\nLibrary (load with library): ").append(lib.isEmpty() ? "empty" : String.join(", ", lib));
		return sb.toString();
	}

	private String deleteDefinition(JsonObject args) throws ToolError {
		JsonObject def = itemDef(args);
		if (Dimensions.isDimension(def)) {
			ServerLevel level = server.getLevel(Dimensions.key(id(def)));
			int moved = level == null ? 0 : Dimensions.unload(server, level).size();
			CustomItems.remove(server, id(def));
			return "Deleted dimension " + id(def) + (moved > 0 ? "; " + moved + " player(s) inside were sent to the overworld" : "")
				+ ". Its terrain stays saved, and comes back if a dimension with this id is defined again (reset: true wipes it).";
		}
		CustomItems.remove(server, id(def));
		return "Deleted custom " + kind(def) + " " + id(def) + ".";
	}

	private String library(JsonObject args) throws ToolError {
		String action = args.has("action") ? args.get("action").getAsString() : "list";
		try {
			switch (action) {
				case "list" -> {
					List<String> lib = Library.list();
					return lib.isEmpty() ? "The library is empty." : "In the library: " + String.join(", ", lib);
				}
				case "save" -> {
					JsonObject def = itemDef(args);
					Library.save(def);
					return "Saved " + id(def) + " to the library. Any world can load it now.";
				}
				case "load" -> {
					String id = checkId(args);
					JsonObject def = Library.load(id);
					if (def == null) throw new ToolError("No " + id + " in the library. In it: " + String.join(", ", Library.list()));
					JsonObject old = CustomItems.DEFS.get(id);
					if (old != null && !kind(old).equals(kind(def))) throw new ToolError("This world already has a " + kind(old) + " called " + id);
					if (CustomBlocks.isBlock(def)) {
						int slot = CustomBlocks.allocateSlot(id);
						if (slot < 0) throw new ToolError("This world already has " + CustomBlocks.SLOTS + " custom blocks");
						def.addProperty("slot", slot);
					}
					CustomItems.define(server, def);
					if (Dimensions.isDimension(def)) Dimensions.ensure(server, def);
					return "Loaded " + kind(def) + " " + id + " (" + def.get("name").getAsString() + ") from the library.";
				}
				default -> throw new ToolError("action must be save, load or list");
			}
		} catch (java.io.IOException e) {
			throw new ToolError("Library problem: " + e.getMessage());
		}
	}

	private CompletableFuture<Images> viewItem(JsonObject args) throws ToolError {
		JsonObject def = itemDef(args);
		if (Dimensions.isDimension(def)) throw new ToolError(id(def) + " is a dimension; visit it, or view_build a box in it (dimension " + id(def) + ")");
		if (Effects.isEffect(def)) throw new ToolError(id(def) + " is an effect; pictures can't show effects, so play it with play_effect and ask the player how it looks");
		if (Morphs.isMorph(def)) throw new ToolError(id(def) + " is a morph; pictures can't show morphs, so morph the player and ask how it looks");
		List<String> views = new ArrayList<>();
		if (args.has("views") && args.get("views").isJsonArray()) for (JsonElement e : args.getAsJsonArray("views")) views.add(e.getAsString());
		if (views.isEmpty()) views.add("southeast");
		if (views.size() > 4) throw new ToolError("At most 4 views per call.");
		for (String v : views) if (!v.equals("top")) view(v);
		int[] argb;
		int sx, sy, sz;
		boolean worn = args.has("worn") && args.get("worn").getAsBoolean();
		JsonObject wornModel = worn ? Morphs.worn(def, true) : null;
		if (worn && wornModel == null) throw new ToolError(id(def) + " has no armor.worn parts");
		if (wornModel != null || CustomEntities.isEntity(def)) {
			EntityModel em;
			try {
				em = wornModel != null ? EntityModel.parse(wornModel, 16) : CustomEntities.model(id(def));
			} catch (RuntimeException e) {
				throw new ToolError("Can't draw it worn: " + e.getMessage());
			}
			if (em == null) throw new ToolError("That creature's model is broken; redefine it");
			argb = em.flatten();
			sx = em.maxX - em.minX;
			sy = em.maxY - em.minY;
			sz = em.maxZ - em.minZ;
		} else {
			VoxelModel m = VoxelModel.parse(def.getAsJsonObject("model"));
			argb = m.argb;
			sx = m.sx;
			sy = m.sy;
			sz = m.sz;
		}
		int[] rgb = new int[argb.length];
		for (int i = 0; i < rgb.length; i++) {
			if (argb[i] == 0 || (argb[i] >>> 24) == 0) continue;
			rgb[i] = Math.max(1, argb[i] & 0xFFFFFF); // 0 means empty to the renderer, so keep black visible
		}
		Renderer.Snapshot snap = new Renderer.Snapshot(rgb, sx, sy, sz);
		String header = def.get("name").getAsString() + " (" + sx + "x" + sy + "x" + sz + " voxels)."
			+ (CustomEntities.isEntity(def) ? " The creature faces south (+z) in these pictures." : "")
			+ (worn ? " Worn on a gray player facing south (+z)." : "");
		return CompletableFuture.supplyAsync(() -> render(snap, views, header));
	}

	private static Images render(Renderer.Snapshot snap, List<String> views, String header) {
		List<byte[]> pngs = new ArrayList<>();
		StringBuilder text = new StringBuilder(header);
		for (int i = 0; i < views.size(); i++) {
			String name = views.get(i);
			text.append("\nPicture ").append(i + 1).append(": ");
			if (name.equals("top")) {
				pngs.add(Renderer.png(Renderer.top(snap)));
				text.append("top view, north up and east to the right; higher blocks are brighter.");
			} else {
				Renderer.View view = Renderer.View.valueOf(name.toUpperCase(Locale.ROOT));
				pngs.add(Renderer.png(Renderer.isometric(snap, view)));
				text.append("isometric ").append(view.caption).append('.');
			}
		}
		return new Images(pngs, text.toString());
	}

	private static final long MAX_VIEW_VOLUME = 1_000_000;

	/** A view_build request: the box to look at (clipped to the level's height) and which pictures to draw. */
	private record ViewBox(int x0, int y0, int z0, int sx, int sy, int sz, List<String> views) {
		static ViewBox of(JsonObject args, Level level) throws ToolError {
			BlockPos a = pos(args, "from"), b = pos(args, "to");
			List<String> views = new ArrayList<>();
			if (args.has("views") && args.get("views").isJsonArray()) for (JsonElement e : args.getAsJsonArray("views")) views.add(e.getAsString());
			if (views.isEmpty()) views.add("southeast");
			if (views.size() > 4) throw new ToolError("At most 4 views per call.");
			for (String v : views) if (!v.equals("top")) view(v);
			int x0 = Math.min(a.getX(), b.getX()), z0 = Math.min(a.getZ(), b.getZ());
			int y0 = Math.max(level.getMinY(), Math.min(a.getY(), b.getY())), y1 = Math.min(level.getMaxY(), Math.max(a.getY(), b.getY()));
			int sx = Math.abs(a.getX() - b.getX()) + 1, sy = y1 - y0 + 1, sz = Math.abs(a.getZ() - b.getZ()) + 1;
			long volume = (long) sx * sy * sz;
			if (volume > MAX_VIEW_VOLUME) throw new ToolError("Box too big to view (" + volume + " blocks, max " + MAX_VIEW_VOLUME + ").");
			return new ViewBox(x0, y0, z0, sx, sy, sz, views);
		}

		String header() {
			return "Box x " + x0 + ".." + (x0 + sx - 1) + ", y " + y0 + ".." + (y0 + sy - 1) + ", z " + z0 + ".." + (z0 + sz - 1) + ".";
		}

		/** Whether every chunk of the box is loaded in this level. */
		boolean loaded(Level level) {
			for (int cx = x0 >> 4; cx <= (x0 + sx - 1) >> 4; cx++) {
				for (int cz = z0 >> 4; cz <= (z0 + sz - 1) >> 4; cz++) if (!level.getChunkSource().hasChunk(cx, cz)) return false;
			}
			return true;
		}

		/** Read the box's block colors. Run on the level's thread. */
		Renderer.Snapshot read(Level level) {
			int[] colors = new int[sx * sy * sz];
			Map<Integer, Integer> customColors = new HashMap<>();
			BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
			for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
				mp.set(x0 + x, y0 + y, z0 + z);
				BlockState st = level.getBlockState(mp);
				if (st.isAir() || (!st.liquid() && st.getCollisionShape(level, mp).isEmpty())) continue;
				int rgb = st.is(CustomBlocks.BLOCK) ? customColors.computeIfAbsent(st.getValue(CustomBlocks.VARIANT), GameTools::customBlockColor)
					: st.getMapColor(level, mp).col;
				if (rgb != 0) colors[(y * sz + z) * sx + x] = rgb;
			}
			return new Renderer.Snapshot(colors, sx, sy, sz);
		}
	}

	private CompletableFuture<Images> viewBuild(JsonObject args) throws ToolError {
		ServerLevel level = level(args);
		ViewBox box = ViewBox.of(args, level);
		// Read the blocks on the server thread once builds are done; draw the pictures off it.
		return builds.idle().thenApplyAsync(v -> box.read(level), server)
			.thenApplyAsync(snap -> render(snap, box.views(), box.header()));
	}

	/** Whether a view_build call could be answered from this level, i.e. it's for this dimension and the box is loaded. Level's thread. */
	public static boolean canViewBuild(Level level, JsonObject args) {
		if (args.has("dimension") && !args.get("dimension").getAsString().isBlank()) {
			String dim = args.get("dimension").getAsString();
			if (!dim.equals(level.dimension().identifier().toString()) && !dim.equals(level.dimension().identifier().getPath())) return false;
		}
		try {
			return ViewBox.of(args, level).loaded(level);
		} catch (ToolError | RuntimeException e) {
			return false; // let the host report the problem
		}
	}

	/**
	 * view_build against a level this game has loaded, for a player whose own Claude asks (their client has the blocks
	 * already, so nothing needs to come from the host). Reads blocks on levelThread and draws on the calling thread.
	 * Returns null if the box isn't loaded after all.
	 */
	public static JsonObject viewBuildOn(Level level, java.util.concurrent.Executor levelThread, JsonObject args) {
		try {
			ViewBox box = ViewBox.of(args, level);
			Renderer.Snapshot snap = CompletableFuture.supplyAsync(() -> box.loaded(level) ? box.read(level) : null, levelThread)
				.get(60, TimeUnit.SECONDS);
			if (snap == null) return null;
			Images im = render(snap, box.views(), box.header());
			return toolResult(im.text(), im.pngs(), false);
		} catch (ToolError e) {
			return toolResult("Error: " + e.getMessage(), List.of(), true);
		} catch (Exception e) {
			ClaudeCraft.LOGGER.warn("Local view_build failed", e);
			return null;
		}
	}

	/** Completes once every queued build (anyone's) has been placed. Any thread. */
	public CompletableFuture<Void> allBuildsIdle() {
		return builds.idle();
	}

	private static int customBlockColor(int slot) {
		String id = CustomBlocks.idForSlot(slot);
		JsonObject def = id == null ? null : CustomItems.DEFS.get(id);
		try {
			return def == null ? 0x808080 : VoxelModel.parse(def.getAsJsonObject("model")).averageColor() & 0xFFFFFF;
		} catch (RuntimeException e) {
			return 0x808080;
		}
	}

	private static Renderer.View view(String name) throws ToolError {
		try {
			return Renderer.View.valueOf(name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ToolError("Unknown view '" + name + "' (southeast, southwest, northwest, northeast, top)");
		}
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
					String key = blockName(st, idsOnly);
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
				int top = topY(level, x, z);
				min = Math.min(min, top);
				max = Math.max(max, top);
				BlockState st = level.getBlockState(mp.set(x, top, z));
				String key = blockName(st, true);
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
