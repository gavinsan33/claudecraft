package dev.claudecraft;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
import java.nio.file.Path;

public class ClaudeCraft implements ModInitializer {
	public static final String MOD_ID = "claudecraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static Config config;
	private static McpServer mcp;
	private static GameTools tools;
	private static ClaudeBridge bridge;

	@Override
	public void onInitialize() {
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
		try (InputStream in = ClaudeCraft.class.getResourceAsStream("/claudecraft/system-prompt.md")) {
			Config.writeIfMissing(configDir.resolve("system-prompt.md"), new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (Exception e) {
			LOGGER.error("Couldn't write default system prompt", e);
		}

		ServerLifecycleEvents.SERVER_STARTED.register(server -> start(server, configDir));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> shutdown());

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
					reply(ctx, bridge.stop() ? "Stopping Claude." : "Claude wasn't doing anything.");
					return 1;
				}))
				.then(Commands.literal("reset").executes(ctx -> {
					if (!ready(ctx) || !allowed(ctx)) return 0;
					bridge.reset();
					reply(ctx, "Started a fresh conversation with Claude.");
					return 1;
				}))
				.then(Commands.literal("undo")
					.executes(ctx -> undo(ctx, 1))
					.then(Commands.argument("steps", IntegerArgumentType.integer(1, 50))
						.executes(ctx -> undo(ctx, IntegerArgumentType.getInteger(ctx, "steps")))))
				.then(Commands.literal("status").executes(ctx -> {
					if (!ready(ctx)) return 0;
					reply(ctx, "Claude: " + bridge.status());
					return 1;
				}))));
	}

	private static void start(MinecraftServer server, Path configDir) {
		config = Config.load(configDir.resolve("config.json"));
		tools = new GameTools(server, config, new UndoHistory());
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

	private static void shutdown() {
		if (bridge != null) bridge.shutdown();
		if (mcp != null) mcp.stop();
		bridge = null;
		mcp = null;
		tools = null;
	}

	static String describeLocation(ServerPlayer p) {
		var b = p.blockPosition();
		return "at " + b.getX() + " " + b.getY() + " " + b.getZ() + ", facing " + p.getDirection().getSerializedName()
			+ ", in " + p.level().dimension().identifier();
	}

	private static int undo(CommandContext<CommandSourceStack> ctx, int steps) {
		if (!ready(ctx) || !allowed(ctx)) return 0;
		reply(ctx, tools.undo(steps));
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
