package dev.agentcraft.client.agents;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.AgentCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.player.PlayerModelType;
import org.jspecify.annotations.Nullable;

/**
 * Draws an agent with the vanilla player model (both skin layers, slim or wide arms), plus the
 * AgentCraft nameplate and Phase 3 hook geometry. One instance per arm model; the dispatcher mixin
 * picks the right one per entity and routes submits back here.
 */
public class AgentRenderer extends AvatarRenderer<ClientAgentEntity> {
	private static @Nullable AgentRenderer wide;
	private static @Nullable AgentRenderer slim;
	/** Nameplates are drawn within this distance (blocks). */
	public static final double PLATE_DISTANCE = 40;

	public AgentRenderer(EntityRendererProvider.Context context, boolean slimArms) {
		super(context, slimArms);
	}

	/** Registered for the agent entity type: builds both variants on every resource reload. */
	public static AgentRenderer provide(EntityRendererProvider.Context context) {
		slim = new AgentRenderer(context, true);
		wide = new AgentRenderer(context, false);
		return wide;
	}

	public static @Nullable AgentRenderer forModel(PlayerModelType model) {
		return model == PlayerModelType.SLIM ? slim : wide;
	}

	@Override
	public AvatarRenderState createRenderState() {
		return new AgentRenderState();
	}

	@Override
	public void extractRenderState(ClientAgentEntity entity, AvatarRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		if (!(state instanceof AgentRenderState s)) {
			return;
		}
		s.renderer = this;
		AgentView v = entity.view();
		s.agentId = v.id;
		s.pose = v.pose;
		s.timeSeconds = (entity.tickCount + partialTicks) / 20f;
		if (v.pose == AgentPose.SIT) {
			s.isPassenger = true;
		} else if (v.pose == AgentPose.LEAN) {
			s.isCrouching = true;
		}
		// Nameplates are world information (like the Task Wall), so they stay visible with the HUD hidden (F1, screenshots).
		// PlateLayout (end of level extraction) picks full/compact, lift and depth nudge for this frame.
		if (s.distanceToCameraSq < PLATE_DISTANCE * PLATE_DISTANCE) {
			s.plateFull = Nameplate.of(v);
			s.plateCompact = Nameplate.compactOf(v);
			s.plate = s.plateFull;
			s.plateScale = Nameplate.distanceScale(Math.sqrt(s.distanceToCameraSq));
		} else {
			s.plate = null;
			s.plateFull = null;
			s.plateCompact = null;
			s.plateScale = 1f;
		}
		s.plateLift = 0f;
		s.plateNudge = 0f;
		s.plateWeight = v.plateWeight();
		s.plateCrosshair = entity == Minecraft.getInstance().crosshairPickEntity;
		for (AgentHooks.Extractor e : AgentHooks.EXTRACTORS) {
			try {
				e.extract(entity, s, partialTicks);
			} catch (Throwable t) {
				AgentCraft.LOGGER.warn("agent extractor failed", t);
			}
		}
	}

	@Override
	protected void submitNameDisplay(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (!(state instanceof AgentRenderState s)) {
			return;
		}
		if (s.plate != null) {
			Nameplate.submit(s, s.plate, poseStack, collector, camera);
		}
		for (AgentHooks.Submitter h : AgentHooks.SUBMITTERS) {
			try {
				h.submit(s, poseStack, collector, camera);
			} catch (Throwable t) {
				AgentCraft.LOGGER.warn("agent submitter failed", t);
			}
		}
	}
}
