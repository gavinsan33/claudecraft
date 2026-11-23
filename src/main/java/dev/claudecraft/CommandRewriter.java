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
		if (!cmd.contains("custom:")) return cmd;
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

			// Inside a selector: type=custom:x or type=!custom:x.
			Matcher sel = Pattern.compile("type\\s*=\\s*(!?)\\s*$").matcher(before);
			if (sel.find()) {
				if (!kind.equals("entity")) {
					out.append(m.group());
					continue;
				}
				boolean negated = !sel.group(1).isEmpty();
				out.setLength(out.length() - sel.group().length());
				out.append(negated ? "tag=!" + tag(id) : "type=" + ENTITY_TYPE + ",tag=" + tag(id));
				continue;
			}

			String word = lastKeyword(before);
			if (kind.equals("dimension") || "in".equals(word)) {
				out.append(kind.equals("dimension") && "in".equals(word) ? "claudecraft:" + id : m.group());
				continue;
			}
			if (kind.equals("entity") && "summon".equals(word) && before.stripTrailing().endsWith("summon")) {
				// summon custom:x [pos] [nbt]: the rest of the command belongs to summon.
				String rest = cmd.substring(m.end());
				last = cmd.length();
				out.append(ENTITY_TYPE);
				String data = "Definition:\"" + id + "\"";
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
		return out.toString();
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
		Items stub = new Items() {
			public String stack(JsonObject d) { return "STACK(" + d.get("id").getAsString() + ")"; }
			public String predicate(JsonObject d) { return "PRED(" + d.get("id").getAsString() + ")"; }
			public String blockState(JsonObject d) { return "STATE(" + d.get("id").getAsString() + ")"; }
		};
		String[][] cases = {
			{"give @s custom:ruby 5", "give @s STACK(ruby) 5"},
			{"/give @p custom:hippo", "/give @p STACK(hippo)"},
			{"setblock ~ ~ ~ custom:ruby_ore", "setblock ~ ~ ~ STATE(ruby_ore)"},
			{"fill ~ ~ ~ ~1 ~1 ~1 custom:ruby_ore replace minecraft:stone", "fill ~ ~ ~ ~1 ~1 ~1 STATE(ruby_ore) replace minecraft:stone"},
			{"fill 0 0 0 1 1 1 minecraft:air replace custom:ruby_ore", "fill 0 0 0 1 1 1 minecraft:air replace STATE(ruby_ore)"},
			{"give @s custom:ruby_ore", "give @s STACK(ruby_ore)"},
			{"clear @s custom:ruby 1", "clear @s PRED(ruby) 1"},
			{"execute if items entity @s weapon.mainhand custom:ruby run say hi", "execute if items entity @s weapon.mainhand PRED(ruby) run say hi"},
			{"execute if block ~ ~-1 ~ custom:ruby_ore run give @s custom:ruby", "execute if block ~ ~-1 ~ STATE(ruby_ore) run give @s STACK(ruby)"},
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
