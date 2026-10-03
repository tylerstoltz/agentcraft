package dev.agentcraft.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * {@code agentcraft-world.json} in the world folder: marks a world as an AgentCraft HQ world and
 * remembers its settings ({@code profile}, and the HQ {@code site} once built). Unknown keys are kept.
 */
public final class WorldMarker {
	public static final String FILE = "agentcraft-world.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path path;
	private final JsonObject json;

	private WorldMarker(Path path, JsonObject json) {
		this.path = path;
		this.json = json;
	}

	public static Path path(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE);
	}

	public static boolean exists(MinecraftServer server) {
		return Files.exists(path(server));
	}

	/** The marker, or an empty one (not yet saved) when the file is missing or unreadable. */
	public static WorldMarker load(MinecraftServer server) {
		Path p = path(server);
		JsonObject o = new JsonObject();
		if (Files.exists(p)) {
			try {
				JsonElement el = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
				if (el.isJsonObject()) {
					o = el.getAsJsonObject();
				}
			} catch (Exception e) {
				AgentCraft.LOGGER.warn("Could not read {}; starting a new one", p, e);
			}
		}
		return new WorldMarker(p, o);
	}

	public JsonObject json() {
		return json;
	}

	public @Nullable String str(String key) {
		JsonElement e = json.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	public void save() {
		try {
			Path tmp = path.resolveSibling(FILE + ".tmp");
			Files.writeString(tmp, GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not write HQ world marker {}", path, e);
		}
	}
}
