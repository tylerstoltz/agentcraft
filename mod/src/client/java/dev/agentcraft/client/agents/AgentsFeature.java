package dev.agentcraft.client.agents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import dev.agentcraft.entity.ModEntities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Agents feature: client-side agent NPCs (architecture A, see mod/DEV.md). Owns entity rendering,
 * the per-tick sync with the Foreman state ({@link AgentManager}), walking, nameplates, and the
 * {@code dev.agents} DevBridge command. Phase 3 deepens motion/animation/particles through
 * {@link AgentHooks} and this package.
 */
public final class AgentsFeature {
	/** Right-click on an agent (client thread). The click is never sent to the server. */
	@FunctionalInterface
	public interface ClickHandler {
		void clicked(Player player, ClientAgentEntity agent);
	}

	private static final List<ClickHandler> CLICK_HANDLERS = new CopyOnWriteArrayList<>();

	private AgentsFeature() {
	}

	public static void onClick(ClickHandler h) {
		CLICK_HANDLERS.add(h);
	}

	public static void init() {
		// The type's own renderer draws nothing; the dispatcher mixin routes ClientAgentEntity to the
		// slim/wide AgentRenderer built here on every resource reload.
		EntityRenderers.register(ModEntities.AGENT, ctx -> {
			AgentRenderer.provide(ctx);
			return new NoopRenderer<>(ctx);
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> AgentManager.get().tick(mc));
		// nameplate declutter: every agent's render state is extracted, nothing is submitted yet
		LevelExtractionEvents.END_EXTRACTION.register(ctx -> PlateLayout.layout(ctx.levelState()));
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onSnapshot(ForemanState state) {
				AgentManager.get().onSnapshot();
			}

			@Override
			public void onSay(Protocol.AgentSay say) {
				AgentManager m = AgentManager.get();
				ClientAgentEntity speaker = m.entity(say.agentId());
				if (speaker == null) {
					return;
				}
				speaker.life().onSay(say, speaker.life().age());
				String to = say.to();
				ClientAgentEntity listener = to == null ? null : m.entity(to);
				if (listener != null && listener != speaker) {
					listener.life().listen(say.agentId(), 60 + Math.min(120, say.text().length()));
				}
			}

			@Override
			public void onTask(Protocol.@Nullable Task prev, Protocol.Task now) {
				if (now.status() == Protocol.TaskStatus.DONE && (prev == null || prev.status() != Protocol.TaskStatus.DONE) && now.assignee() != null) {
					ClientAgentEntity e = AgentManager.get().entity(now.assignee());
					if (e != null) {
						e.life().onTaskDone();
					}
				}
			}
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() && entity instanceof ClientAgentEntity agent) {
				if (hand == InteractionHand.MAIN_HAND) {
					CLICK_HANDLERS.forEach(h -> h.clicked(player, agent));
				}
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});
		// right-click an agent: its card (name, state, task, log tail, message/pause/stop)
		onClick((player, agent) -> Minecraft.getInstance().gui.setScreen(new AgentCardScreen(agent.agentId())));
		DevBridge.registerScreen("agent", mc -> {
			String id = AgentCardScreen.defaultAgent();
			if (id == null) {
				throw new DevBridge.DevException("no agents (is the Foreman connected?)");
			}
			return new AgentCardScreen(id);
		});
		DevBridge.register("dev.agents.card", 10_000, "{agent} -> open the agent card for one agent (like right-clicking it)", (req, mc) -> {
			String id = Fields.of(req).nonBlank("agent");
			return DevBridge.onClient(mc, () -> {
				if (Foreman.state() == null || Foreman.state().agent(id) == null) {
					throw new DevBridge.DevException("no agent '" + id + "'");
				}
				mc.gui.setScreen(new AgentCardScreen(id));
				JsonObject o = new JsonObject();
				o.addProperty("screen", AgentCardScreen.class.getSimpleName());
				o.addProperty("agent", id);
				return o;
			});
		});
		DevBridge.register("dev.agents.fx", 10_000,
			"{agent, fx: confetti|puff|sparkle|say, text?, to?} -> play an agent effect now (QA preview; 'say' shows a local speech bubble)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String id = f.nonBlank("agent");
				String fx = f.nonBlank("fx");
				String text = f.optStr("text", "Pushed the fix - tests are green again.");
				String to = f.optStr("to", null);
				return DevBridge.onClient(mc, () -> {
					ClientAgentEntity e = AgentManager.get().entity(id);
					if (e == null) {
						throw new DevBridge.DevException("no agent '" + id + "' in the world");
					}
					if (!e.life().preview(fx, text, to)) {
						throw new DevBridge.DevException("unknown fx '" + fx + "' (confetti|puff|sparkle|say)");
					}
					JsonObject o = new JsonObject();
					o.addProperty("agent", id);
					o.addProperty("fx", fx);
					return o;
				});
			});
		DevBridge.register("dev.agents.look", 10_000,
			"{agent?} -> each agent's life: posture, seat, sit, head yaw/pitch, bubble, exclaim, particles, family (QA)",
			(req, mc) -> {
				String only = Fields.of(req).optStr("agent", null);
				return DevBridge.onClient(mc, () -> {
					JsonObject o = new JsonObject();
					JsonArray list = new JsonArray();
					for (ClientAgentEntity e : AgentManager.get().entities().values()) {
						if (only != null && !only.equals(e.agentId())) {
							continue;
						}
						AgentLife l = e.life();
						JsonObject j = new JsonObject();
						j.addProperty("id", e.agentId());
						j.addProperty("family", e.view().family);
						j.addProperty("awaitingUser", e.view().awaitingUser);
						j.addProperty("posture", l.posture().name());
						j.addProperty("seated", l.seated());
						j.addProperty("sit", round(l.sitAmount()));
						Seats.Seat st = l.seat();
						if (st != null) {
							JsonObject sj = new JsonObject();
							sj.addProperty("x", round(st.sit().x));
							sj.addProperty("z", round(st.sit().z));
							sj.addProperty("top", round(st.seatTop()));
							sj.addProperty("drop", round(st.drop()));
							sj.addProperty("deskTop", Double.isNaN(st.deskTop()) ? null : round(st.deskTop()));
							j.add("seat", sj);
						}
						j.addProperty("bodyYaw", round(e.yBodyRot));
						j.addProperty("headYaw", round(e.getYHeadRot()));
						j.addProperty("headPitch", round(e.getXRot()));
						j.addProperty("bubble", l.bubble.visible() ? l.bubble.current().text() : null);
						j.addProperty("particles", l.particles.live());
						list.add(j);
					}
					o.add("agents", list);
					return o;
				});
			});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
			entity instanceof ClientAgentEntity ? InteractionResult.FAIL : InteractionResult.PASS);

		DevBridge.addStateContributor((mc, o) -> {
			JsonObject a = new JsonObject();
			AgentManager m = AgentManager.get();
			a.addProperty("count", m.entities().size());
			a.addProperty("moving", m.movingCount());
			a.addProperty("pathFailures", m.pathFailures());
			a.addProperty("plates", PlateLayout.laidOut());
			a.addProperty("plateOverlaps", PlateLayout.overlaps());
			a.addProperty("plateLayoutUs", Math.round(PlateLayout.layoutMicros() * 10) / 10.0);
			o.add("agents", a);
		});
		DevBridge.register("dev.agents", 10_000,
			"{settle?:false} -> {count, moving, plateOverlaps, agents:[{id, x,y,z, yaw, station, anchor, target, walking, state, activity,"
				+ " plate:{mode full|compact, lift, rank, nudge, focused, rect:[x0,y0,x1,y1] screen px}}]};"
				+ " settle:true snaps walking agents to their targets and nameplates to their final layout (next frame)",
			(req, mc) -> {
				boolean settle = Fields.of(req).optBool("settle", false);
				return DevBridge.onClient(mc, () -> {
					AgentManager m = AgentManager.get();
					JsonObject o = new JsonObject();
					if (settle) {
						o.addProperty("settled", m.settle());
						PlateLayout.snapNextFrame();
					}
					o.addProperty("count", m.entities().size());
					o.addProperty("moving", m.movingCount());
					o.addProperty("plates", PlateLayout.laidOut());
					o.addProperty("plateOverlaps", PlateLayout.overlaps());
					JsonArray list = new JsonArray();
					for (ClientAgentEntity e : m.entities().values()) {
						JsonObject j = new JsonObject();
						AgentView v = e.view();
						j.addProperty("id", v.id);
						j.addProperty("entityId", e.getId());
						j.addProperty("x", round(e.getX()));
						j.addProperty("y", round(e.getY()));
						j.addProperty("z", round(e.getZ()));
						j.addProperty("yaw", round(e.getYRot()));
						j.addProperty("station", v.station);
						j.addProperty("anchor", v.anchor);
						var t = e.motion().target();
						if (t != null) {
							JsonObject tj = new JsonObject();
							tj.addProperty("x", round(t.x()));
							tj.addProperty("y", round(t.y()));
							tj.addProperty("z", round(t.z()));
							tj.addProperty("yaw", round(t.yaw()));
							j.add("target", tj);
						}
						j.addProperty("walking", e.motion().walking());
						JsonArray path = new JsonArray();
						for (Vec3 p : e.motion().remainingPath()) {
							JsonArray pj = new JsonArray();
							pj.add(round(p.x));
							pj.add(round(p.y));
							pj.add(round(p.z));
							path.add(pj);
						}
						j.add("path", path);
						j.addProperty("state", v.state.wire());
						j.addProperty("activity", v.activityLine());
						j.addProperty("stale", v.stale);
						PlateLayout.Track pt = PlateLayout.track(v.id);
						if (pt != null) {
							JsonObject pj = new JsonObject();
							pj.addProperty("mode", pt.compact ? "compact" : "full");
							pj.addProperty("lift", round(pt.lift));
							pj.addProperty("target", round(pt.targetLift));
							pj.addProperty("depth", round(pt.depth));
							pj.addProperty("weight", pt.weight);
							pj.addProperty("capped", pt.capped);
							pj.addProperty("rank", pt.rank);
							pj.addProperty("nudge", Math.round(pt.nudge * 1e5) / 1e5);
							pj.addProperty("scale", round(pt.scale));
							pj.addProperty("focused", pt.focused);
							JsonArray r = new JsonArray();
							r.add(round(pt.rx0));
							r.add(round(pt.ry0));
							r.add(round(pt.rx1));
							r.add(round(pt.ry1));
							pj.add("rect", r);
							j.add("plate", pj);
						}
						j.addProperty("model", e.getSkin().model().getSerializedName());
						j.addProperty("skin", e.getSkin().body().texturePath().toString());
						list.add(j);
					}
					o.add("agents", list);
					return o;
				});
			});
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
