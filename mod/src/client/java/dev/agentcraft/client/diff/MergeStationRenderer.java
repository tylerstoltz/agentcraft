package dev.agentcraft.client.diff;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.block.entity.MergeStationBlockEntity;
import dev.agentcraft.client.world.StationRenderState;
import dev.agentcraft.client.world.StationRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Merge Station BER (Phase 3: diff stat / branch card for the waiting merge decision). */
public class MergeStationRenderer extends StationRenderer<MergeStationBlockEntity, MergeStationRenderer.State> {
	public static class State extends StationRenderState {
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected void extractStation(MergeStationBlockEntity be, State s, float partialTicks) {
		// Phase 3: copy what submit needs from Foreman.state() into State here (client thread).
	}

	@Override
	public void submit(State s, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		// Phase 3: draw it. Use StationRenderer.toFace(...) + WorldUi / font text in pixel space.
	}
}
