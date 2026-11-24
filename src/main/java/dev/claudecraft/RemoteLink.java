package dev.claudecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The connection between the host and a player who runs Claude on their own computer, with their own account
 * ({@link Config#useOwnClaude}). It rides on the Minecraft connection itself, so it works wherever the game does.
 *
 * The ClaudeCraft menu uses it too, to read and change the world's settings.
 *
 * Every message is a JSON object with a "type":
 * <pre>
 * client → server
 *   hello                         I'll run my own Claude
 *   tool {id, name, args}         a tool call from my Claude, during a turn
 *   builds_idle {id}              tell me when every queued build has been placed (I'm about to look at the world)
 *   text {line}                   a line of my Claude's reply, for chat
 *   activity {verb}               what my Claude is doing, for the status line (verb absent = thinking)
 *   session {id}                  my Claude's conversation id (only for /claude status)
 *   done {error?}                 the turn is over
 *   bye                           I've stopped using my own Claude; send my messages to the host's again
 *   get_settings                  for the ClaudeCraft menu
 *   set_setting {key, value}      change a world setting (host or operators only)
 * server → client
 *   welcome {world, tools}        accepted: the tool list to offer, and a key for remembering the conversation
 *   declined {reason}
 *   turn {prompt}                 run a turn with this prompt
 *   tool_result {id, result}      an MCP tool result
 *   builds_idle {id, reports}     everything is placed; reports are build results to pass on to Claude
 *   stop                          kill the current turn
 *   reset                         forget the conversation
 *   invite                        the host now allows players' own Claude; say hello if you want to
 *   settings {canEdit, maxConcurrentSessions, allowPlayerClaude, usingOwnClaude}
 * </pre>
 */
public final class RemoteLink {
	private static final int MAX_PAYLOAD = 16 * 1024 * 1024;

	public record Payload(String json) implements CustomPacketPayload {
		public static final Type<Payload> TYPE = new Type<>(CustomItems.id("claude_link"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC =
			ByteBufCodecs.stringUtf8(MAX_PAYLOAD).map(Payload::new, Payload::json).cast();

		public Payload(JsonObject o) {
			this(o.toString());
		}

		public JsonObject parse() {
			return JsonParser.parseString(json).getAsJsonObject();
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static JsonObject message(String type) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		return o;
	}

	static void register() {
		PayloadTypeRegistry.serverboundPlay().registerLarge(Payload.TYPE, Payload.CODEC, MAX_PAYLOAD);
		PayloadTypeRegistry.clientboundPlay().registerLarge(Payload.TYPE, Payload.CODEC, MAX_PAYLOAD);
	}

	private RemoteLink() {}
}
