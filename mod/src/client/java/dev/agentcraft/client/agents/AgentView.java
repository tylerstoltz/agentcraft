package dev.agentcraft.client.agents;

import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.AgentState;
import dev.agentcraft.client.ui.UiStyle;
import org.jspecify.annotations.Nullable;

/**
 * What an agent entity shows, derived from its protocol {@link Agent} by {@link AgentManager} every
 * tick (client thread). Renderers and Phase 3 hooks read it; only the manager writes it.
 */
public final class AgentView {
	public final String id;
	public String name;
	/** Identity colour (scarf/badge), ARGB. */
	public int color;
	/** Name colour on dark surfaces (nameplate), ARGB. */
	public int nameColor;
	public AgentState state = AgentState.IDLE;
	/** Status family for dots/lamps: idle thinking working waiting error done. */
	public String family = "idle";
	public String activity = "";
	/** The station key the agent is at or walking to ("desk", "library", ..., "lounge"). */
	public String station = "lounge";
	public @Nullable String anchor;
	public boolean active = true;
	public boolean paused;
	/** The Foreman link is down: show the last known state, dimmed. */
	public boolean stale;
	public AgentPose pose = AgentPose.STAND;
	public @Nullable String taskId;
	/**
	 * An open decision is waiting on Blendi for this agent's work (a merge of its task, a question it
	 * asked, a permission prompt), even when the Foreman shows the agent as idle meanwhile.
	 */
	public boolean awaitingUser;
	/** The decision behind {@link #awaitingUser} (for the agent card), or null. */
	public @Nullable String awaitingDecision;
	public String role = "";
	public @Nullable String title;
	/** Laid-out nameplates, rebuilt by {@link Nameplate#of} / {@link Nameplate#compactOf} only when their text changes. */
	Nameplate.@Nullable Data plateCache;
	Nameplate.@Nullable Data compactCache;

	public AgentView(String id) {
		this.id = id;
		this.name = id;
	}

	void update(Agent a, boolean staleLink, @Nullable String awaitingDecisionId) {
		name = a.name();
		color = 0xFF000000 | dev.agentcraft.Cast.parseColor(a.color(), 0x9C9488);
		nameColor = UiStyle.agentOnDark(a.id());
		state = a.state();
		awaitingDecision = awaitingDecisionId;
		awaitingUser = awaitingDecisionId != null;
		family = statusFamily(a.state(), awaitingUser);
		role = a.role().wire();
		title = a.title();
		activity = a.activity();
		active = a.isActive();
		paused = a.isPaused();
		stale = staleLink;
		taskId = a.taskId();
	}

	/**
	 * The status family shown for an agent (dot, lamp colour, "!" marker). The Foreman reports a
	 * worker whose finished task waits for Blendi's merge as {@code idle} ("t4 awaiting your merge");
	 * that is the user's turn, so it shows as {@code waiting} (clay), like {@code waiting_user}.
	 * Errors and real work keep their own family.
	 */
	public static String statusFamily(AgentState state, boolean awaitingUser) {
		String f = state.family();
		if (awaitingUser && (f.equals("idle") || f.equals("done"))) {
			return "waiting";
		}
		return f;
	}

	/** One line for the nameplate under the name. */
	public String activityLine() {
		if (stale) {
			return "Foreman offline";
		}
		if (!active) {
			return "off shift";
		}
		if (paused) {
			return activity.isEmpty() ? "paused" : "paused · " + activity;
		}
		return activity;
	}

	/** Dot family shown on the nameplate (idle when stale or off shift). */
	public String dotFamily() {
		return stale || !active ? "idle" : family;
	}

	/**
	 * How much the plate matters when plates compete for screen space (higher = placed first, keeps
	 * its activity line): waiting 6, error 5, working/thinking 4, done 2, idle 1, off shift or
	 * Foreman offline 0. Plates at 2 or below collapse to the name when they would overlap another.
	 */
	public int plateWeight() {
		if (stale || !active) {
			return 0;
		}
		return switch (family) {
			case "waiting" -> 6;
			case "error" -> 5;
			case "working", "thinking" -> 4;
			case "done" -> 2;
			default -> 1;
		};
	}
}
