package dev.agentcraft.foreman;

import java.util.concurrent.CompletableFuture;

/**
 * One text connection to the Foreman, as {@link ForemanLink} uses it: a direct WebSocket
 * ({@link DirectConnector}) or the multiplayer server's relay ({@link RelayConnector}). At most one
 * send is outstanding at a time (the link chains them).
 */
public interface ForemanSocket {
	CompletableFuture<?> sendText(String text);

	/** A keep-alive; the far end answers through {@link Events#onAlive()}. */
	CompletableFuture<?> sendPing();

	CompletableFuture<?> sendClose(String reason);

	void abort();

	/** Callbacks from any thread. */
	interface Events {
		/** One complete Foreman message. */
		void onText(String text);

		/** Proof of life without a message (ping / pong). */
		void onAlive();

		/** The connection is gone; {@code reason} is shown in the HUD. */
		void onClose(String reason);
	}

	/** Opens sockets of one kind. */
	interface Connector {
		/** Shown as the link URL (HUD, dev.state). */
		String label();

		CompletableFuture<ForemanSocket> open(Events events);
	}
}
