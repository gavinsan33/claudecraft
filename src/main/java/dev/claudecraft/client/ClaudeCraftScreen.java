package dev.claudecraft.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.claudecraft.Config;
import dev.claudecraft.RemoteLink;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The ClaudeCraft menu, opened from the pause menu. "This world" holds the host's settings (only the host or an
 * operator may change them; the host applies and saves them); "You" holds this player's own. Every change applies at once.
 */
public final class ClaudeCraftScreen extends Screen {
	private static final int WIDTH = 240;
	private static final int MAX_SESSIONS = 8;

	/** The host's settings as last sent, or null until they arrive. */
	private static volatile JsonObject settings;

	private final Screen parent;
	/** Made fresh each time the widgets are (re)built, e.g. when the host's settings arrive. */
	private HeaderAndFooterLayout layout;

	static void register() {
		ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
			if (!(screen instanceof PauseScreen pause) || !pause.showsPauseMenu()) return;
			if (mc.getConnection() == null || !ClientPlayNetworking.canSend(RemoteLink.Payload.TYPE)) return;
			Screens.getWidgets(screen).add(Button.builder(Component.literal("ClaudeCraft…"), b -> mc.gui.setScreen(new ClaudeCraftScreen(screen)))
				.bounds(4, height - 24, 90, 20).build());
		});
	}

	public ClaudeCraftScreen(Screen parent) {
		super(Component.literal("ClaudeCraft"));
		this.parent = parent;
		settings = null;
		send(RemoteLink.message("get_settings"));
	}

	/** Whether the host's settings have arrived. For tests. */
	public static boolean loaded() {
		return settings != null;
	}

	/** The host sent its settings. Client thread. */
	static void onSettings(JsonObject msg) {
		settings = msg;
		if (Minecraft.getInstance().gui.screen() instanceof ClaudeCraftScreen s) s.rebuildWidgets();
	}

	/** Something changed (e.g. the host accepted this player's own Claude); ask for fresh settings if the menu is open. */
	static void refresh() {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.gui.screen() instanceof ClaudeCraftScreen) send(RemoteLink.message("get_settings"));
		});
	}

	@Override
	protected void init() {
		layout = new HeaderAndFooterLayout(this);
		layout.addTitleHeader(title, font);
		LinearLayout col = layout.addToContents(LinearLayout.vertical().spacing(6));
		JsonObject s = settings;
		boolean loaded = s != null;
		boolean canEdit = loaded && s.get("canEdit").getAsBoolean();

		col.addChild(heading("This world"));
		int max = loaded ? s.get("maxConcurrentSessions").getAsInt() : 1;
		CycleButton<Integer> sessions = CycleButton.<Integer>builder(n -> Component.literal(String.valueOf(n)), Math.clamp(max, 1, MAX_SESSIONS))
			.withValues(List.of(1, 2, 3, 4, 5, 6, 7, 8))
			.withTooltip(n -> Tooltip.create(Component.literal(
				"How many requests the host's Claude works on at once. Each is its own conversation. Follow-ups (\"make it "
					+ "bigger\") stay with the one handling them; a new request runs alongside in a free one, and /claude new "
					+ "<message> always does." + (n == 1 ? " At 1, requests wait their turn." : ""))))
			.create(0, 0, WIDTH, 20, Component.literal("Claude sessions at once"), (b, n) -> set("maxConcurrentSessions", new JsonPrimitive(n)));
		String effortNow = loaded && s.has("effort") ? s.get("effort").getAsString() : "default";
		CycleButton<String> effort = CycleButton.<String>builder(Component::literal, effortNow)
			.withValues(List.of("default", "low", "medium", "high", "xhigh", "max"))
			.withTooltip(e -> Tooltip.create(Component.literal(
				"How hard Claude thinks before acting. Higher builds more carefully but takes longer and uses more of your plan; "
					+ "default is Claude Code's own. Applies from Claude's next turn. Also /claude effort <level>.")))
			.create(0, 0, WIDTH, 20, Component.literal("Claude's effort"), (b, e) -> set("effort", new JsonPrimitive(e)));
		boolean allow = loaded && s.get("allowPlayerClaude").getAsBoolean();
		CycleButton<Boolean> friends = CycleButton.onOffBuilder(allow)
			.withTooltip(on -> Tooltip.create(Component.literal(
				"Let players who turn on \"Use my own Claude\" run Claude on their own computer, with their own account. "
					+ "Only players allowed to talk to Claude can.")))
			.create(0, 0, WIDTH, 20, Component.literal("Friends can use their own Claude"), (b, on) -> set("allowPlayerClaude", new JsonPrimitive(on)));
		for (CycleButton<?> b : List.of(sessions, effort, friends)) {
			b.active = canEdit;
			if (loaded && !canEdit) b.setTooltip(Tooltip.create(Component.literal("Only the host or an operator can change this.")));
			col.addChild(b);
		}

		col.addChild(heading("You"));
		boolean possible = OwnClaude.possibleHere();
		CycleButton<Boolean> mine = CycleButton.onOffBuilder(Config.load(OwnClaude.CONFIG_FILE).useOwnClaude)
			.withTooltip(on -> Tooltip.create(Component.literal(
				"Run Claude on this computer with your own Claude Code account when you play in someone else's world, "
					+ "instead of using the host's. The host has to allow it.")))
			.create(0, 0, WIDTH, 20, Component.literal("Use my own Claude"), (b, on) -> OwnClaude.setEnabled(on));
		mine.active = possible;
		if (!possible) mine.setTooltip(Tooltip.create(Component.literal("You're hosting, so Claude already runs on this computer.")));
		col.addChild(mine);

		col.addChild(new MultiLineTextWidget(Component.literal(whereMessagesGo(s, possible)).withStyle(ChatFormatting.GRAY), font)
			.setMaxWidth(WIDTH).setCentered(true));

		layout.addToFooter(Button.builder(Component.literal("Done"), b -> onClose()).width(200).build());
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	private static String whereMessagesGo(JsonObject s, boolean possible) {
		if (!possible) return "You're hosting: Claude runs on this computer, with your account.";
		if (s == null) return "Asking the host…";
		if (s.get("usingOwnClaude").getAsBoolean()) return "Your messages go to Claude on this computer, with your account.";
		if (Config.load(OwnClaude.CONFIG_FILE).useOwnClaude && !s.get("allowPlayerClaude").getAsBoolean()) {
			return "The host hasn't allowed friends' own Claude, so your messages go to theirs.";
		}
		return "Your messages go to the host's Claude.";
	}

	private StringWidget heading(String text) {
		return new StringWidget(WIDTH, 12, Component.literal(text).withStyle(ChatFormatting.GOLD), font);
	}

	private static void set(String key, JsonPrimitive value) {
		JsonObject msg = RemoteLink.message("set_setting");
		msg.addProperty("key", key);
		msg.add("value", value);
		send(msg);
	}

	private static void send(JsonObject msg) {
		if (Minecraft.getInstance().getConnection() != null) ClientPlayNetworking.send(new RemoteLink.Payload(msg));
	}

	@Override
	protected void repositionElements() {
		if (layout != null) layout.arrangeElements();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
