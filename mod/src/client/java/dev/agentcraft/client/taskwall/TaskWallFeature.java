package dev.agentcraft.client.taskwall;

import dev.agentcraft.block.entity.ModBlockEntities;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/**
 * Task Wall (Phase 3 owner: task wall specialist). Kanban of {@code Foreman.state().tasks()} on the
 * connected task_board panel: columns todo / doing / review / done / blocked ({@code cancelled}
 * hidden), kit card sprites per status, assignee portrait, CI badge. Anchor {@code task_wall}.
 * Optional: right-click a card -> task actions ({@code Foreman.taskAction}) via
 * {@code StationInteractions.onUse(ModBlocks.TASK_BOARD, ...)}.
 */
public final class TaskWallFeature {
	private TaskWallFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.TASK_BOARD, ctx -> new TaskBoardRenderer());
	}
}
