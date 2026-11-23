package dev.claudecraft;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Designs saved outside any one world (config/claudecraft/library/<id>.json), so a frost wand or a pet dragon made
 * in one world can be loaded into another.
 */
final class Library {
	private Library() {}

	private static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve(ClaudeCraft.MOD_ID).resolve("library");
	}

	static List<String> list() {
		List<String> out = new ArrayList<>();
		if (!Files.isDirectory(dir())) return out;
		try (Stream<Path> s = Files.list(dir())) {
			for (Path p : s.sorted().toList()) {
				String f = p.getFileName().toString();
				if (f.endsWith(".json")) out.add(f.substring(0, f.length() - 5));
			}
		} catch (IOException e) {
			ClaudeCraft.LOGGER.warn("Couldn't list the design library", e);
		}
		return out;
	}

	static void save(JsonObject def) throws IOException {
		JsonObject copy = def.deepCopy();
		copy.remove("slot"); // block slots belong to a world
		Files.createDirectories(dir());
		Files.writeString(dir().resolve(def.get("id").getAsString() + ".json"),
			new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(copy));
	}

	/** A saved definition, or null. Ids are checked by the caller, so they can't climb out of the folder. */
	static JsonObject load(String id) throws IOException {
		Path f = dir().resolve(id + ".json");
		return Files.exists(f) ? JsonParser.parseString(Files.readString(f)).getAsJsonObject() : null;
	}
}
