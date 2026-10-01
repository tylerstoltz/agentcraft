package dev.agentcraft.client.agents;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.jspecify.annotations.Nullable;

/**
 * Render state of an agent: the vanilla avatar state (model, skin, walk cycle) plus AgentCraft's
 * extras. {@link #renderer} routes the submit back to {@link AgentRenderer} (vanilla would send any
 * AvatarRenderState to the player renderer; see EntityRenderDispatcherMixin). Phase 3 hooks may add
 * fields here.
 */
public class AgentRenderState extends AvatarRenderState {
	public @Nullable AgentRenderer renderer;
	public String agentId = "";
	public AgentPose pose = AgentPose.STAND;
	/** The nameplate to draw this frame (full or compact, chosen by {@link PlateLayout}), or null (too far). */
	public Nameplate.@Nullable Data plate;
	/** Both variants (set in extract; {@link PlateLayout} picks {@link #plate} from them). */
	public Nameplate.@Nullable Data plateFull;
	public Nameplate.@Nullable Data plateCompact;
	/** Declutter lift above the natural spot, plate px (smoothed). */
	public float plateLift;
	/** World size factor of the plate ({@link Nameplate#distanceScale}). */
	public float plateScale = 1f;
	/** Depth nudge (fraction of the camera distance) that orders overlapping plates. */
	public float plateNudge;
	/** {@link AgentView#plateWeight()} at extract time. */
	public int plateWeight;
	/** The crosshair is on this agent (its plate is shown in full). */
	public boolean plateCrosshair;
	/** Seconds since the client started (for pulses), partial-tick accurate. */
	public float timeSeconds;
}
