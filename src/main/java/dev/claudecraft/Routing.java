package dev.claudecraft;

import java.util.Locale;
import java.util.Set;

/**
 * Whether a chat message continues what Claude is already doing for that player ("make it bigger", "no, blue",
 * "stop") or is a separate request ("build a tower over there", "give me a sword") that another session can take on
 * at the same time. A guess from the words: back-references and replies stay; anything else runs alongside.
 * Pure string work, testable without a game: java Routing.java.
 */
final class Routing {
	private Routing() {}

	/** Words that point back at the work in progress. */
	private static final Set<String> BACK = Set.of("it", "its", "it's", "itself", "that", "this", "those", "these", "them", "they",
		"same", "again", "instead", "more", "less", "bigger", "smaller", "taller", "shorter", "wider", "thinner", "higher", "lower",
		"too", "rest", "finish", "continue", "keep", "redo", "undo");
	/** Openers that answer or steer Claude rather than ask for something new. */
	private static final Set<String> REPLY = Set.of("no", "nope", "nah", "yes", "yeah", "yep", "sure", "ok", "okay", "thanks", "thank",
		"wait", "stop", "cancel", "actually", "nevermind", "nvm", "great", "nice", "perfect", "cool", "awesome", "hmm", "and", "but",
		"done", "good", "looks", "please", "lol", "wow");
	/** Verbs that change something already there, unless followed by "a/an/some/me", which starts something new. */
	private static final Set<String> CHANGE = Set.of("make", "change", "add", "remove", "move", "put", "turn", "paint", "fix", "replace",
		"extend", "raise", "lower", "rotate", "color", "colour", "swap", "shrink", "grow", "flip", "mirror", "widen", "fill", "clear");
	private static final Set<String> NEW_THING = Set.of("a", "an", "some", "me", "us", "him", "her", "another", "two", "three", "few");

	// ponytail: a word list, so odd phrasings misroute ("turn on the rain" reads as a change); /claude new forces
	// a separate session. Ask a small model to classify if this guesses wrong too often.
	static boolean isFollowUp(String message) {
		String[] w = message.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ]", " ").trim().split("\\s+");
		if (w.length == 0 || w[0].isEmpty()) return true;
		if (w.length <= 2) return true; // "bigger", "red instead", "thanks!"
		if (REPLY.contains(w[0])) return true;
		for (String word : w) if (BACK.contains(word)) return true;
		return CHANGE.contains(w[0]) && !NEW_THING.contains(w[1]);
	}

	public static void main(String[] args) {
		String[] follow = {"make it bigger", "no, make the roof blue", "stop", "actually use spruce instead", "add windows to it",
			"make the tower taller", "thanks!", "undo that", "and a moat around this", "paint the walls white", "put a flag on top of that",
			"more trees", "wait, not there"};
		String[] fresh = {"build a castle over there", "give me a diamond sword", "spawn a dragon", "make a pet fox for me",
			"also make me a crown", "can you build a lighthouse on the hill", "set the time to night", "add a farm next to my house please"};
		int bad = 0;
		for (String m : follow) if (!isFollowUp(m)) { bad++; System.out.println("should continue: " + m); }
		for (String m : fresh) if (isFollowUp(m)) { bad++; System.out.println("should run alongside: " + m); }
		System.out.println(bad == 0 ? "Routing self-check passed." : bad + " wrong");
		if (bad > 0) System.exit(1);
	}
}
