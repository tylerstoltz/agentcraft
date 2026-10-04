package dev.agentcraft.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.ServerOpListEntry;

/**
 * Who may do what on a shared HQ world. Deliberately not the player's effective permission level:
 * "Open to LAN" with Allow Commands gives every guest gamemaster permissions
 * ({@code IntegratedServer#getCustomPermissionLevel}), which would make every guest a driver. Only
 * an entry in the server's ops list (level 2 or higher) or the singleplayer owner counts as op here.
 */
public final class Trust {
	private static long allowlistMtime = -1;
	private static Set<String> allowlist = Set.of();

	private Trust() {
	}

	/** The singleplayer / LAN host, or a player in the ops list with level 2 (gamemaster) or higher. */
	public static boolean isOperator(MinecraftServer server, ServerPlayer player) {
		if (server.isSingleplayerOwner(player.nameAndId())) {
			return true;
		}
		ServerOpListEntry op = server.getPlayerList().getOps().get(player.nameAndId());
		return op != null && op.permissions().level().isEqualOrHigherThan(PermissionLevel.GAMEMASTERS);
	}

	/** May give goals, answer decisions, merge, ...: an operator, or listed in {@code config/agentcraft-allowlist.json}. */
	public static boolean mayDrive(MinecraftServer server, ServerPlayer player) {
		if (isOperator(server, player)) {
			return true;
		}
		Set<String> allowed = allowlist();
		return allowed.contains(player.getPlainTextName().toLowerCase(Locale.ROOT)) || allowed.contains(player.getUUID().toString());
	}

	/** Names (lower case) and UUIDs from config/agentcraft-allowlist.json, re-read when the file changes. */
	private static synchronized Set<String> allowlist() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("agentcraft-allowlist.json");
		try {
			long mtime = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
			if (mtime != allowlistMtime) {
				allowlistMtime = mtime;
				Set<String> s = new HashSet<>();
				if (mtime != 0) {
					JsonArray arr = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
					for (JsonElement e : arr) {
						s.add(e.getAsString().trim().toLowerCase(Locale.ROOT));
					}
				}
				allowlist = Set.copyOf(s);
				AgentCraft.LOGGER.info("[trust] allowlist: {} entries", allowlist.size());
			}
		} catch (Exception e) {
			AgentCraft.LOGGER.warn("[trust] could not read {}: {}", file, e.toString());
		}
		return allowlist;
	}
}
