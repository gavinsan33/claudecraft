package dev.claudecraft;

import com.google.gson.JsonObject;

import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lets commands name custom things as custom:<id>, the same way Claude's tools do. Vanilla commands only know the
 * three registered carriers (claudecraft:custom_item, custom_block, custom_entity), so each custom:<id> is turned
 * into the right vanilla syntax for where it appears:
 * <ul>
 * <li>{@code summon custom:hippo ...}: the custom entity type with its Definition tag.</li>
 * <li>{@code @e[type=custom:hippo]}: the custom entity type plus the tag every such creature carries.</li>
 * <li>Block arguments ({@code setblock}, {@code fill}, {@code execute if block}...): the custom block's state.</li>
 * <li>Item predicates ({@code clear}, {@code execute if items}): the item matched by its definition alone.</li>
 * <li>Anything else ({@code give}, {@code item replace ... with}, {@code loot}...): a full item stack with its
 * components; a creature's id gives its spawn egg.</li>
 * <li>{@code execute in custom:moon}: the dimension's id, claudecraft:moon.</li>
 * </ul>
 * Pure string work, so it can be tested without a game (see main).
 */
final class CommandRewriter {
	private CommandRewriter() {}

	/** How the game side turns a definition into item syntax; the tests use a stub. */
	interface Items {
		/** A full item argument, e.g. claudecraft:custom_item[claudecraft:definition="ruby",max_stack_size=16]. */
		String stack(JsonObject def);

		/** An item predicate that matches every copy, e.g. claudecraft:custom_item[claudecraft:definition="ruby"]. */
		String predicate(JsonObject def);

		/** A block state argument, e.g. claudecraft:custom_block[variant=3,light=0]. */
		String blockState(JsonObject def);
	}

	static final String ENTITY_TYPE = "claudecraft:custom_entity";
	static final String EFFECT_TYPE = "claudecraft:effect";

	/** The tag every creature of a definition carries, so selectors can find them. */
	static String tag(String id) {
		return "custom." + id;
	}

	private static final Pattern TOKEN = Pattern.compile("custom:([a-z0-9_]+)");
	private static final Set<String> BLOCK_WORDS = Set.of("setblock", "fill", "block", "filtered", "replace");
	private static final Set<String> PREDICATE_WORDS = Set.of("clear", "items");
	private static final Set<String> ITEM_WORDS = Set.of("give", "item", "with", "loot", "drop", "summon");
	private static final Set<String> DIMENSION_WORDS = Set.of("in");

	static String rewrite(String cmd, Function<String, JsonObject> defs, Items items) {
		if (!cmd.contains("custom:")) return nbtStates(cmd);
		StringBuilder out = new StringBuilder();
		Matcher m = TOKEN.matcher(cmd);
		int last = 0;
		while (m.find()) {
			String id = m.group(1);
			JsonObject def = defs.apply(id);
			out.append(cmd, last, m.start());
			last = m.end();
			if (def == null) {
				out.append(m.group());
				continue;
			}
			String kind = def.has("kind") ? def.get("kind").getAsString() : "item";
			String before = cmd.substring(0, m.start());

			// A block named in NBT, like a falling block's BlockState:{Name:"custom:x"} (nbtStates then splits off its properties).
			Matcher nbtName = NBT_NAME_BEFORE.matcher(before);
			if (nbtName.find() && (kind.equals("block") || nbtName.group(1).equals("Name"))) {
				out.append(kind.equals("block") ? items.blockState(def) : m.group());
				continue;
			}

			// Inside a selector: type=custom:x or type=!custom:x.
			Matcher sel = Pattern.compile("type\\s*=\\s*(!?)\\s*$").matcher(before);
			if (sel.find()) {
				if (!kind.equals("entity") && !kind.equals("effect")) {
					out.append(m.group());
					continue;
				}
				boolean negated = !sel.group(1).isEmpty();
				out.setLength(out.length() - sel.group().length());
				out.append(negated ? "tag=!" + tag(id) : "type=" + (kind.equals("effect") ? EFFECT_TYPE : ENTITY_TYPE) + ",tag=" + tag(id));
				continue;
			}

			String word = lastKeyword(before);
			if (kind.equals("dimension") || "in".equals(word)) {
				out.append(kind.equals("dimension") && "in".equals(word) ? "claudecraft:" + id : m.group());
				continue;
			}
			boolean summonable = kind.equals("entity") || kind.equals("effect");
			if (summonable && "summon".equals(word) && before.stripTrailing().endsWith("summon")) {
				// summon custom:x [pos] [nbt]: the rest of the command belongs to summon.
				String rest = cmd.substring(m.end());
				last = cmd.length();
				out.append(kind.equals("effect") ? EFFECT_TYPE : ENTITY_TYPE);
				String data = (kind.equals("effect") ? "Effect:\"" : "Definition:\"") + id + "\"";
				int brace = rest.indexOf('{');
				if (rest.isBlank()) {
					out.append(" ~ ~ ~ {").append(data).append('}');
				} else if (brace < 0) {
					out.append(rest.stripTrailing()).append(" {").append(data).append('}');
				} else {
					String inner = rest.substring(brace + 1).stripLeading();
					out.append(rest, 0, brace + 1).append(data).append(inner.startsWith("}") ? "" : ",").append(inner);
				}
				continue;
			}
			if (word == null || word.equals("summon")) {
				out.append(m.group()); // plain text, e.g. in say or tellraw
				continue;
			}
			if (BLOCK_WORDS.contains(word)) {
				out.append(kind.equals("block") ? items.blockState(def) : m.group());
				continue;
			}
			if (PREDICATE_WORDS.contains(word)) {
				out.append(items.predicate(def));
				continue;
			}
			out.append(items.stack(def));
		}
		out.append(cmd, last, cmd.length());
		return nbtStates(out.toString());
	}

	private static final Pattern NBT_NAME_BEFORE = Pattern.compile("(Name|id)\\s*:\\s*\"$");
	private static final Pattern NBT_STATE_START = Pattern.compile("(BlockState|block_state)\\s*:\\s*\\{");
	private static final Pattern NBT_INLINE = Pattern.compile("(id\\s*:\\s*\")([a-z0-9_.:/-]+)\\[([^\\]\"]*)]\"");

	/**
	 * Block states in NBT (a falling block's BlockState, a block display's block_state). Since 26.x they're
	 * {id:"minecraft:oak_log",properties:{axis:"y"}}, but the older {Name:..,Properties:{..}} is what's natural to write, and
	 * so is Name:"minecraft:oak_log[axis=y]". Vanilla reads either as nothing, and a falling block of nothing turns into
	 * sand, so translate them.
	 */
	static String nbtStates(String cmd) {
		Matcher m = NBT_STATE_START.matcher(cmd);
		StringBuilder out = new StringBuilder();
		int last = 0;
		while (m.find(last)) {
			int open = m.end() - 1, close = matchingBrace(cmd, open);
			if (close < 0) break;
			String inner = cmd.substring(open + 1, close)
				.replaceAll("(^|[{,\\s])Name\\s*:", "$1id:")
				.replaceAll("(^|[{,\\s])Properties\\s*:", "$1properties:");
			Matcher p = NBT_INLINE.matcher(inner);
			StringBuilder fixed = new StringBuilder();
			while (p.find()) {
				StringBuilder props = new StringBuilder();
				for (String kv : p.group(3).split(",")) {
					String[] e = kv.split("=", 2);
					if (e.length != 2) continue;
					if (!props.isEmpty()) props.append(',');
					props.append(e[0].trim()).append(":\"").append(e[1].trim()).append('"');
				}
				p.appendReplacement(fixed, Matcher.quoteReplacement(p.group(1) + p.group(2) + "\",properties:{" + props + "}"));
			}
			p.appendTail(fixed);
			out.append(cmd, last, open + 1).append(fixed).append('}');
			last = close + 1;
		}
		out.append(cmd, last, cmd.length());
		return out.toString();
	}

	/** The index of the brace closing the one at open, skipping quoted text; -1 if it's unbalanced. */
	private static int matchingBrace(String s, int open) {
		int depth = 0;
		boolean quoted = false;
		for (int i = open; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\') i++;
			else if (c == '"') quoted = !quoted;
			else if (!quoted && c == '{') depth++;
			else if (!quoted && c == '}' && --depth == 0) return i;
		}
		return -1;
	}

	/** The nearest command word before a position that tells what kind of argument follows. */
	private static String lastKeyword(String before) {
		String[] words = before.trim().split("\\s+");
		for (int i = words.length - 1; i >= 0; i--) {
			String w = words[i].replaceFirst("^/", "");
			if (BLOCK_WORDS.contains(w) || PREDICATE_WORDS.contains(w) || ITEM_WORDS.contains(w) || DIMENSION_WORDS.contains(w)) return w;
		}
		return null;
	}

	// ---------------------------------------------------------------- tests: java CommandRewriter.java

	public static void main(String[] args) {
		java.util.Map<String, JsonObject> defs = new java.util.HashMap<>();
		defs.put("ruby", com.google.gson.JsonParser.parseString("{\"id\":\"ruby\"}").getAsJsonObject());
		defs.put("ruby_ore", com.google.gson.JsonParser.parseString("{\"id\":\"ruby_ore\",\"kind\":\"block\"}").getAsJsonObject());
		defs.put("hippo", com.google.gson.JsonParser.parseString("{\"id\":\"hippo\",\"kind\":\"entity\"}").getAsJsonObject());
		defs.put("moon", com.google.gson.JsonParser.parseString("{\"id\":\"moon\",\"kind\":\"dimension\"}").getAsJsonObject());
		defs.put("black_hole", com.google.gson.JsonParser.parseString("{\"id\":\"black_hole\",\"kind\":\"effect\"}").getAsJsonObject());
		Items stub = new Items() {
			public String stack(JsonObject d) { return "STACK(" + d.get("id").getAsString() + ")"; }
			public String predicate(JsonObject d) { return "PRED(" + d.get("id").getAsString() + ")"; }
			public String blockState(JsonObject d) { return "claudecraft:custom_block[variant=7,light=0]"; }
		};
		String[][] cases = {
			{"give @s custom:ruby 5", "give @s STACK(ruby) 5"},
			{"/give @p custom:hippo", "/give @p STACK(hippo)"},
			{"setblock ~ ~ ~ custom:ruby_ore", "setblock ~ ~ ~ claudecraft:custom_block[variant=7,light=0]"},
			{"fill ~ ~ ~ ~1 ~1 ~1 custom:ruby_ore replace minecraft:stone", "fill ~ ~ ~ ~1 ~1 ~1 claudecraft:custom_block[variant=7,light=0] replace minecraft:stone"},
			{"fill 0 0 0 1 1 1 minecraft:air replace custom:ruby_ore", "fill 0 0 0 1 1 1 minecraft:air replace claudecraft:custom_block[variant=7,light=0]"},
			{"give @s custom:ruby_ore", "give @s STACK(ruby_ore)"},
			{"clear @s custom:ruby 1", "clear @s PRED(ruby) 1"},
			{"execute if items entity @s weapon.mainhand custom:ruby run say hi", "execute if items entity @s weapon.mainhand PRED(ruby) run say hi"},
			{"execute if block ~ ~-1 ~ custom:ruby_ore run give @s custom:ruby", "execute if block ~ ~-1 ~ claudecraft:custom_block[variant=7,light=0] run give @s STACK(ruby)"},
			{"summon custom:hippo", "summon claudecraft:custom_entity ~ ~ ~ {Definition:\"hippo\"}"},
			{"summon custom:hippo ~ ~1 ~", "summon claudecraft:custom_entity ~ ~1 ~ {Definition:\"hippo\"}"},
			{"summon custom:hippo ~ ~ ~ {CustomName:\"Bob\"}", "summon claudecraft:custom_entity ~ ~ ~ {Definition:\"hippo\",CustomName:\"Bob\"}"},
			{"summon custom:hippo ~ ~ ~ {}", "summon claudecraft:custom_entity ~ ~ ~ {Definition:\"hippo\"}"},
			{"execute at @s run summon custom:hippo ^ ^ ^3", "execute at @s run summon claudecraft:custom_entity ^ ^ ^3 {Definition:\"hippo\"}"},
			{"kill @e[type=custom:hippo,distance=..10]", "kill @e[type=claudecraft:custom_entity,tag=custom.hippo,distance=..10]"},
			{"kill @e[type=!custom:hippo]", "kill @e[tag=!custom.hippo]"},
			{"item replace entity @s armor.head with custom:ruby", "item replace entity @s armor.head with STACK(ruby)"},
			{"give @s custom:nope", "give @s custom:nope"},
			{"say custom:ruby is shiny", "say custom:ruby is shiny"},
			{"execute in custom:moon run tp @s 0 100 0", "execute in claudecraft:moon run tp @s 0 100 0"},
			{"execute in custom:moon run give @s custom:ruby", "execute in claudecraft:moon run give @s STACK(ruby)"},
			{"give @s custom:moon", "give @s custom:moon"},
			{"summon custom:black_hole ~ ~2 ~", "summon claudecraft:effect ~ ~2 ~ {Effect:\"black_hole\"}"},
			{"kill @e[type=custom:black_hole]", "kill @e[type=claudecraft:effect,tag=custom.black_hole]"},
			{"summon falling_block ~ ~5 ~ {BlockState:{Name:\"custom:ruby_ore\"}}", "summon falling_block ~ ~5 ~ {BlockState:{id:\"claudecraft:custom_block\",properties:{variant:\"7\",light:\"0\"}}}"},
			{"summon falling_block ~ ~5 ~ {BlockState:{Name:\"minecraft:oak_log[axis=x]\"},Time:1}", "summon falling_block ~ ~5 ~ {BlockState:{id:\"minecraft:oak_log\",properties:{axis:\"x\"}},Time:1}"},
			{"summon block_display ~ ~ ~ {block_state:{Name:\"minecraft:oak_stairs[facing=east, half=top]\"}}", "summon block_display ~ ~ ~ {block_state:{id:\"minecraft:oak_stairs\",properties:{facing:\"east\",half:\"top\"}}}"},
			{"summon falling_block ~ ~ ~ {BlockState:{Name:\"minecraft:stone\"}}", "summon falling_block ~ ~ ~ {BlockState:{id:\"minecraft:stone\"}}"},
			{"summon falling_block ~ ~ ~ {BlockState:{Name:\"minecraft:oak_log\",Properties:{axis:\"z\"}},CustomName:\"Name: x\"}", "summon falling_block ~ ~ ~ {BlockState:{id:\"minecraft:oak_log\",properties:{axis:\"z\"}},CustomName:\"Name: x\"}"},
			{"summon falling_block ~ ~ ~ {BlockState:{id:\"custom:ruby_ore\"}}", "summon falling_block ~ ~ ~ {BlockState:{id:\"claudecraft:custom_block\",properties:{variant:\"7\",light:\"0\"}}}"},
			{"summon falling_block ~ ~ ~ {BlockState:\"minecraft:stone\"}", "summon falling_block ~ ~ ~ {BlockState:\"minecraft:stone\"}"},
			{"setblock ~ ~ ~ minecraft:oak_log[axis=x]", "setblock ~ ~ ~ minecraft:oak_log[axis=x]"},
		};
		int failed = 0;
		for (String[] c : cases) {
			String got = rewrite(c[0], defs::get, stub);
			if (!got.equals(c[1])) {
				failed++;
				System.out.println("FAIL: " + c[0] + "\n  got:  " + got + "\n  want: " + c[1]);
			}
		}
		System.out.println(failed == 0 ? "all " + cases.length + " passed" : failed + " failed");
		if (failed > 0) System.exit(1);
	}
}
