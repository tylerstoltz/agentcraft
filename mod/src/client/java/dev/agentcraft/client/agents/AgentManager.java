package dev.agentcraft.client.agents;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps one {@link ClientAgentEntity} per Foreman agent in the client level, in sync with the
 * state model and the anchor layout (client thread, every client tick):
 * <ul>
 *   <li>new agent: spawned standing at its target anchor (no walk-in from nowhere);</li>
 *   <li>station/active change: walks there along a {@link GridPathfinder} route (teleports if
 *       there is no route, e.g. the HQ was rebuilt around it);</li>
 *   <li>agent gone after a snapshot: removed;</li>
 *   <li>Foreman link down: agents stay where they are with a dimmed "Foreman offline" plate;</li>
 *   <li>layout republished ({@code /agentcraft hq}): everyone is placed at their new anchors.</li>
 * </ul>
 * Without a layout, agents stand in a row near the world spawn so they are still visible.
 */
public final class AgentManager {
	private static final AgentManager INSTANCE = new AgentManager();
	/** Teleport instead of walking when the route is longer than this (blocks). */
	private static final double MAX_WALK = 96;

	private final Map<String, ClientAgentEntity> entities = new LinkedHashMap<>();
	private final StationAssigner assigner = new StationAssigner();
	private @Nullable ClientLevel level;
	private long layoutRevision = -1;
	private int nextEntityId = -10_000;
	private int pathFailures;

	private AgentManager() {
	}

	public static AgentManager get() {
		return INSTANCE;
	}

	/** Live agent entities by agent id (client thread). */
	public Map<String, ClientAgentEntity> entities() {
		return Collections.unmodifiableMap(entities);
	}

	public @Nullable ClientAgentEntity entity(String agentId) {
		return entities.get(agentId);
	}

	public int pathFailures() {
		return pathFailures;
	}

	/**
	 * A snapshot rebuilds the view: forget sticky slots so the assignment depends only on the state
	 * (Foreman order), not on the history of this session. Agents that change slot walk there.
	 */
	void onSnapshot() {
		assigner.clear();
	}

	public int movingCount() {
		int n = 0;
		for (ClientAgentEntity e : entities.values()) {
			if (e.motion().walking()) {
				n++;
			}
		}
		return n;
	}

	void tick(Minecraft mc) {
		ClientLevel lvl = mc.level;
		if (lvl != level) {
			entities.clear(); // the old level and its entities are gone
			assigner.clear();
			level = lvl;
			layoutRevision = -1;
		}
		if (lvl == null) {
			return;
		}
		ForemanState st = Foreman.state();
		if (st == null || !st.hasData()) {
			removeAll();
			return;
		}
		Anchors.Layout layout = Anchors.current();
		boolean relayout = layout.revision() != layoutRevision;
		layoutRevision = layout.revision();
		List<Agent> agents = new ArrayList<>(st.agents().values());
		Map<String, Anchor> targets = layout.isEmpty() ? fallbackTargets(agents, lvl) : assigner.assign(agents, layout);
		boolean stale = st.isStale();

		Set<String> keep = new HashSet<>();
		for (Agent a : agents) {
			Anchor target = targets.get(a.id());
			if (target == null) {
				continue;
			}
			keep.add(a.id());
			ClientAgentEntity e = entities.get(a.id());
			if (e == null || e.isRemoved() || e.level() != lvl) {
				e = spawn(lvl, a, target);
				entities.put(a.id(), e);
			} else if (!e.getSkin().equals(AgentSkins.get(a.id(), a.skin()))) {
				e.setSkin(AgentSkins.get(a.id(), a.skin()));
			}
			AgentView v = e.view();
			v.update(a, stale);
			v.station = StationAssigner.stationKey(a);
			v.anchor = target.name();
			if (relayout) {
				place(e, target);
			} else if (!stale) {
				retarget(lvl, layout, e, target);
			}
		}
		for (var it = entities.entrySet().iterator(); it.hasNext();) {
			var en = it.next();
			if (!keep.contains(en.getKey())) {
				remove(lvl, en.getValue());
				it.remove();
			}
		}
	}

	private ClientAgentEntity spawn(ClientLevel lvl, Agent a, Anchor target) {
		ClientAgentEntity e = new ClientAgentEntity(lvl, a.id(), AgentSkins.get(a.id(), a.skin()));
		// Negative ids never collide with server-assigned entity ids.
		e.setId(nextEntityId--);
		place(e, target);
		lvl.addEntity(e);
		AgentCraft.LOGGER.info("Agent {} appeared at {}", a.id(), target.name());
		return e;
	}

	private static void place(ClientAgentEntity e, Anchor target) {
		Vec3 p = e.motion().placeAt(target);
		e.snapTo(p, target.yaw());
	}

	private void retarget(ClientLevel lvl, Anchors.Layout layout, ClientAgentEntity e, Anchor target) {
		Anchor current = e.motion().target();
		if (current != null && current.name().equals(target.name()) && current.pos().distanceToSqr(target.pos()) < 1e-4) {
			return;
		}
		List<Vec3> route = new GridPathfinder(lvl, layout.bounds()).find(e.position(), target.pos());
		if (route == null || length(route) > MAX_WALK) {
			pathFailures++;
			AgentCraft.LOGGER.info("Agent {}: no walkable route to {} ({}), teleporting", e.agentId(), target.name(),
				route == null ? "no path" : "too far");
			place(e, target);
			return;
		}
		e.motion().walkTo(target, route);
	}

	private static double length(List<Vec3> route) {
		double d = 0;
		for (int i = 1; i < route.size(); i++) {
			d += route.get(i).distanceTo(route.get(i - 1));
		}
		return d;
	}

	/** Snap every agent to its target now (QA: no one mid-walk in a screenshot). */
	public int settle() {
		int n = 0;
		for (ClientAgentEntity e : entities.values()) {
			Anchor t = e.motion().target();
			if (t != null && e.motion().walking()) {
				place(e, t);
				n++;
			}
		}
		return n;
	}

	private void removeAll() {
		if (level != null) {
			for (ClientAgentEntity e : entities.values()) {
				remove(level, e);
			}
		}
		entities.clear();
	}

	private static void remove(ClientLevel lvl, ClientAgentEntity e) {
		lvl.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
	}

	/** No layout yet: a row in front of the world spawn, facing it. */
	private static Map<String, Anchor> fallbackTargets(List<Agent> agents, ClientLevel lvl) {
		Map<String, Anchor> out = new LinkedHashMap<>();
		BlockPos spawn = lvl.getRespawnData().pos();
		int n = agents.size();
		for (int i = 0; i < n; i++) {
			double x = spawn.getX() + 0.5 + (i - (n - 1) / 2.0) * 1.4;
			out.put(agents.get(i).id(), new Anchor(AnchorNames.LOUNGE + "@spawn" + i, x, spawn.getY(), spawn.getZ() + 4.5, 180, 0));
		}
		return out;
	}
}
