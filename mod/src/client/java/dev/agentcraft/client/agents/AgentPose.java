package dev.agentcraft.client.agents;

/**
 * Body pose of an agent (Phase 2 basics; Phase 3 deepens animation). The renderer maps SIT to the
 * vanilla riding pose and LEAN to a slight crouch; everything else uses the standing model with the
 * vanilla walk cycle (driven by the real movement speed).
 */
public enum AgentPose {
	STAND, WALK, SIT, LEAN
}
