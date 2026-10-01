package dev.agentcraft.client.diff;

import dev.agentcraft.block.entity.ModBlockEntities;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/**
 * Diff / merge review (Phase 3 owner: diff specialist). For a merge decision ({@code kind == MERGE},
 * with {@code repoId} + {@code worktree}) fetch {@code Foreman.requestDiff(repoId, worktree)} and show
 * a scrollable, syntax-tinted multi-file diff (ui-style "paper" add/del tokens), then answer with
 * {@code Merge} / {@code Request changes} (+ feedback text) / {@code Reject}. Open from the merge
 * station ({@code StationInteractions.onUse(ModBlocks.MERGE_STATION, ...)}) and register as
 * {@code DevBridge.registerScreen("diff", ...)} (QA: sim showcase d3 = merge of wren-t4).
 */
public final class DiffFeature {
	private DiffFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.MERGE_STATION, ctx -> new MergeStationRenderer());
	}
}
