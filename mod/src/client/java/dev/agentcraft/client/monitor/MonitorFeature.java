package dev.agentcraft.client.monitor;

import dev.agentcraft.block.entity.ModBlockEntities;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/**
 * Monitors (Phase 3 owner: monitor specialist). Each desk monitor streams its agent's live log
 * ({@code Foreman.state().logs(agentId)}, appended via {@code ForemanListener.onLog}) on the screen
 * plane, crisp and legible (assets-src/ui-style.md "monitor" tokens and metrics). Binding = agent id
 * (the test room binds {@code monitor_<id>} blocks to their desk owner).
 *
 * <p>Hooks: {@link MonitorRenderer} (BER, draws from the connected panel's origin block).
 */
public final class MonitorFeature {
	private MonitorFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.MONITOR, ctx -> new MonitorRenderer());
	}
}
