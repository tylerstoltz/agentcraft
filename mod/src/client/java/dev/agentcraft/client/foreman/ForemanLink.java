package dev.agentcraft.client.foreman;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.foreman.LinkStatus.Phase;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.foreman.Protocol.Diff;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocketHandshakeException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Client of the Foreman: directly over its WebSocket ({@code ws://127.0.0.1:${AGENTCRAFT_PORT:-7878}},
 * {@link DirectConnector}) or, on a server running the relay, through the game connection
 * ({@link RelayConnector}); the connector is chosen again on every connect. Sends {@code hello} on every
 * connect, hands each received message to {@link ForemanState} on the client thread, matches acks
 * and diffs to requests, and reconnects forever with backoff (0.25 s doubling up to 5 s). It never
 * blocks the render or server thread: all network work happens on its own daemon threads.
 *
 * <p>A watchdog closes a connection that sends nothing (not even a pong to our 15 s pings) for
 * 45 s, or that does not deliver a snapshot within 15 s of opening.
 */
public final class ForemanLink {
	public static final long ACK_TIMEOUT_MS = 20_000;
	private static final long[] BACKOFF_MS = {250, 500, 1000, 2000, 3000, 5000};
	private static final long SILENCE_MS = 45_000;
	private static final long HANDSHAKE_MS = 15_000;
	private static final long PING_MS = 15_000;

	private final URI uri;
	private final String modVersion;
	private final ForemanState state;
	private final Executor clientThread;
	private final ScheduledExecutorService sched;
	private final ExecutorService io;
	private final ForemanSocket.Connector direct;
	private volatile Supplier<ForemanSocket.Connector> connectors;
	private volatile ForemanSocket.@Nullable Connector active;
	private final AtomicInteger generation = new AtomicInteger();
	private final AtomicLong ids = new AtomicLong();
	private final Map<String, CompletableFuture<Ack>> pendingAcks = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<Diff>> pendingDiffs = new ConcurrentHashMap<>();
	private final java.util.List<Consumer<Diff>> diffListeners = new CopyOnWriteArrayList<>();

	private volatile @Nullable ForemanSocket ws;
	private volatile boolean running;
	private volatile LinkStatus status;
	private volatile long lastInbound;
	private volatile long lastPing;
	private volatile long messages;
	private volatile int attempt;
	private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
	private final Object sendLock = new Object();

	public ForemanLink(URI uri, String modVersion, ForemanState state, Executor clientThread, boolean enabled) {
		this.uri = uri;
		this.modVersion = modVersion;
		this.state = state;
		this.clientThread = clientThread;
		this.status = new LinkStatus(enabled ? Phase.WAITING_RETRY : Phase.DISABLED, uri.toString(), 0, null,
			System.currentTimeMillis(), System.currentTimeMillis(), false);
		this.sched = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "AgentCraft-ForemanLink"));
		this.io = Executors.newCachedThreadPool(r -> daemon(r, "AgentCraft-ForemanLink-io"));
		this.direct = new DirectConnector(uri, io);
		this.connectors = () -> direct;
	}

	/** The connector to use for the next connect ({@link #direct()} unless told otherwise). */
	public void setConnectors(Supplier<ForemanSocket.Connector> chooser) {
		this.connectors = chooser;
	}

	ForemanSocket.Connector direct() {
		return direct;
	}

	/** Reconnect now if the chooser would now pick a different connector (joined / left a relay server). */
	public void reconnectIfTransportChanged() {
		sched.execute(() -> {
			if (running && connectors.get() != active) {
				reconnectNow();
			}
		});
	}

	private static Thread daemon(Runnable r, String name) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		return t;
	}

	// ------------------------------------------------------------------ lifecycle

	public synchronized void start() {
		if (running || status.phase() == Phase.DISABLED) {
			return;
		}
		running = true;
		sched.execute(this::connect);
		sched.scheduleAtFixedRate(this::watchdog, 5, 5, TimeUnit.SECONDS);
		AgentCraft.LOGGER.info("Foreman link started ({})", uri);
	}

	public synchronized void stop() {
		running = false;
		ForemanSocket s = ws;
		ws = null;
		if (s != null) {
			try {
				s.sendClose("game closing").orTimeout(500, TimeUnit.MILLISECONDS).exceptionally(t -> null).join();
			} catch (Throwable ignored) {
				// closing anyway
			}
			s.abort();
		}
		failPending("game closing");
		sched.shutdownNow();
		io.shutdownNow();
	}

	/** Drop the current connection (if any) and connect again right away. */
	public void reconnectNow() {
		sched.execute(() -> {
			ForemanSocket s = ws;
			if (s != null) {
				s.abort();
			}
			fail(generation.get(), "reconnect requested", 0);
		});
	}

	public LinkStatus status() {
		return status;
	}

	public URI uri() {
		return uri;
	}

	/** Messages received since the game started. */
	public long messageCount() {
		return messages;
	}

	public long lastInboundAt() {
		return lastInbound;
	}

	/** Called (client thread) for every {@code diff} message, also ones answering other clients' requests addressed to us. */
	public void addDiffListener(Consumer<Diff> l) {
		diffListeners.add(l);
	}

	// ------------------------------------------------------------------ connect / fail

	private void connect() {
		if (!running) {
			return;
		}
		int gen = generation.incrementAndGet();
		attempt++;
		try {
			ForemanSocket.Connector connector = connectors.get();
			active = connector;
			publish(status.url(connector.label()).with(Phase.CONNECTING, status.lastError(), 0).attempt(attempt));
			connector.open(new Events(gen))
				.whenComplete((socket, err) -> {
					if (err != null) {
						fail(gen, describe(err), -1);
						return;
					}
					if (gen != generation.get() || !running) {
						socket.abort();
						return;
					}
					ws = socket;
					lastInbound = System.currentTimeMillis();
					lastPing = lastInbound;
					publish(status.with(Phase.HANDSHAKE, null, 0));
					JsonObject hello = ForemanJson.msg("hello").put("modVersion", modVersion).put("protocol", Protocol.VERSION).put("client", "mod").json();
					sendRaw(socket, hello.toString());
				});
		} catch (Throwable t) {
			fail(gen, describe(t), -1);
		}
	}

	/** The connection of generation {@code gen} is gone: fail pending requests and schedule the next attempt. */
	private void fail(int gen, String reason, long delayOverride) {
		if (gen != generation.get()) {
			return; // a stale socket's late callback
		}
		generation.incrementAndGet();
		ForemanSocket s = ws;
		ws = null;
		if (s != null) {
			s.abort();
		}
		failPending("Foreman connection lost: " + reason);
		if (!running) {
			return;
		}
		boolean wasSynced = status.phase() == Phase.SYNCED;
		if (wasSynced) {
			attempt = 0;
			AgentCraft.LOGGER.warn("Foreman link lost: {}", reason);
		}
		long delay = delayOverride >= 0 ? delayOverride : BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
		publish(status.with(Phase.WAITING_RETRY, reason, System.currentTimeMillis() + delay));
		try {
			sched.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
		} catch (Throwable ignored) {
			// shutting down
		}
	}

	private void watchdog() {
		ForemanSocket s = ws;
		if (s == null) {
			return;
		}
		long now = System.currentTimeMillis();
		Phase p = status.phase();
		if (p == Phase.HANDSHAKE && now - status.sinceMs() > HANDSHAKE_MS) {
			fail(generation.get(), "no snapshot within " + HANDSHAKE_MS / 1000 + " s", -1);
		} else if (now - lastInbound > SILENCE_MS) {
			fail(generation.get(), "no data for " + SILENCE_MS / 1000 + " s", -1);
		} else if (now - lastPing >= PING_MS) {
			lastPing = now;
			synchronized (sendLock) {
				sendChain = sendChain.handle((v, e) -> null).thenCompose(v -> s.sendPing()).exceptionally(t -> null);
			}
		}
	}

	private void publish(LinkStatus s) {
		status = s;
		clientThread.execute(() -> state.setLink(s));
	}

	static String describe(Throwable t) {
		Throwable c = t;
		while ((c instanceof CompletionException || c.getClass() == RuntimeException.class) && c.getCause() != null) {
			c = c.getCause();
		}
		if (c instanceof ConnectException) {
			return "connection refused (Foreman not running?)";
		}
		if (c instanceof HttpTimeoutException) {
			return "connect timed out";
		}
		if (c instanceof WebSocketHandshakeException h) {
			return "handshake refused (HTTP " + h.getResponse().statusCode() + ")";
		}
		String m = c.getMessage();
		return c.getClass().getSimpleName() + (m != null ? ": " + m : "");
	}

	// ------------------------------------------------------------------ receive

	private final class Events implements ForemanSocket.Events {
		private final int gen;

		Events(int gen) {
			this.gen = gen;
		}

		@Override
		public void onText(String text) {
			if (gen == generation.get()) {
				handle(text);
			}
		}

		@Override
		public void onAlive() {
			lastInbound = System.currentTimeMillis();
		}

		@Override
		public void onClose(String reason) {
			fail(gen, reason, -1);
		}
	}

	private void handle(String text) {
		lastInbound = System.currentTimeMillis();
		messages++;
		JsonObject json;
		try {
			JsonElement el = JsonParser.parseString(text);
			if (!el.isJsonObject()) {
				return;
			}
			json = el.getAsJsonObject();
		} catch (Exception e) {
			AgentCraft.LOGGER.warn("Foreman sent invalid JSON ({} chars)", text.length());
			return;
		}
		String type = json.has("type") && json.get("type").isJsonPrimitive() ? json.get("type").getAsString() : "";
		try {
			switch (type) {
				case "ack" -> {
					Ack ack = ForemanJson.read(json, Ack.class);
					CompletableFuture<Ack> f = ack.re() == null ? null : pendingAcks.remove(ack.re());
					if (f != null) {
						f.complete(ack);
					}
				}
				case "diff" -> {
					Diff diff = ForemanJson.read(json, Diff.class);
					CompletableFuture<Diff> f = diff.requestId() == null ? null : pendingDiffs.remove(diff.requestId());
					if (f != null) {
						f.complete(diff);
					}
					clientThread.execute(() -> {
						for (Consumer<Diff> l : diffListeners) {
							try {
								l.accept(diff);
							} catch (Throwable t) {
								AgentCraft.LOGGER.warn("diff listener failed", t);
							}
						}
					});
				}
				case "error" -> AgentCraft.LOGGER.info("Foreman error: {}", json.has("message") ? json.get("message").getAsString() : json);
				case "snapshot" -> {
					// parse + apply on the client thread, then mark the link live
					clientThread.execute(() -> {
						try {
							state.receive(type, json);
						} catch (Exception e) {
							AgentCraft.LOGGER.warn("Bad snapshot from the Foreman", e);
						}
					});
					if (status.phase() != Phase.SYNCED) {
						attempt = 0;
						AgentCraft.LOGGER.info("Foreman link synced ({})", uri);
						publish(status.with(Phase.SYNCED, null, 0));
					}
				}
				default -> clientThread.execute(() -> {
					try {
						state.receive(type, json);
					} catch (Exception e) {
						AgentCraft.LOGGER.warn("Bad '{}' message from the Foreman", type, e);
					}
				});
			}
		} catch (Exception e) {
			AgentCraft.LOGGER.warn("Could not handle Foreman message '{}'", type, e);
		}
	}

	// ------------------------------------------------------------------ send

	/**
	 * Send a client message. An {@code id} is added if missing; the future completes (on the client
	 * thread) with the Foreman's ack, or fails if not connected, on timeout or when the connection drops.
	 * {@code ack.ok == false} is a normal completion: check it.
	 */
	public CompletableFuture<Ack> send(JsonObject message) {
		CompletableFuture<Ack> raw = new CompletableFuture<>();
		ForemanSocket s = ws;
		if (s == null || status.phase() != Phase.SYNCED) {
			raw.completeExceptionally(new IllegalStateException("Foreman not connected (" + status.phaseName() + ")"));
			return onClientThread(raw);
		}
		String id;
		if (message.has("id") && message.get("id").isJsonPrimitive()) {
			id = message.get("id").getAsString();
		} else {
			id = "mc-" + ids.incrementAndGet();
			message.addProperty("id", id);
		}
		if (!message.has("v")) {
			message.addProperty("v", Protocol.VERSION);
		}
		pendingAcks.put(id, raw);
		raw.orTimeout(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS).whenComplete((a, e) -> pendingAcks.remove(id));
		sendRaw(s, message.toString()).exceptionally(t -> {
			raw.completeExceptionally(t);
			return null;
		});
		return onClientThread(raw);
	}

	/** {@code diff.request}; completes (client thread) with the {@code diff} reply (check {@code diff.error()}). */
	public CompletableFuture<Diff> requestDiff(String repoId, String worktree) {
		String requestId = "mcd-" + ids.incrementAndGet();
		CompletableFuture<Diff> raw = new CompletableFuture<>();
		pendingDiffs.put(requestId, raw);
		raw.orTimeout(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS).whenComplete((d, e) -> pendingDiffs.remove(requestId));
		send(ForemanJson.msg("diff.request").put("requestId", requestId).put("repoId", repoId).put("worktree", worktree).json())
			.whenComplete((ack, err) -> {
				if (err != null) {
					raw.completeExceptionally(err);
				} else if (!ack.ok()) {
					raw.completeExceptionally(new IllegalStateException(ack.error() != null ? ack.error() : "diff.request refused"));
				}
			});
		return onClientThread(raw);
	}

	/** A future that completes like {@code raw}, but always on the client thread. */
	private <T> CompletableFuture<T> onClientThread(CompletableFuture<T> raw) {
		CompletableFuture<T> out = new CompletableFuture<>();
		raw.whenComplete((v, e) -> clientThread.execute(() -> {
			if (e != null) {
				out.completeExceptionally(e instanceof CompletionException && e.getCause() != null ? e.getCause() : e);
			} else {
				out.complete(v);
			}
		}));
		return out;
	}

	/** java.net.http allows one outstanding send per socket: chain them. */
	private CompletableFuture<?> sendRaw(ForemanSocket s, String text) {
		synchronized (sendLock) {
			CompletableFuture<?> next = sendChain.handle((v, e) -> null).thenCompose(v -> s.sendText(text));
			sendChain = next.exceptionally(t -> null);
			return next;
		}
	}

	private void failPending(String reason) {
		IllegalStateException ex = new IllegalStateException(reason);
		pendingAcks.values().forEach(f -> f.completeExceptionally(ex));
		pendingAcks.clear();
		pendingDiffs.values().forEach(f -> f.completeExceptionally(ex));
		pendingDiffs.clear();
	}
}
