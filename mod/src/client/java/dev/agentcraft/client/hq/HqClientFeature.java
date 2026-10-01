package dev.agentcraft.client.hq;

import dev.agentcraft.block.entity.ModBlockEntities;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/**
 * Client side of the HQ (Phase 3 owner: HQ specialist, who also owns {@code dev.agentcraft.hq} in
 * main). Drives world blocks from Foreman state through {@code ServerTasks}: status lamps by binding
 * ({@code agent:<id>} -> {@code LampStatus.forAgentState}, {@code ci:<repo>} -> {@code forCi}), the
 * podium {@code open}, merge station {@code active}, monitor {@code lit}; and the Goal Atrium
 * hologram. Anchors come from {@code Anchors.current()}.
 */
public final class HqClientFeature {
	private HqClientFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.STATUS_LAMP, ctx -> new StatusLampRenderer());
	}
}
