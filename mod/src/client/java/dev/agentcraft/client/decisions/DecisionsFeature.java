package dev.agentcraft.client.decisions;

import dev.agentcraft.block.entity.ModBlockEntities;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/**
 * Decision Podium + decision GUI (Phase 3 owner: decisions specialist). Open decisions:
 * {@code Foreman.state().openDecisions()}; answer with {@code Foreman.answer(id, option, text)}
 * (exact option labels in {@code Protocol}). The podium block's {@code open} state should follow
 * "any question decision open" (set it through {@code ServerTasks}). Register the GUI as
 * {@code DevBridge.registerScreen("decision", mc -> new DecisionScreen(...))} so QA can shoot it,
 * and open it from the podium with {@code StationInteractions.onUse(ModBlocks.DECISION_PODIUM, ...)}.
 * Merge decisions are reviewed in the diff feature; permission decisions in the permissions feature.
 */
public final class DecisionsFeature {
	private DecisionsFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.DECISION_PODIUM, ctx -> new DecisionPodiumRenderer());
	}
}
