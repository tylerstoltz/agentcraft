package dev.agentcraft.foreman;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * The Foreman's own WebSocket on loopback, using {@code java.net.http} (no Origin header, as the
 * Foreman requires). Used in singleplayer and on servers without the relay.
 */
final class DirectConnector implements ForemanSocket.Connector {
	private final URI uri;
	private final HttpClient http;

	DirectConnector(URI uri, Executor io) {
		this.uri = uri;
		this.http = HttpClient.newBuilder().executor(io).connectTimeout(Duration.ofSeconds(3)).build();
	}

	@Override
	public String label() {
		return uri.toString();
	}

	@Override
	public CompletableFuture<ForemanSocket> open(ForemanSocket.Events events) {
		return http.newWebSocketBuilder()
			.connectTimeout(Duration.ofSeconds(3))
			.buildAsync(uri, new Listener(events))
			.thenApply(Socket::new);
	}

	private record Socket(WebSocket ws) implements ForemanSocket {
		@Override
		public CompletableFuture<?> sendText(String text) {
			return ws.sendText(text, true);
		}

		@Override
		public CompletableFuture<?> sendPing() {
			return ws.sendPing(ByteBuffer.allocate(0));
		}

		@Override
		public CompletableFuture<?> sendClose(String reason) {
			return ws.sendClose(WebSocket.NORMAL_CLOSURE, reason);
		}

		@Override
		public void abort() {
			ws.abort();
		}
	}

	private static final class Listener implements WebSocket.Listener {
		private final ForemanSocket.Events events;
		private final StringBuilder buf = new StringBuilder();

		Listener(ForemanSocket.Events events) {
			this.events = events;
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			buf.append(data);
			if (last) {
				String text = buf.toString();
				buf.setLength(0);
				events.onText(text);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
			// java.net.http answers pings with a pong automatically
			events.onAlive();
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
			events.onAlive();
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			events.onClose("closed by Foreman (" + statusCode + (reason == null || reason.isEmpty() ? "" : ": " + reason) + ")");
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			events.onClose(ForemanLink.describe(error));
		}
	}
}
