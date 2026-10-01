package dev.agentcraft.hq;

import dev.agentcraft.layout.Anchors;
import net.minecraft.server.level.ServerLevel;

/**
 * Builds an HQ into the world and describes it with anchors. Registered in {@link HqBuilders};
 * {@code /agentcraft hq [id]} runs one and publishes its anchors ({@link Anchors#publish}).
 *
 * <p>Contract: write every required anchor from {@link dev.agentcraft.layout.AnchorNames}
 * (desk_/monitor_ per cast agent, the shared stations with enough slots, task_wall,
 * decision_podium, goal_atrium, entrance, spawn) and set {@link Anchors.Builder#bounds} to the
 * walkable HQ region. Bind station block entities ({@code StationBlockEntity#setBinding}) as you
 * place them. Must be deterministic and idempotent (running it twice gives the same world).
 */
public interface HqBuilder {
	String id();

	String description();

	/** Runs on the server thread. */
	void build(ServerLevel level, Anchors.Builder anchors);
}
