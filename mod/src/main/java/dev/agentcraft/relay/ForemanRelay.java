package dev.agentcraft.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Server side of the Foreman relay (see {@link RelayPayloads}). For each modded player that asks, the
 * server opens its own WebSocket to the Foreman on {@code ws://127.0.0.1:${AGENTCRAFT_PORT:-7878}} and
 * pipes text both ways. The Foreman therefore stays loopback-only next to the server (Docker:
 * {@code network_mode: host}) and sees one ordinary client per player.
 *
 * <p>Everyone may watch ({@code hello}, {@code diff.request}). Every other intent can make agents run
 * code on the host, so it needs op level 2 or an entry in {@code config/agentcraft-allowlist.json}
 * (a JSON array of player names and/or UUIDs). Refused intents get an {@code ack} with {@code ok:false};
 * forwarded ones are logged with the player's name.
 */
public final class ForemanRelay {
	private static final Set<String> WATCHER_TYPES = Set.of("hello", "diff.request");
	private static final long PING_MS = 15_000;

	private static final Map<UUID, Pipe> PIPES = new ConcurrentHashMap<>();
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.executor(Executors.newCachedThreadPool(r -> daemon(r, "AgentCraft-Relay-io")))
		.connectTimeout(Duration.ofSeconds(3))
		.build();
	private static final URI FOREMAN = URI.create("ws://127.0.0.1:" + envInt("AGENTCRAFT_PORT", 7878));

	private static volatile @Nullable MinecraftServer server;
	private static long allowlistMtime = -1;
	private static Set<String> allowlist = Set.of();

	private ForemanRelay() {
	}

	public static void init() {
		RelayPayloads.register();
		ServerPlayNetworking.registerGlobalReceiver(RelayPayloads.C2S.TYPE, (p, ctx) -> onFrame(ctx.player(), p));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> close(handler.getPlayer().getUUID(), null));
		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			for (UUID id : Set.copyOf(PIPES.keySet())) {
				close(id, null);
			}
			server = null;
		});
		Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "AgentCraft-Relay-ping"))
			.scheduleAtFixedRate(ForemanRelay::pingAll, PING_MS, PING_MS, TimeUnit.MILLISECONDS);
	}

	// ------------------------------------------------------------------ frames from players (server thread)

	private static void onFrame(ServerPlayer player, RelayPayloads.C2S f) {
		UUID id = player.getUUID();
		if (f.kind() == RelayPayloads.OPEN) {
			close(id, null);
			open(player, f.conn());
			return;
		}
		Pipe pipe = PIPES.get(id);
		if (pipe == null || pipe.conn != f.conn()) {
			return; // stale generation
		}
		switch (f.kind()) {
			case RelayPayloads.PART, RelayPayloads.END -> {
				if (pipe.inbound.length() + f.data().length() > RelayPayloads.MAX_C2S_CHARS) {
					close(id, "message too large");
					return;
				}
				pipe.inbound.append(f.data());
				if (f.kind() == RelayPayloads.END) {
					String text = pipe.inbound.toString();
					pipe.inbound.setLength(0);
					forward(player, pipe, text);
				}
			}
			case RelayPayloads.PING -> {
				if (pipe.ws != null) {
					send(player, new RelayPayloads.S2C(pipe.conn, RelayPayloads.PONG, ""));
				}
			}
			case RelayPayloads.CLOSE -> close(id, null);
			default -> {
			}
		}
	}

	private static void open(ServerPlayer player, int conn) {
		UUID id = player.getUUID();
		Pipe pipe = new Pipe(player, conn);
		PIPES.put(id, pipe);
		HTTP.newWebSocketBuilder()
			.connectTimeout(Duration.ofSeconds(3))
			.buildAsync(FOREMAN, pipe)
			.whenComplete((ws, err) -> {
				if (err != null) {
					close(id, pipe, "Foreman unreachable from the server (" + rootMessage(err) + ")");
					return;
				}
				if (PIPES.get(id) != pipe) {
					ws.abort();
					return;
				}
				pipe.ws = ws;
				send(player, new RelayPayloads.S2C(conn, RelayPayloads.OPENED, ""));
			});
	}

	/** Gate one client message, then pass it to the Foreman or answer it with a refusal ack. */
	private static void forward(ServerPlayer player, Pipe pipe, String text) {
		JsonObject msg;
		try {
			JsonElement el = JsonParser.parseString(text);
			if (!el.isJsonObject()) {
				return;
			}
			msg = el.getAsJsonObject();
		} catch (Exception e) {
			return;
		}
		String type = str(msg, "type");
		String name = player.getPlainTextName();
		if (type.equals("hello")) {
			msg.addProperty("client", "mc:" + name); // the Foreman log names the player
		} else if (!WATCHER_TYPES.contains(type)) {
			if (!mayDrive(player)) {
				AgentCraft.LOGGER.info("[relay] refused {} from {} (not op / not in agentcraft-allowlist.json)", type, name);
				JsonObject ack = new JsonObject();
				if (msg.has("v")) {
					ack.add("v", msg.get("v"));
				}
				ack.addProperty("type", "ack");
				ack.addProperty("re", str(msg, "id"));
				ack.addProperty("ok", false);
				ack.addProperty("error", "Only ops or allowlisted players can do that on this server.");
				sendText(player, pipe.conn, ack.toString());
				return;
			}
			AgentCraft.LOGGER.info("[relay] {} -> {} {}", name, type, summary(msg));
		}
		pipe.send(msg.toString());
	}

	private static boolean mayDrive(ServerPlayer player) {
		if (Commands.LEVEL_GAMEMASTERS.check(player.permissions())) {
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
				Set<String> s = new java.util.HashSet<>();
				if (mtime != 0) {
					JsonArray arr = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
					for (JsonElement e : arr) {
						s.add(e.getAsString().trim().toLowerCase(Locale.ROOT));
					}
				}
				allowlist = Set.copyOf(s);
				AgentCraft.LOGGER.info("[relay] allowlist: {} entries", allowlist.size());
			}
		} catch (Exception e) {
			AgentCraft.LOGGER.warn("[relay] could not read {}: {}", file, e.toString());
		}
		return allowlist;
	}

	// ------------------------------------------------------------------ to players

	private static void sendText(ServerPlayer player, int conn, String text) {
		java.util.List<String> parts = RelayPayloads.split(text);
		MinecraftServer s = server;
		if (s == null) {
			return;
		}
		s.execute(() -> {
			for (int i = 0; i < parts.size(); i++) {
				byte kind = i == parts.size() - 1 ? RelayPayloads.END : RelayPayloads.PART;
				sendNow(player, new RelayPayloads.S2C(conn, kind, parts.get(i)));
			}
		});
	}

	private static void send(ServerPlayer player, RelayPayloads.S2C payload) {
		MinecraftServer s = server;
		if (s != null) {
			s.execute(() -> sendNow(player, payload));
		}
	}

	private static void sendNow(ServerPlayer player, RelayPayloads.S2C payload) {
		if (!player.hasDisconnected() && ServerPlayNetworking.canSend(player, RelayPayloads.S2C.TYPE)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	// ------------------------------------------------------------------ pipes

	private static void close(UUID id, @Nullable String reason) {
		Pipe pipe = PIPES.get(id);
		if (pipe != null) {
			close(id, pipe, reason);
		}
	}

	private static void close(UUID id, Pipe pipe, @Nullable String reason) {
		if (!PIPES.remove(id, pipe)) {
			return;
		}
		WebSocket ws = pipe.ws;
		pipe.ws = null;
		if (ws != null) {
			ws.sendClose(WebSocket.NORMAL_CLOSURE, "player left").orTimeout(500, TimeUnit.MILLISECONDS).exceptionally(t -> null);
			ws.abort();
		}
		if (reason != null) {
			send(pipe.player, new RelayPayloads.S2C(pipe.conn, RelayPayloads.CLOSED, truncate(reason)));
		}
	}

	private static void pingAll() {
		for (Pipe p : PIPES.values()) {
			WebSocket ws = p.ws;
			if (ws != null) {
				p.chain(() -> ws.sendPing(ByteBuffer.allocate(0)));
			}
		}
	}

	/** One player's Foreman WebSocket; also its {@link WebSocket.Listener}. */
	private static final class Pipe implements WebSocket.Listener {
		final ServerPlayer player;
		final int conn;
		final StringBuilder inbound = new StringBuilder();
		private final StringBuilder outbound = new StringBuilder();
		volatile @Nullable WebSocket ws;
		private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

		Pipe(ServerPlayer player, int conn) {
			this.player = player;
			this.conn = conn;
		}

		void send(String text) {
			WebSocket s = ws;
			if (s != null) {
				chain(() -> s.sendText(text, true));
			}
		}

		/** java.net.http allows one outstanding send per socket: chain them. */
		synchronized void chain(java.util.function.Supplier<CompletableFuture<?>> op) {
			sendChain = sendChain.handle((v, e) -> null).thenCompose(v -> op.get()).exceptionally(t -> null);
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			outbound.append(data);
			if (last) {
				String text = outbound.toString();
				outbound.setLength(0);
				if (PIPES.get(player.getUUID()) == this) {
					sendText(player, conn, text);
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			close(player.getUUID(), this, "Foreman closed the connection (" + statusCode + ")");
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			close(player.getUUID(), this, "Foreman connection error (" + rootMessage(error) + ")");
		}
	}

	// ------------------------------------------------------------------ helpers

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}

	private static String summary(JsonObject msg) {
		for (String k : new String[] {"text", "decisionId", "taskId", "agentId", "path"}) {
			String v = str(msg, k);
			if (!v.isEmpty()) {
				String extra = k.equals("decisionId") ? " option=" + str(msg, "option") : "";
				return k + "=" + truncate(v.replace('\n', ' ')) + extra;
			}
		}
		return "";
	}

	private static String truncate(String s) {
		return s.length() <= 200 ? s : s.substring(0, 200) + "...";
	}

	private static String rootMessage(Throwable t) {
		Throwable c = t;
		while (c.getCause() != null) {
			c = c.getCause();
		}
		return c instanceof java.net.ConnectException ? "connection refused" : c.getClass().getSimpleName();
	}

	private static int envInt(String name, int def) {
		try {
			String v = System.getenv(name);
			return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static Thread daemon(Runnable r, String name) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		return t;
	}
}
