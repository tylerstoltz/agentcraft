package dev.agentcraft.hq;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** TEMPORARY design-iteration knobs read from {@code agentcraft-hq-tweaks.json} in the world folder. */
final class HqTweaks {
	private static JsonObject cur = new JsonObject();

	private HqTweaks() {
	}

	static void load(MinecraftServer server) {
		Path f = server.getWorldPath(LevelResource.ROOT).resolve("agentcraft-hq-tweaks.json");
		try {
			cur = Files.exists(f) ? JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			cur = new JsonObject();
		}
	}

	static double d(String k, double def) {
		JsonElement e = cur.get(k);
		return e == null ? def : e.getAsDouble();
	}

	static String s(String k, String def) {
		JsonElement e = cur.get(k);
		return e == null ? def : e.getAsString();
	}
}
