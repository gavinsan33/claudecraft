package dev.claudecraft;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs Claude Code headlessly (claude -p) for each batch of chat messages and relays Claude's replies into chat.
 * Up to {@link Config#maxConcurrentSessions} sessions work at once, each resuming its own conversation per world.
 * A player's messages stay with the session already handling them; new requests go to an idle session.
 *
 * A player who runs Claude on their own computer ({@link Config#useOwnClaude}, allowed by
 * {@link Config#allowPlayerClaude}) gets a remote session of their own: their messages are sent to their game as
 * turns, and their Claude's tool calls come back over {@link RemoteLink}. Everything else (queueing, the status
 * line, undo steps, /claude stop) treats it like any other session.
 */
public class ClaudeBridge {
	private static final int MAX_SESSIONS = 8;
	/** How long to wait past the turn timeout for a player's own Claude to say it's done. */
	private static final int REMOTE_GRACE_SECONDS = 60;

	/** A chat message waiting for Claude, with a snapshot of where the sender was. */
	/** A chat message for Claude. fresh: the player asked for it to run alongside their other requests (/claude new). */
	public record Pending(String player, String text, String where, boolean fresh) {
		public Pending(String player, String text, String where) {
			this(player, text, where, false);
		}
	}

	private final MinecraftServer server;
	private final Config cfg;
	private final GameTools tools;
	private final McpServer mcp;
	private final Path workDir;
	private final Path promptFile;
	private final Path sessionsFile;
	private final Path configFile;
	private final String worldName;

	/** The host's sessions, numbered #1, #2… Guarded by {@link #lock}. */
	private final List<Slot> local = new ArrayList<>();
	/** Host sessions removed by lowering maxConcurrentSessions that are finishing what they were doing. Guarded by {@link #lock}. */
	private final List<Slot> retiring = new ArrayList<>();
	/** Sessions run by players' own Claude, by player. Guarded by {@link #lock}. */
	private final Map<UUID, Slot> remote = new LinkedHashMap<>();
	/**
	 * Every session gets a number of its own for its tool calls and undo steps, never reused, so a retiring session
	 * and its replacement can't mix their changes.
	 */
	private final AtomicInteger ids = new AtomicInteger();
	/** Guards every slot's queue and batch, the slot lists and {@link #lastSlot}. */
	private final Object lock = new Object();
	/** The session that last handled each player, so their follow-ups land where the context is. */
	private final Map<String, Slot> lastSlot = new HashMap<>();
	/** Runs remote players' tool calls, which block until the game has done them. */
	private final ExecutorService linkPool = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "ClaudeCraft-Link");
		t.setDaemon(true);
		return t;
	});

	public ClaudeBridge(MinecraftServer server, Config cfg, GameTools tools, McpServer mcp, Path configDir) {
		this.server = server;
		this.cfg = cfg;
		this.tools = tools;
		this.mcp = mcp;
		this.workDir = configDir.resolve("workspace");
		this.promptFile = configDir.resolve("system-prompt.md");
		this.sessionsFile = configDir.resolve("sessions.json");
		this.configFile = configDir.resolve("config.json");
		this.worldName = server.getWorldData().getLevelName();
		try {
			Files.createDirectories(workDir);
		} catch (IOException e) {
			ClaudeCraft.LOGGER.error("Cannot create {}", workDir, e);
		}
		resizeLocal(cfg.maxConcurrentSessions);
	}

	/** Add or retire host sessions to match a new limit. Busy sessions being removed finish their queue first. */
	private void resizeLocal(int n) {
		n = Math.clamp(n, 1, MAX_SESSIONS);
		Map<String, String> saved = ClaudeProcess.loadSessions(sessionsFile);
		List<Slot> idle = new ArrayList<>();
		List<Slot> winding;
		synchronized (lock) {
			while (local.size() > n) {
				Slot s = local.removeLast();
				lastSlot.values().removeIf(x -> x == s);
				if (s.running) retiring.add(s);
				else idle.add(s);
			}
			while (local.size() < n) {
				Slot s = new Slot(ids.getAndIncrement(), local.size(), null, null);
				// A retiring session with the same number still owns that conversation; start this one fresh.
				boolean taken = retiring.stream().anyMatch(r -> r.key.equals(s.key));
				s.sessionId = taken ? null : saved.get(s.key);
				local.add(s);
			}
			winding = new ArrayList<>(retiring);
		}
		for (Slot s : idle) s.worker.shutdown();
		for (Slot s : winding) s.worker.shutdown(); // stops after its current queue
	}

	/** One Claude session: its own conversation and worker thread. */
	private final class Slot {
		/** For tool calls and undo steps; unique. */
		final int id;
		/** Its place among the host's sessions (shown as #1, #2…); -1 for a player's own Claude. */
		final int index;
		/** Key in sessions.json. The first session keeps the plain world name, as before there were several. */
		final String key;
		/** For a player's own Claude: who runs it. Null for the host's sessions. */
		final UUID owner;
		final String ownerName;
		final List<Pending> queue = new ArrayList<>();
		/** Players in the batch being worked on. */
		final List<String> handling = new ArrayList<>();
		final ExecutorService worker;
		volatile boolean running;
		volatile boolean cancelled;
		volatile String sessionId;
		/** The player whose message started the current turn. */
		volatile String requester;
		/** What it's working on, for the other sessions to know: who asked, and what. */
		volatile String request = "";
		/** What this session is doing right now, shown in the status line. */
		volatile String activity = THINKING[0];
		volatile String thinkingVerb = THINKING[0];
		/** The host's claude process for the current turn. */
		volatile ClaudeProcess proc;
		/** A player's own Claude: the turn in progress, completed with an error message or null. */
		volatile CompletableFuture<String> remoteTurn;
		volatile int remoteTurnId;

		Slot(int id, int index, UUID owner, String ownerName) {
			this.id = id;
			this.index = index;
			this.owner = owner;
			this.ownerName = ownerName;
			this.key = index == 0 ? worldName : worldName + "#" + (index + 1);
			this.worker = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "ClaudeCraft-Bridge-" + (owner == null ? String.valueOf(index + 1) : ownerName));
				t.setDaemon(true);
				return t;
			});
		}

		boolean has(String player) {
			return handling.contains(player) || queue.stream().anyMatch(m -> m.player().equals(player));
		}

		/** How its chat lines start. */
		MutableComponent label() {
			return Component.literal(owner == null ? "[Claude] " : "[" + ownerName + "'s Claude] ").withStyle(ChatFormatting.GOLD);
		}
	}

	/** The host's sessions (including retiring ones), then players' own. */
	private List<Slot> allSlots() {
		synchronized (lock) {
			List<Slot> all = new ArrayList<>(local);
			all.addAll(retiring);
			all.addAll(remote.values());
			return all;
		}
	}

	// ---------------------------------------------------------------- public API (called on the server thread)

	public void submit(Pending msg) {
		boolean busy, several;
		Slot slot, mine;
		synchronized (lock) {
			several = local.size() > 1;
			mine = sessionFor(msg.player());
			slot = pick(msg);
			slot.queue.add(msg);
			busy = slot.running;
			if (!slot.running) {
				slot.running = true;
				slot.worker.execute(() -> drain(slot));
			}
		}
		ServerPlayer p = server.getPlayerList().getPlayerByName(msg.player());
		if (p == null) return;
		if (mine != null && slot != mine && slot.owner == null) {
			p.sendSystemMessage(Component.literal("Claude #" + (slot.index + 1) + " is taking this alongside your other request.").withStyle(ChatFormatting.GRAY));
		} else if (busy) {
			boolean couldRunAlongside = several && slot.owner == null;
			p.sendSystemMessage(Component.literal("Claude is busy; your message will be handled next."
				+ (couldRunAlongside && !msg.fresh() ? " To run it alongside instead, use /claude new <message>." : "")).withStyle(ChatFormatting.GRAY));
		}
	}

	/** The host session working on (or queued for) a player's requests right now, or null. Call with {@link #lock} held. */
	private Slot sessionFor(String player) {
		for (Slot s : local) if (s.has(player)) return s;
		for (Slot s : retiring) if (s.has(player)) return s;
		return null;
	}

	/**
	 * Which session gets a message. Call with {@link #lock} held. A follow-up to what a session is doing for this
	 * player stays with it, so the conversation holds together; a separate request goes to an idle session and runs
	 * alongside, instead of waiting behind the first.
	 */
	private Slot pick(Pending msg) {
		String player = msg.player();
		// A player with their own Claude always uses it.
		for (Slot s : remote.values()) if (s.ownerName.equals(player)) return s;
		Slot mine = sessionFor(player);
		if (mine != null) {
			if (!msg.fresh() && Routing.isFollowUp(msg.text())) return mine;
			for (Slot s : local) if (!s.running) return s;
			if (!msg.fresh()) return mine; // everyone's busy: wait with the session that knows what you're doing
			Slot best = null;
			for (Slot s : local) if (s != mine && (best == null || s.queue.size() < best.queue.size())) best = s;
			return best != null ? best : mine;
		}
		Slot last = lastSlot.get(player);
		boolean lastIsLocal = last != null && local.contains(last);
		if (lastIsLocal && !last.running) return last;
		for (Slot s : local) if (!s.running) return s;
		// Everyone is busy: wait for the session that knows this player, else the shortest line.
		if (lastIsLocal) return last;
		Slot best = local.getFirst();
		for (Slot s : local) if (s.queue.size() < best.queue.size()) best = s;
		return best;
	}

	// ---------------------------------------------------------------- ticking (server thread)

	private long ticks;
	private final List<Map.Entry<Long, Runnable>> later = new ArrayList<>();

	/** Run something on the server thread a few ticks from now. Server thread. */
	private void runLater(int inTicks, Runnable r) {
		later.add(Map.entry(ticks + inTicks, r));
	}

	public void tick(BuildQueue builds) {
		ticks++;
		for (Iterator<Map.Entry<Long, Runnable>> it = later.iterator(); it.hasNext(); ) {
			Map.Entry<Long, Runnable> e = it.next();
			if (e.getKey() <= ticks) {
				it.remove();
				e.getValue().run();
			}
		}
		tickStatus(builds);
	}

	// ---------------------------------------------------------------- status line (server thread)

	/** Claude's orange, and the lighter shade that sweeps across the verb, as in Claude Code's spinner. */
	private static final int ORANGE = 0xD77757, SHIMMER = 0xF5C1A9;
	private static final String[] SPINNER = {"·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢"};
	private static final String[] THINKING = {"Thinking", "Pondering", "Scheming", "Tinkering", "Imagining", "Sketching", "Crafting", "Mulling"};

	private long activeSince = -1, statusTicks;

	/**
	 * Keep an animated status in every player's action bar while Claude is working or blocks are still going up,
	 * like Claude Code's spinner: "✻ Pondering… (12s · 340 blocks to go)". Action bar text fades on its own,
	 * so this re-sends it every couple of ticks.
	 */
	private void tickStatus(BuildQueue builds) {
		if (!cfg.showToolActivity) return;
		boolean building = builds.isBusy();
		List<Slot> all = allSlots();
		List<Slot> working = new ArrayList<>();
		for (Slot s : all) if (s.running) working.add(s);
		long now = System.currentTimeMillis();
		if (working.isEmpty() && !building) {
			if (activeSince >= 0) {
				String took = elapsed(now - activeSince);
				activeSince = -1;
				sendStatus(Component.literal("✻ ").withColor(ORANGE)
					.append(Component.literal("Worked for " + took).withStyle(ChatFormatting.GRAY)));
			}
			return;
		}
		if (activeSince < 0) activeSince = now;
		if (statusTicks++ % 2 != 0) return;

		List<Doing> doing = new ArrayList<>();
		for (Slot s : working) {
			// While a session's blocks are going up and it's only thinking, the build is what players see.
			boolean thinking = s.activity.equals(s.thinkingVerb);
			doing.add(new Doing(s.requester, thinking && tools.isBuilding(s.id) ? "Building" : s.activity));
		}

		long frame = statusTicks / 2;
		String verb = statusVerb(doing);
		MutableComponent line = Component.literal(SPINNER[(int) (frame % SPINNER.length)] + " ").withColor(ORANGE);
		int sweep = (int) (frame % (verb.length() + 8)) - 4;
		for (int i = 0; i < verb.length(); i++) {
			line.append(Component.literal(String.valueOf(verb.charAt(i))).withColor(Math.abs(i - sweep) <= 1 ? SHIMMER : ORANGE));
		}
		StringBuilder info = new StringBuilder(" (").append(elapsed(now - activeSince));
		if (building) info.append(" · ").append(String.format(java.util.Locale.ROOT, "%,d", builds.blocksLeft())).append(" blocks to go");
		int queued = 0;
		synchronized (lock) {
			for (Slot s : all) queued += s.queue.size();
		}
		if (queued > 0) info.append(" · ").append(queued).append(" message").append(queued == 1 ? "" : "s").append(" queued");
		line.append(Component.literal(info.append(')').toString()).withStyle(ChatFormatting.GRAY));
		sendStatus(line);
	}

	/** What one working session is doing, and for whom. */
	record Doing(String player, String verb) {}

	/**
	 * The animated part of the status line. One session reads as before ("Surveying…"); several are each
	 * labeled with the player they're working for ("Alex: Surveying… · Sam: Building…").
	 * With no session working (e.g. a player's undo animating) it's just "Building…".
	 */
	static String statusVerb(List<Doing> doing) {
		if (doing.isEmpty()) return "Building…";
		if (doing.size() == 1) return doing.getFirst().verb() + "…";
		StringBuilder sb = new StringBuilder();
		for (Doing d : doing) {
			if (!sb.isEmpty()) sb.append(" · ");
			if (d.player() != null) sb.append(d.player()).append(": ");
			sb.append(d.verb()).append('…');
		}
		return sb.toString();
	}

	private void sendStatus(Component c) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(c, true);
	}

	private static String elapsed(long ms) {
		long s = ms / 1000;
		return s < 60 ? s + "s" : s / 60 + "m " + s % 60 + "s";
	}

	/** Stop every session's current turn and drop queued messages. */
	public boolean stop() {
		boolean had = false;
		List<Slot> all = allSlots();
		synchronized (lock) {
			for (Slot s : all) {
				had |= s.running;
				s.queue.clear();
			}
		}
		for (Slot s : all) cancel(s);
		return had;
	}

	private void cancel(Slot s) {
		s.cancelled = true;
		ClaudeProcess p = s.proc;
		if (p != null) p.cancel();
		CompletableFuture<String> turn = s.remoteTurn;
		if (turn != null) {
			sendTo(s, RemoteLink.message("stop"));
			turn.complete(null);
		}
	}

	/** Forget the conversations so the next messages start fresh sessions. */
	public void reset() {
		stop();
		synchronized (lock) {
			lastSlot.clear();
		}
		for (Slot s : allSlots()) {
			s.sessionId = null;
			if (s.owner == null) ClaudeProcess.saveSession(sessionsFile, s.key, null);
			else sendTo(s, RemoteLink.message("reset"));
		}
	}

	public String status() {
		List<Slot> all = allSlots();
		StringBuilder sb = new StringBuilder();
		synchronized (lock) {
			for (Slot s : all) {
				if (all.size() > 1) {
					sb.append("\n  ");
					sb.append(s.owner == null ? "#" + (s.index + 1) : s.ownerName + "'s own Claude").append(": ");
				}
				sb.append(s.running ? "working" : "idle").append(", ").append(s.queue.size()).append(" queued, session ")
					.append(s.sessionId == null ? "none (fresh)" : s.sessionId);
				if (all.size() == 1) sb.append(", ");
			}
		}
		if (all.size() > 1) sb.append("\n  ");
		return sb.append(tools.undoHistory().depth()).append(" undo step(s)").toString();
	}

	public void shutdown() {
		stop();
		for (Slot s : allSlots()) s.worker.shutdownNow();
		linkPool.shutdownNow();
	}

	// ---------------------------------------------------------------- worker

	private void drain(Slot slot) {
		while (true) {
			List<Pending> batch;
			synchronized (lock) {
				slot.handling.clear();
				if (slot.queue.isEmpty()) {
					slot.running = false;
					retiring.remove(slot);
					return;
				}
				batch = new ArrayList<>(slot.queue);
				slot.queue.clear();
				for (Pending m : batch) {
					if (!slot.handling.contains(m.player())) slot.handling.add(m.player());
					lastSlot.put(m.player(), slot);
				}
				Pending first = batch.getFirst();
				slot.request = first.player() + ": \"" + (first.text().length() > 140 ? first.text().substring(0, 140) + "…" : first.text()) + "\"";
			}
			slot.cancelled = false;
			slot.thinkingVerb = THINKING[java.util.concurrent.ThreadLocalRandom.current().nextInt(THINKING.length)];
			slot.activity = slot.thinkingVerb;
			try {
				runTurn(slot, batch);
			} catch (Exception e) {
				ClaudeCraft.LOGGER.error("Claude turn failed", e);
				broadcastError(slot, "Claude failed: " + e.getMessage());
			} finally {
				slot.proc = null;
				slot.remoteTurn = null;
				// Builds keep animating after Claude's reply; they still belong to this turn's undo step.
				try {
					tools.buildsIdle(slot.id).get(30, TimeUnit.MINUTES);
				} catch (Exception e) {
					ClaudeCraft.LOGGER.warn("Gave up waiting for builds to finish", e);
				}
				server.execute(() -> tools.undoHistory().commit(slot.id));
			}
		}
	}

	/** Players another session is working for right now, so Claude knows the world may change under it. */
	/** What the other busy sessions are doing: "Alex: \"build a castle\"" each. */
	private List<String> othersBusy(Slot self) {
		List<String> out = new ArrayList<>();
		List<Slot> all = allSlots();
		synchronized (lock) {
			for (Slot s : all) if (s != self && s.running && !s.handling.isEmpty()) out.add(s.request);
		}
		return out;
	}

	private void runTurn(Slot slot, List<Pending> batch) throws Exception {
		String requester = batch.getLast().player();
		tools.setDefaultPlayer(slot.id, requester);
		slot.requester = requester;

		StringBuilder prompt = new StringBuilder("[Minecraft chat]\n");
		for (Pending m : batch) {
			prompt.append('<').append(m.player()).append('>');
			if (m.where() != null) prompt.append(" (").append(m.where()).append(')');
			prompt.append(": ").append(m.text()).append('\n');
		}
		List<String> others = othersBusy(slot);
		if (!others.isEmpty()) {
			prompt.append("[Other Claude sessions are working at the same time on: ").append(String.join("; ", others))
				.append(". They have their own conversations, so you only know what's said here. Blocks may change that you didn't place; "
					+ "leave their work alone and build somewhere else unless this request is about it. If this message only makes sense as "
					+ "part of one of those requests, say so in one short line and suggest waiting for it to finish.]\n");
		}
		if (slot.owner != null) runRemoteTurn(slot, prompt.toString());
		else runLocalTurn(slot, prompt.toString(), true);
	}

	private void runLocalTurn(Slot slot, String prompt, boolean allowRetry) throws Exception {
		ClaudeProcess proc = new ClaudeProcess();
		slot.proc = proc;
		if (slot.cancelled) proc.cancel();
		ClaudeProcess.Result r = proc.run(cfg, workDir, ClaudeProcess.readSystemPrompt(promptFile), mcp.url(slot.id), slot.sessionId,
			prompt, new ClaudeProcess.Listener() {
				@Override
				public void onSession(String id) {
					if (id != null && !id.equals(slot.sessionId)) {
						slot.sessionId = id;
						ClaudeProcess.saveSession(sessionsFile, slot.key, id);
					}
				}

				@Override
				public void onText(String line) {
					say(slot, line);
				}

				@Override
				public void onActivity(String activity) {
					slot.activity = activity == null ? slot.thinkingVerb : activity;
				}

				@Override
				public void onError(String message) {
					broadcastError(slot, message);
				}
			});
		switch (r.outcome()) {
			case OK -> {}
			case CANCELLED -> broadcast(Component.literal("Stopped.").withStyle(ChatFormatting.GRAY));
			case STALE_SESSION -> {
				if (allowRetry) {
					ClaudeCraft.LOGGER.info("Retrying with a fresh session");
					slot.sessionId = null;
					ClaudeProcess.saveSession(sessionsFile, slot.key, null);
					runLocalTurn(slot, prompt, false);
				} else {
					broadcastError(slot, "Claude error: " + r.error());
				}
			}
			case FAILED -> broadcastError(slot, "Claude error: " + r.error());
		}
	}

	/** Hand the turn to the player's own Claude and wait until their game says it's done. */
	private void runRemoteTurn(Slot slot, String prompt) throws Exception {
		CompletableFuture<String> done = new CompletableFuture<>();
		int turnId = slot.remoteTurnId + 1;
		slot.remoteTurnId = turnId;
		slot.remoteTurn = done;
		if (slot.cancelled) return;
		JsonObject msg = RemoteLink.message("turn");
		msg.addProperty("turn", turnId);
		msg.addProperty("prompt", prompt);
		sendTo(slot, msg);
		try {
			String error = done.get(cfg.turnTimeoutSeconds + REMOTE_GRACE_SECONDS, TimeUnit.SECONDS);
			if (slot.cancelled) broadcast(Component.literal("Stopped.").withStyle(ChatFormatting.GRAY));
			else if (error != null) broadcastError(slot, "Claude error: " + error);
		} catch (TimeoutException e) {
			sendTo(slot, RemoteLink.message("stop"));
			broadcastError(slot, "No answer from " + slot.ownerName + "'s Claude; giving up on this request.");
		}
	}

	// ---------------------------------------------------------------- players' own Claude (server thread)

	/** A message from a player's game over {@link RemoteLink}. */
	public void onLink(ServerPlayer player, JsonObject msg) {
		String type = msg.has("type") ? msg.get("type").getAsString() : "";
		switch (type) {
			case "hello" -> {
				hello(player);
				return;
			}
			case "get_settings" -> {
				sendSettings(player);
				return;
			}
			case "set_setting" -> {
				if (canEditSettings(player)) changeSetting(msg.get("key").getAsString(), msg.get("value"));
				sendSettings(player);
				return;
			}
			case "bye" -> {
				onDisconnect(player.getUUID());
				sendSettings(player);
				return;
			}
			default -> {}
		}
		Slot slot;
		synchronized (lock) {
			slot = remote.get(player.getUUID());
		}
		if (slot == null) return;
		int turn = msg.has("turn") ? msg.get("turn").getAsInt() : -1;
		CompletableFuture<String> current = slot.remoteTurn;
		// Only the turn in progress may act; anything from an earlier (stopped) turn is ignored.
		boolean active = current != null && !current.isDone() && turn == slot.remoteTurnId;
		switch (type) {
			case "tool" -> {
				long id = msg.get("id").getAsLong();
				if (!active) {
					reply(slot, id, GameTools.toolResult("Error: no request is in progress.", List.of(), true));
					return;
				}
				String name = msg.get("name").getAsString();
				JsonObject args = msg.has("args") && msg.get("args").isJsonObject() ? msg.getAsJsonObject("args") : new JsonObject();
				linkPool.execute(() -> reply(slot, id, tools.call(slot.id, name, args)));
			}
			case "builds_idle" -> {
				long id = msg.get("id").getAsLong();
				// Blocks placed in a tick reach players on the next one or two; wait so their game has them too.
				tools.allBuildsIdle().thenRun(() -> server.execute(() -> runLater(3, () -> {
					JsonObject out = RemoteLink.message("builds_idle");
					out.addProperty("id", id);
					out.addProperty("reports", tools.drainReports(slot.id));
					sendTo(slot, out);
				})));
			}
			case "text" -> {
				if (active && msg.has("line")) say(slot, truncate(msg.get("line").getAsString(), 1000));
			}
			case "activity" -> {
				if (active) slot.activity = msg.has("verb") ? truncate(msg.get("verb").getAsString(), 40) : slot.thinkingVerb;
			}
			case "session" -> {
				if (msg.has("id")) slot.sessionId = truncate(msg.get("id").getAsString(), 80);
			}
			case "done" -> {
				if (active) current.complete(msg.has("error") ? truncate(msg.get("error").getAsString(), 500) : null);
			}
			default -> {}
		}
	}

	private void hello(ServerPlayer player) {
		String name = player.getScoreboardName();
		String refusal = !cfg.allowPlayerClaude ? "The host hasn't turned on allowPlayerClaude, so your messages go to their Claude."
			: !cfg.isAllowed(name) ? "You're not on the host's allowedPlayers list." : null;
		if (refusal != null) {
			JsonObject out = RemoteLink.message("declined");
			out.addProperty("reason", refusal);
			ServerPlayNetworking.send(player, new RemoteLink.Payload(out));
			return;
		}
		Slot old;
		Slot slot = new Slot(ids.getAndIncrement(), -1, player.getUUID(), name);
		synchronized (lock) {
			old = remote.put(player.getUUID(), slot);
		}
		if (old != null) retire(old);
		JsonObject out = RemoteLink.message("welcome");
		out.addProperty("world", worldName);
		out.add("tools", tools.listTools());
		ServerPlayNetworking.send(player, new RemoteLink.Payload(out));
		ClaudeCraft.LOGGER.info("{} is using their own Claude", name);
	}

	// ---------------------------------------------------------------- settings menu (server thread)

	/** The host (in singleplayer or open to LAN) and operators may change the world's ClaudeCraft settings. */
	public static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");

	/** Claude's effort level, or "default" (Claude Code's own). */
	public String effort() {
		return cfg.effort == null || cfg.effort.isBlank() ? "default" : cfg.effort;
	}

	/** Set the effort level for the turns that start from now on, and save it; "default" clears it. */
	public void setEffort(String level) {
		cfg.effort = level.equals("default") ? "" : level;
		Config.saveSetting(configFile, "effort", new com.google.gson.JsonPrimitive(cfg.effort));
	}

	public boolean canEditSettings(ServerPlayer player) {
		return server.isSingleplayerOwner(player.nameAndId())
			|| player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
	}

	private void sendSettings(ServerPlayer player) {
		JsonObject out = RemoteLink.message("settings");
		out.addProperty("canEdit", canEditSettings(player));
		out.addProperty("maxConcurrentSessions", cfg.maxConcurrentSessions);
		out.addProperty("allowPlayerClaude", cfg.allowPlayerClaude);
		out.addProperty("effort", effort());
		synchronized (lock) {
			out.addProperty("usingOwnClaude", remote.containsKey(player.getUUID()));
		}
		ServerPlayNetworking.send(player, new RemoteLink.Payload(out));
	}

	/** Apply a setting from the menu now, and save it. */
	private void changeSetting(String key, com.google.gson.JsonElement value) {
		switch (key) {
			case "effort" -> {
				String level = value.getAsString();
				if (level.equals("default") || EFFORTS.contains(level)) setEffort(level);
			}
			case "maxConcurrentSessions" -> {
				int n = Math.clamp(value.getAsInt(), 1, MAX_SESSIONS);
				cfg.maxConcurrentSessions = n;
				resizeLocal(n);
				Config.saveSetting(configFile, key, new com.google.gson.JsonPrimitive(n));
			}
			case "allowPlayerClaude" -> {
				boolean on = value.getAsBoolean();
				if (on == cfg.allowPlayerClaude) return;
				cfg.allowPlayerClaude = on;
				Config.saveSetting(configFile, key, new com.google.gson.JsonPrimitive(on));
				if (on) {
					// Players who want their own Claude can start using it without rejoining.
					RemoteLink.Payload invite = new RemoteLink.Payload(RemoteLink.message("invite"));
					for (ServerPlayer p : server.getPlayerList().getPlayers()) ServerPlayNetworking.send(p, invite);
				} else {
					List<Slot> gone;
					synchronized (lock) {
						gone = new ArrayList<>(remote.values());
						remote.clear();
						lastSlot.values().removeIf(gone::contains);
					}
					JsonObject out = RemoteLink.message("declined");
					out.addProperty("reason", "The host turned off friends' own Claude, so your messages go to their Claude now.");
					for (Slot s : gone) {
						sendTo(s, out);
						retire(s);
					}
				}
			}
			default -> ClaudeCraft.LOGGER.warn("Unknown setting {}", key);
		}
	}

	/** The player left: drop their session. */
	public void onDisconnect(UUID player) {
		Slot slot;
		synchronized (lock) {
			slot = remote.remove(player);
			if (slot != null) lastSlot.values().removeIf(s -> s == slot);
		}
		if (slot != null) retire(slot);
	}

	private void retire(Slot slot) {
		synchronized (lock) {
			slot.queue.clear();
		}
		cancel(slot);
		slot.worker.shutdown(); // lets the turn wind down and commit its undo step
	}

	private void reply(Slot slot, long id, JsonObject result) {
		JsonObject out = RemoteLink.message("tool_result");
		out.addProperty("id", id);
		out.add("result", result);
		sendTo(slot, out);
	}

	private void sendTo(Slot slot, JsonObject msg) {
		if (slot.owner == null) return;
		RemoteLink.Payload payload = new RemoteLink.Payload(msg);
		server.execute(() -> {
			ServerPlayer p = server.getPlayerList().getPlayer(slot.owner);
			if (p != null) ServerPlayNetworking.send(p, payload);
		});
	}

	// ---------------------------------------------------------------- helpers

	private void say(Slot slot, String line) {
		broadcast(slot.label().append(Component.literal(line).withStyle(ChatFormatting.WHITE)));
	}

	private void broadcast(Component c) {
		server.execute(() -> server.getPlayerList().broadcastSystemMessage(c, false));
		ClaudeCraft.LOGGER.info("[chat] {}", c.getString());
	}

	private void broadcastError(Slot slot, String msg) {
		broadcast(slot.label().append(Component.literal(msg).withStyle(ChatFormatting.RED)));
	}

	private static String truncate(String s, int n) {
		return s.length() <= n ? s : s.substring(0, n);
	}
}
