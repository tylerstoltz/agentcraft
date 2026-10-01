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
	/** Laid-out nameplates, rebuilt by {@link Nameplate#of} / {@link Nameplate#compactOf} only when their text changes. */
	Nameplate.@Nullable Data plateCache;
	Nameplate.@Nullable Data compactCache;

	public AgentView(String id) {
		this.id = id;
		this.name = id;
	}

	void update(Agent a, boolean staleLink) {
		name = a.name();
		color = 0xFF000000 | dev.agentcraft.Cast.parseColor(a.color(), 0x9C9488);
		nameColor = UiStyle.agentOnDark(a.id());
		state = a.state();
		family = a.state().family();
		activity = a.activity();
		active = a.isActive();
		paused = a.isPaused();
		stale = staleLink;
		taskId = a.taskId();
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
