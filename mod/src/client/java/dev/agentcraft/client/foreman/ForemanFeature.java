package dev.agentcraft.client.foreman;

import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.ClientEnv;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.ForemanStatus;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.TaskStatus;
import java.net.URI;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Wires the Foreman link: creates the state model and the WebSocket client at startup, starts it
 * when the client has started, stops it on shutdown, and exposes it to the DevBridge
 * ({@code dev.state.foreman}, {@code dev.foreman}).
 *
 * <pre>
 * AGENTCRAFT_PORT     Foreman port (default 7878), always 127.0.0.1
 * AGENTCRAFT_FOREMAN  0 disables the link (the HUD then says so)
 * </pre>
 */
public final class ForemanFeature {
	private ForemanFeature() {
	}

	public static void init() {
		int port = ClientEnv.intValue("AGENTCRAFT_PORT", 7878);
		boolean enabled = ClientEnv.flag("AGENTCRAFT_FOREMAN", true);
		URI uri = URI.create("ws://127.0.0.1:" + port);
		String modVersion = FabricLoader.getInstance().getModContainer(AgentCraft.MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
		ForemanState state = new ForemanState(new LinkStatus(enabled ? LinkStatus.Phase.WAITING_RETRY : LinkStatus.Phase.DISABLED,
			uri.toString(), 0, null, System.currentTimeMillis(), System.currentTimeMillis(), false));
		// Executor: the client thread. Minecraft.getInstance() is resolved lazily (it does not exist yet during init).
		ForemanLink link = new ForemanLink(uri, modVersion, state, r -> Minecraft.getInstance().execute(r), enabled);
		Foreman.install(state, link);
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> link.start());
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> link.stop());

		DevBridge.addStateContributor((mc, o) -> o.add("foreman", stateJson()));
		DevBridge.register("dev.foreman", 10_000,
			"{reconnect?:false} -> foreman link status, backend/auth and counts; reconnect:true drops and reconnects now",
			(req, mc) -> {
				boolean reconnect = Fields.of(req).optBool("reconnect", false);
				if (reconnect) {
					link.reconnectNow();
				}
				return DevBridge.onClient(mc, ForemanFeature::stateJson);
			});
		if (ClientEnv.flag("AGENTCRAFT_DEV_TEST", false)) {
			// TEST ONLY: feed a Foreman message into the state model as if the Foreman sent it (UI states
			// that are hard to reach for real, e.g. a failed claude login banner).
			DevBridge.register("dev.test.foremanMessage", 5_000, "{message:{type, ...}} - TEST ONLY: apply as if received from the Foreman",
				(req, mc) -> {
					JsonObject msg = Fields.of(req).obj("message").json();
					String type = Fields.of(msg).nonBlank("type");
					return DevBridge.onClient(mc, () -> {
						JsonObject o = new JsonObject();
						o.addProperty("applied", Foreman.state().apply(type, msg));
						return o;
					});
				});
		}
		DevBridge.register("dev.foreman.send", 25_000,
			"{message:{type, ...payload}} -> {ack:{re, ok, error?, result?}} - send a client message (docs/protocol.md Mod -> Foreman) through"
				+ " the mod's own link, e.g. {message:{type:'goal.submit', text:'...'}} or {message:{type:'decision.answer', decisionId:'d3', option:'Merge'}}",
			(req, mc) -> {
				Fields f = Fields.of(req);
				JsonObject msg = f.obj("message").json();
				Fields.of(msg).nonBlank("type");
				return DevBridge.onClient(mc, () -> link.send(msg.deepCopy())).thenCompose(fut -> fut).thenApply(ack -> {
					JsonObject o = new JsonObject();
					o.add("ack", ForemanJson.GSON.toJsonTree(ack));
					return o;
				});
			});
	}

	/** Compact JSON view of the link + model (for dev.state / dev.foreman). Client thread. */
	public static JsonObject stateJson() {
		ForemanState s = Foreman.state();
		LinkStatus l = s.link();
		JsonObject o = new JsonObject();
		o.addProperty("link", l.phaseName());
		o.addProperty("connected", l.synced());
		o.addProperty("url", l.url());
		o.addProperty("attempt", l.attempt());
		o.addProperty("lastError", l.lastError());
		o.addProperty("phaseForMs", System.currentTimeMillis() - l.sinceMs());
		o.addProperty("everSynced", l.everSynced());
		o.addProperty("snapshots", s.snapshotCount());
		o.addProperty("messages", Foreman.link().messageCount());
		o.addProperty("lastMessageAgoMs", s.lastMessageAt() == 0 ? -1 : System.currentTimeMillis() - s.lastMessageAt());
		o.addProperty("stale", s.isStale());
		ForemanStatus fs = s.status();
		o.addProperty("backend", fs == null ? null : fs.backend().wire());
		o.addProperty("auth", fs == null ? null : fs.auth().wire());
		o.addProperty("message", fs == null ? null : fs.message());
		o.addProperty("version", fs == null ? null : fs.version());
		JsonObject counts = new JsonObject();
		counts.addProperty("agents", s.agents().size());
		counts.addProperty("activeAgents", s.agents().values().stream().filter(Protocol.Agent::isActive).count());
		counts.addProperty("tasks", s.tasks().size());
		int open = 0;
		for (Task t : s.tasks().values()) {
			if (t.status() != TaskStatus.DONE && t.status() != TaskStatus.CANCELLED) {
				open++;
			}
		}
		counts.addProperty("openTasks", open);
		counts.addProperty("decisions", s.decisions().size());
		counts.addProperty("openDecisions", s.openDecisions().size());
		counts.addProperty("repos", s.repos().size());
		counts.addProperty("memory", s.memory().size());
		counts.addProperty("goals", s.goals().size());
		counts.addProperty("feed", s.feed().size());
		o.add("counts", counts);
		Goal g = s.goal();
		if (g != null) {
			JsonObject go = new JsonObject();
			go.addProperty("id", g.id());
			go.addProperty("text", g.text());
			go.addProperty("progress", g.progress());
			go.addProperty("status", g.status().wire());
			o.add("goal", go);
		}
		Decision first = s.openDecisions().isEmpty() ? null : s.openDecisions().getFirst();
		o.addProperty("oldestOpenDecision", first == null ? null : first.id());
		return o;
	}
}
