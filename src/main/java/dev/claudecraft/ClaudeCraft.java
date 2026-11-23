package dev.claudecraft;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ClaudeCraft implements ModInitializer {
	public static final String MOD_ID = "claudecraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static Config config;
	private static McpServer mcp;
	private static GameTools tools;
	private static BuildQueue builds;
	private static ClaudeBridge bridge;

	@Override
	public void onInitialize() {
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
		installSystemPrompt(configDir);
		CustomItems.register();
		OreGeneration.register();
		CustomRecipes.register();
		net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.CHUNK_GENERATOR, CustomItems.id("custom"), CustomChunkGenerator.CODEC);

		ServerLifecycleEvents.SERVER_STARTED.register(server -> start(server, configDir));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> shutdown());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (builds != null) builds.tick();
			CreatureSpawning.tick(server);
			OreGeneration.tick(server);
			if (bridge != null) bridge.tickStatus(builds);
		});

		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			if (bridge == null) return;
			String text = message.signedContent();
			String prefix = config.triggerPrefix;
			if (!prefix.isEmpty()) {
				if (!text.regionMatches(true, 0, prefix, 0, prefix.length())) return;
				text = text.substring(prefix.length()).strip();
			}
			if (text.isBlank() || !config.isAllowed(sender.getScoreboardName())) return;
			bridge.submit(new ClaudeBridge.Pending(sender.getScoreboardName(), text, describeLocation(sender)));
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("claude")
				.then(Commands.literal("ask")
					.then(Commands.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
						if (!ready(ctx) || !allowed(ctx)) return 0;
						ServerPlayer p = ctx.getSource().getPlayer();
						String name = p != null ? p.getScoreboardName() : "Server";
						bridge.submit(new ClaudeBridge.Pending(name, StringArgumentType.getString(ctx, "message"),
							p != null ? describeLocation(p) : null));
						return 1;
					})))
				.then(Commands.literal("stop").executes(ctx -> {
					if (!ready(ctx) || !allowed(ctx)) return 0;
					boolean wasBuilding = builds.isBusy();
					boolean wasThinking = bridge.stop();
					builds.cancelAll();
					reply(ctx, wasThinking || wasBuilding ? "Stopping Claude." : "Claude wasn't doing anything.");
					return 1;
				}))
				.then(Commands.literal("reset").executes(ctx -> {
					if (!ready(ctx) || !allowed(ctx)) return 0;
					bridge.reset();
					builds.cancelAll();
					reply(ctx, "Started a fresh conversation with Claude.");
					return 1;
				}))
				.then(Commands.literal("undo")
					.executes(ctx -> undo(ctx, 1))
					.then(Commands.argument("steps", IntegerArgumentType.integer(1, 50))
						.executes(ctx -> undo(ctx, IntegerArgumentType.getInteger(ctx, "steps")))))
				.then(Commands.literal("effect").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
					// claude effect <id> <from> [to]: play an effect, e.g. from an item's or block's commands.
					.then(Commands.argument("id", StringArgumentType.word())
						.then(Commands.argument("from", net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3())
							.executes(ctx -> effect(ctx, false))
							.then(Commands.argument("to", net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3())
								.executes(ctx -> effect(ctx, true))))))
				.then(Commands.literal("visit").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
					// For portals: a custom block's step_commands or use_commands run "claude visit <dimension>".
					.then(Commands.argument("dimension", StringArgumentType.word())
						.executes(ctx -> visit(ctx, java.util.List.of(ctx.getSource().getPlayerOrException())))
						.then(Commands.argument("targets", net.minecraft.commands.arguments.EntityArgument.players())
							.executes(ctx -> visit(ctx, net.minecraft.commands.arguments.EntityArgument.getPlayers(ctx, "targets"))))))
				.then(Commands.literal("status").executes(ctx -> {
					if (!ready(ctx)) return 0;
					reply(ctx, "Claude: " + bridge.status());
					return 1;
				}))));
	}

	private static void start(MinecraftServer server, Path configDir) {
		config = Config.load(configDir.resolve("config.json"));
		CustomItems.load(server);
		CustomItems.onServerChange = () -> CustomRecipes.sync(server);
		CustomRecipes.sync(server);
		Dimensions.loadAll(server);
		builds = new BuildQueue(config);
		tools = new GameTools(server, config, new UndoHistory(), builds);
		mcp = new McpServer(tools);
		try {
			mcp.start(config.mcpPort);
		} catch (Exception e) {
			LOGGER.error("Couldn't start the ClaudeCraft MCP endpoint; Claude integration disabled", e);
			mcp = null;
			return;
		}
		bridge = new ClaudeBridge(server, config, tools, mcp, configDir);
	}

	static GameTools tools() {
		return tools;
	}

	/** Run a custom item's commands as the player using it. Server thread. */
	static void runItemCommands(ServerPlayer player, java.util.List<String> commands) {
		if (tools != null && !commands.isEmpty()) tools.execute(player, commands);
	}

	/**
	 * Run a creature's, block's or projectile's commands as that entity (@s), at a position (~ ~ ~), with @target
	 * standing for another entity by UUID. Server thread.
	 */
	static void runAs(net.minecraft.world.entity.Entity self, java.util.List<String> commands, net.minecraft.world.phys.Vec3 at,
			net.minecraft.world.entity.Entity target) {
		if (tools == null || commands.isEmpty() || !(self.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
		java.util.List<String> cmds = new java.util.ArrayList<>();
		for (String c : commands) cmds.add(CustomItems.withTarget(c.replaceFirst("^/", ""), target));
		tools.execute(level.getServer().createCommandSourceStack().withLevel(level).withEntity(self)
			.withPosition(at != null ? at : self.position()).withRotation(self.getRotationVector()).withSuppressedOutput(), cmds);
	}

	/** Run commands as the server at a position in a level (blocks with nobody involved). Server thread. */
	static void runAt(net.minecraft.server.level.ServerLevel level, java.util.List<String> commands, net.minecraft.world.phys.Vec3 at) {
		if (tools == null || commands.isEmpty()) return;
		java.util.List<String> cmds = new java.util.ArrayList<>();
		for (String c : commands) cmds.add(c.replaceFirst("^/", ""));
		tools.execute(level.getServer().createCommandSourceStack().withLevel(level).withPosition(at).withSuppressedOutput(), cmds);
	}

	private static int effect(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx, boolean hasTo) {
		String id = StringArgumentType.getString(ctx, "id").replaceFirst("^custom:", "");
		var def = CustomItems.DEFS.get(id);
		if (!Effects.isEffect(def)) {
			ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal("No effect " + id));
			return 0;
		}
		var from = net.minecraft.commands.arguments.coordinates.Vec3Argument.getVec3(ctx, "from");
		var to = hasTo ? net.minecraft.commands.arguments.coordinates.Vec3Argument.getVec3(ctx, "to") : from;
		Effects.play(ctx.getSource().getLevel(), def, from, to, ctx.getSource().getEntity());
		return 1;
	}

	private static int visit(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx,
			java.util.Collection<ServerPlayer> players) {
		String name = StringArgumentType.getString(ctx, "dimension");
		var level = Dimensions.level(ctx.getSource().getServer(), name);
		if (level == null) {
			ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal("No dimension " + name));
			return 0;
		}
		for (ServerPlayer p : players) Dimensions.visit(p, level, null);
		return players.size();
	}

	private static void shutdown() {
		if (bridge != null) bridge.shutdown();
		if (builds != null) builds.flushAll();
		if (mcp != null) mcp.stop();
		bridge = null;
		mcp = null;
		tools = null;
		builds = null;
	}

	/**
	 * Write the bundled system prompt, and keep it up to date across mod updates as long as the
	 * user hasn't edited it. User edits are never overwritten.
	 */
	private static void installSystemPrompt(Path configDir) {
		Path file = configDir.resolve("system-prompt.md");
		Path stamp = configDir.resolve(".system-prompt.sha256");
		try (InputStream in = ClaudeCraft.class.getResourceAsStream("/claudecraft/system-prompt.md")) {
			String bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			Files.createDirectories(configDir);
			boolean write;
			if (!Files.exists(file)) {
				write = true;
			} else {
				String current = Files.readString(file);
				String hash = sha256(current);
				String lastWritten = Files.exists(stamp) ? Files.readString(stamp).strip() : "";
				boolean untouched = hash.equals(lastWritten) || PREVIOUS_DEFAULT_PROMPTS.contains(hash);
				write = untouched && !current.equals(bundled);
			}
			if (write) {
				Files.writeString(file, bundled);
				LOGGER.info("Installed the default ClaudeCraft system prompt at {}", file);
			}
			if (write || !Files.exists(stamp)) {
				// Only stamp content we wrote ourselves, so later edits by the user are recognized.
				String onDisk = Files.readString(file);
				if (onDisk.equals(bundled)) Files.writeString(stamp, sha256(bundled));
			}
		} catch (Exception e) {
			LOGGER.error("Couldn't install the default system prompt", e);
		}
	}

	/** SHA-256 of default prompts shipped by earlier versions (before the stamp file existed). */
	private static final java.util.Set<String> PREVIOUS_DEFAULT_PROMPTS = java.util.Set.of(
		"df2b43c2220db65fc94144d9fdc24b677b469c560be98acace53147fdf8c38d5"); // 0.1.0

	private static String sha256(String s) throws java.security.NoSuchAlgorithmException {
		return java.util.HexFormat.of().formatHex(
			java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
	}

	static String describeLocation(ServerPlayer p) {
		var b = p.blockPosition();
		return "at " + b.getX() + " " + b.getY() + " " + b.getZ() + ", facing " + p.getDirection().getSerializedName()
			+ ", in " + p.level().dimension().identifier();
	}

	private static int undo(CommandContext<CommandSourceStack> ctx, int steps) {
		if (!ready(ctx) || !allowed(ctx)) return 0;
		CommandSourceStack source = ctx.getSource();
		tools.undo(steps).thenAccept(msg -> source.sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.GOLD), false));
		return 1;
	}

	private static boolean ready(CommandContext<CommandSourceStack> ctx) {
		if (bridge == null) {
			ctx.getSource().sendFailure(Component.literal("ClaudeCraft isn't running (check the log)."));
			return false;
		}
		return true;
	}

	private static boolean allowed(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer p = ctx.getSource().getPlayer();
		if (p == null || config.isAllowed(p.getScoreboardName())) return true;
		ctx.getSource().sendFailure(Component.literal("You're not on the ClaudeCraft allowedPlayers list."));
		return false;
	}

	private static void reply(CommandContext<CommandSourceStack> ctx, String msg) {
		ctx.getSource().sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.GOLD), false);
	}
}
