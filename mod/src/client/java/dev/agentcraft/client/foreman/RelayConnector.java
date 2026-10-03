package dev.agentcraft.client.foreman;

import dev.agentcraft.foreman.ForemanSocket;
import dev.agentcraft.relay.RelayPayloads;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * The Foreman through the multiplayer server ({@code dev.agentcraft.relay.ForemanRelay}): text rides
 * {@link RelayPayloads} frames over the game connection. Available while connected to a remote server
 * that registered the relay channel; the singleplayer integrated server is never used (the local
 * Foreman is reached directly).
 */
final class RelayConnector implements ForemanSocket.Connector {
	private static final long OPEN_TIMEOUT_MS = 5_000;

	private final AtomicInteger conns = new AtomicInteger();
	private volatile @Nullable Socket current;

	/** Registers the receiver and the leave hook; once, from the client initializer. */
	void install() {
		ClientPlayNetworking.registerGlobalReceiver(RelayPayloads.S2C.TYPE, (p, ctx) -> onFrame(p));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			Socket s = current;
			if (s != null) {
				s.closed("left the server");
			}
		});
	}

	/** True when the next connect should go through the server. Any thread. */
	static boolean available() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getConnection() == null || mc.hasSingleplayerServer()) {
			return false;
		}
		try {
			return ClientPlayNetworking.canSend(RelayPayloads.C2S.TYPE);
		} catch (RuntimeException e) {
			return false; // not in play yet
		}
	}

	@Override
	public String label() {
		Minecraft mc = Minecraft.getInstance();
		var server = mc == null ? null : mc.getCurrentServer();
		return "relay:" + (server == null ? "server" : server.ip);
	}

	@Override
	public CompletableFuture<ForemanSocket> open(ForemanSocket.Events events) {
		Socket old = current;
		if (old != null) {
			old.closed("replaced");
		}
		Socket s = new Socket(conns.incrementAndGet(), events);
		current = s;
		s.frame(RelayPayloads.OPEN, "");
		return s.opened.orTimeout(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS).whenComplete((v, e) -> {
			if (e != null) {
				s.closed(null);
			}
		});
	}

	/** Client thread (Fabric runs play payload handlers there). */
	private void onFrame(RelayPayloads.S2C f) {
		Socket s = current;
		if (s == null || s.conn != f.conn()) {
			return;
		}
		switch (f.kind()) {
			case RelayPayloads.OPENED -> s.opened.complete(s);
			case RelayPayloads.PART -> s.inbound.append(f.data());
			case RelayPayloads.END -> {
				s.inbound.append(f.data());
				String text = s.inbound.toString();
				s.inbound.setLength(0);
				s.events.onText(text);
			}
			case RelayPayloads.PONG -> s.events.onAlive();
			case RelayPayloads.CLOSED -> {
				String reason = f.data().isEmpty() ? "server closed the relay" : f.data();
				s.opened.completeExceptionally(new IllegalStateException(reason));
				s.closed(reason);
			}
			default -> {
			}
		}
	}

	private final class Socket implements ForemanSocket {
		final int conn;
		final Events events;
		final StringBuilder inbound = new StringBuilder();
		final CompletableFuture<ForemanSocket> opened = new CompletableFuture<>();

		Socket(int conn, Events events) {
			this.conn = conn;
			this.events = events;
		}

		@Override
		public CompletableFuture<?> sendText(String text) {
			List<String> parts = RelayPayloads.split(text);
			for (int i = 0; i < parts.size(); i++) {
				frame(i == parts.size() - 1 ? RelayPayloads.END : RelayPayloads.PART, parts.get(i));
			}
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<?> sendPing() {
			frame(RelayPayloads.PING, "");
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<?> sendClose(String reason) {
			abort();
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void abort() {
			if (current == this) {
				current = null;
				frame(RelayPayloads.CLOSE, "");
			}
		}

		/** Forget this socket and tell the link (once); {@code reason} null = silently. */
		void closed(@Nullable String reason) {
			if (current == this) {
				current = null;
				if (reason != null) {
					events.onClose(reason);
				}
			}
		}

		void frame(byte kind, String data) {
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> {
				if (mc.getConnection() != null && ClientPlayNetworking.canSend(RelayPayloads.C2S.TYPE)) {
					ClientPlayNetworking.send(new RelayPayloads.C2S(conn, kind, data));
				}
			});
		}
	}
}
