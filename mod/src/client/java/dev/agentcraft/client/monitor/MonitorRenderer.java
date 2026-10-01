package dev.agentcraft.client.monitor;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.block.MonitorBlock;
import dev.agentcraft.block.entity.MonitorBlockEntity;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.client.ui.WorldUi;
import dev.agentcraft.client.world.StationRenderState;
import dev.agentcraft.client.world.StationRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * Monitor screen BER. Phase 2 placeholder: the bound agent's name in the top-left corner of the
 * screen (proves the screen-space transform); Phase 3 draws the live log here.
 */
public class MonitorRenderer extends StationRenderer<MonitorBlockEntity, MonitorRenderer.State> {
	/** Screen plane of the north-facing model (assets-src block contract), minus a hair to sit on top. */
	public static final float SCREEN_DEPTH = 12f / 16f - 0.002f;
	/** Pixel density on the screen (ui-style metrics: desk monitors 96 px per block). */
	public static final float PX_PER_BLOCK = 96f;

	public static class State extends StationRenderState {
		public boolean lit;
		public String agentName = "";
		public int nameColor;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected void extractStation(MonitorBlockEntity be, State s, float partialTicks) {
		s.lit = be.getBlockState().getValue(MonitorBlock.LIT);
		Agent a = Foreman.state() == null || s.binding.isEmpty() ? null : Foreman.state().agent(s.binding);
		s.agentName = a != null ? a.name() : s.binding;
		s.nameColor = UiStyle.agentOnLight(s.binding);
	}

	@Override
	public void submit(State s, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (!s.panelOrigin || !s.lit || s.agentName.isEmpty()) {
			return;
		}
		poseStack.pushPose();
		toFace(poseStack, s.facing, SCREEN_DEPTH, PX_PER_BLOCK);
		poseStack.translate(0, -(s.panelHeight - 1) * PX_PER_BLOCK, 0);
		// inside the 2 px bezel (= 12 screen px at 96 px/block) plus the ui-style padding
		WorldUi.submitText(poseStack, collector, s.agentName, 12 + UiStyle.metric("metrics.monitor_pad_left", 6), 12 + UiStyle.metric(
			"metrics.monitor_pad_top", 5), s.nameColor, WorldUi.uiLight());
		poseStack.popPose();
	}
}
