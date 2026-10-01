package dev.agentcraft.client.hq;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.block.entity.StatusLampBlockEntity;
import dev.agentcraft.client.world.StationRenderState;
import dev.agentcraft.client.world.StationRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Status lamp BER (Phase 3: waiting pulse / particles; the lamp colour itself is the blockstate, driven via ServerTasks). */
public class StatusLampRenderer extends StationRenderer<StatusLampBlockEntity, StatusLampRenderer.State> {
	public static class State extends StationRenderState {
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected void extractStation(StatusLampBlockEntity be, State s, float partialTicks) {
		// Phase 3: copy what submit needs from Foreman.state() into State here (client thread).
	}

	@Override
	public void submit(State s, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		// Phase 3: draw it. Use StationRenderer.toFace(...) + WorldUi / font text in pixel space.
	}
}
