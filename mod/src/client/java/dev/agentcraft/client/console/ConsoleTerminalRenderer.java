package dev.agentcraft.client.console;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.block.entity.ConsoleTerminalBlockEntity;
import dev.agentcraft.client.world.StationRenderState;
import dev.agentcraft.client.world.StationRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Console terminal BER (Phase 3: prompt / last command on the leaning screen). */
public class ConsoleTerminalRenderer extends StationRenderer<ConsoleTerminalBlockEntity, ConsoleTerminalRenderer.State> {
	public static class State extends StationRenderState {
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected void extractStation(ConsoleTerminalBlockEntity be, State s, float partialTicks) {
		// Phase 3: copy what submit needs from Foreman.state() into State here (client thread).
	}

	@Override
	public void submit(State s, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		// Phase 3: draw it. Use StationRenderer.toFace(...) + WorldUi / font text in pixel space.
	}
}
