package dev.claudecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
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
import net.minecraft.world.level.block.state.BlockState;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The MCP tools Claude uses to observe and act in the world. All world access runs on the server thread. */
public class GameTools {
	/** Thrown for user-facing tool errors (bad arguments etc.). */
	static class ToolError extends Exception {
		ToolError(String msg) { super(msg); }
	}

	@FunctionalInterface
	interface Handler {
		String run(JsonObject args) throws Exception;
	}

	record Tool(String name, String description, JsonObject schema, Handler handler) {}

	private final MinecraftServer server;
	private final Config cfg;
	private final UndoHistory undo;
	private final Map<String, Tool> tools = new LinkedHashMap<>();
	/** Name of the player whose message Claude is currently handling. */
	private volatile String defaultPlayer;

	public GameTools(MinecraftServer server, Config cfg, UndoHistory undo) {
		this.server = server;
		this.cfg = cfg;
		this.undo = undo;
		registerAll();
	}

	public void setDefaultPlayer(String name) {
		this.defaultPlayer = name;
	}

	public UndoHistory undoHistory() {
		return undo;
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
				text = server.submit(() -> {
					try {
						return tool.handler().run(args);
					} catch (RuntimeException e) {
						throw e;
					} catch (Exception e) {
						throw new WrappedException(e);
					}
				}).get(120, TimeUnit.SECONDS);
			} catch (ExecutionException e) {
				Throwable cause = e.getCause() instanceof WrappedException w ? w.getCause() : e.getCause();
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
		tools.put(name, new Tool(name, description, JsonParser.parseString(schemaJson).getAsJsonObject(), handler));
	}

	// ---------------------------------------------------------------- tool definitions

	private static final String POS_SCHEMA = """
		{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"}},"required":["x","y","z"]}""";
	private static final String DIM_SCHEMA = """
		{"type":"string","description":"Dimension id, e.g. minecraft:the_nether. Defaults to the requesting player's dimension."}""";

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
				+ "untouched unless you define them in the palette. Map a character to minecraft:air to clear blocks. Undoable.",
			"""
				{"type":"object","properties":{
				  "origin":%s,
				  "palette":{"type":"object","additionalProperties":{"type":"string"}},
				  "layers":{"type":"array","items":{"type":"array","items":{"type":"string"}}},
				  "dimension":%s
				},"required":["origin","palette","layers"]}""".formatted(POS_SCHEMA, DIM_SCHEMA),
			this::placeBlueprint);

		register("fill_region",
			"Fill the box between two corners (inclusive) with one block state. Modes: solid (whole box), hollow (shell of "
				+ "the block with air inside), outline (shell only, inside untouched), walls (four vertical sides only). "
				+ "Optional replace limits changes to blocks matching that id (e.g. minecraft:grass_block) or exact state. "
				+ "Use block minecraft:air to clear space. Undoable.",
			"""
				{"type":"object","properties":{
				  "from":%s,"to":%s,
				  "block":{"type":"string"},
				  "mode":{"type":"string","enum":["solid","hollow","outline","walls"]},
				  "replace":{"type":"string","description":"Only replace blocks of this id/state."},
				  "dimension":%s
				},"required":["from","to","block"]}""".formatted(POS_SCHEMA, POS_SCHEMA, DIM_SCHEMA),
			this::fillRegion);

		register("place_blocks",
			"Place individual blocks at absolute coordinates. Each entry is {\"x\":..,\"y\":..,\"z\":..,\"block\":\"minecraft:...\"} "
				+ "or a compact array [x, y, z, \"block\"]. Undoable.",
			"""
				{"type":"object","properties":{
				  "blocks":{"type":"array","items":{}},
				  "dimension":%s
				},"required":["blocks"]}""".formatted(DIM_SCHEMA),
			this::placeBlocks);

		register("scan_area",
			"Read the blocks in a box (inclusive corners) in the same layer/row/palette format place_blueprint uses, "
				+ "with origin at the minimum corner. '.' = air. Use it to inspect terrain, existing builds, or verify your work.",
			"""
				{"type":"object","properties":{
				  "from":%s,"to":%s,
				  "ignore_properties":{"type":"boolean","description":"Show only block ids, not full states (smaller output)."},
				  "dimension":%s
				},"required":["from","to"]}""".formatted(POS_SCHEMA, POS_SCHEMA, DIM_SCHEMA),
			this::scanArea);

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
			"Revert your most recent block changes made with place_blueprint, fill_region, or place_blocks. "
				+ "Each step is one chat turn's worth of changes (including the current turn).",
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
		Map<Integer, BlockState> palette = new HashMap<>();
		for (var e : args.getAsJsonObject("palette").entrySet()) {
			if (e.getKey().codePointCount(0, e.getKey().length()) != 1) {
				throw new ToolError("Palette keys must be single characters, got '" + e.getKey() + "'");
			}
			palette.put(e.getKey().codePointAt(0), parser.parse(e.getValue().getAsString()));
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
					BlockState st = palette.get(cp);
					if (st != null) {
						changes.put(origin.offset(x, y, z), st);
					} else if (cp != '.' && cp != ' ') {
						unknown.add(new String(Character.toChars(cp)));
					}
					x++;
				}
			}
			checkSize(changes.size());
		}
		if (!unknown.isEmpty()) throw new ToolError("Characters not in palette: " + String.join(" ", unknown) + " (nothing was placed)");
		return BlockPlacer.apply(level, changes, undo);
	}

	private String fillRegion(JsonObject args) throws ToolError {
		ServerLevel level = level(args);
		BlockPos a = pos(args, "from"), b = pos(args, "to");
		BlockParser parser = new BlockParser(level);
		BlockState state = parser.parse(args.get("block").getAsString());
		String mode = args.has("mode") ? args.get("mode").getAsString() : "solid";
		BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

		BlockState replaceState = null;
		Block replaceBlock = null;
		if (args.has("replace") && !args.get("replace").getAsString().isBlank()) {
			String r = args.get("replace").getAsString();
			BlockState rs = parser.parse(r);
			if (r.contains("[")) replaceState = rs;
			else replaceBlock = rs.getBlock();
		}

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
						case "solid" -> state;
						case "hollow" -> shell ? state : air;
						case "outline" -> shell ? state : null;
						case "walls" -> edgeXZ ? state : null;
						default -> throw new ToolError("Unknown mode " + mode);
					};
					if (target == null) continue;
					BlockPos p = new BlockPos(x, y, z);
					if (replaceState != null || replaceBlock != null) {
						BlockState cur = level.getBlockState(p);
						if (replaceState != null ? cur != replaceState : !cur.is(replaceBlock)) continue;
					}
					changes.put(p, target);
				}
			}
		}
		return BlockPlacer.apply(level, changes, undo);
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
		return BlockPlacer.apply(level, changes, undo);
	}

	/** Must be called on the server thread. */
	public String undo(int steps) {
		List<UndoHistory.Entry> entries = undo.pop(Math.max(1, steps));
		if (entries.isEmpty()) return "Nothing to undo.";
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (UndoHistory.Entry entry : entries) {
			for (var dimEntry : entry.originals.entrySet()) {
				ServerLevel lvl = server.getLevel(dimEntry.getKey());
				if (lvl == null) continue;
				sb.append(BlockPlacer.apply(lvl, dimEntry.getValue(), null)).append('\n');
			}
			n++;
		}
		sb.append("Undid ").append(n).append(" step(s). ").append(undo.depth()).append(" step(s) remain.");
		return sb.toString();
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
