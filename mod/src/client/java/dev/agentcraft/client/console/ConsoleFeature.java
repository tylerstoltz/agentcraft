package dev.agentcraft.client.console;

import dev.agentcraft.block.entity.ModBlockEntities;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/**
 * Command console (Phase 3 owner: console specialist). One input line with prefixes (docs/protocol.md
 * "Console mapping"): plain text -> {@code Foreman.submitGoal}, {@code @name msg} -> {@code Foreman.message},
 * {@code /answer}, {@code /repo add}, {@code /pause @name}, ... with agent-name autocomplete from
 * {@code Foreman.state().agents()}. Keybind {@code `} (register a {@code KeyMapping} with
 * {@code KeyMappingHelper} in this init) and Enter on a console terminal
 * ({@code StationInteractions.onUse(ModBlocks.CONSOLE_TERMINAL, ...)}). Register the screen as
 * {@code DevBridge.registerScreen("console", ...)} for QA.
 */
public final class ConsoleFeature {
	private ConsoleFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.CONSOLE_TERMINAL, ctx -> new ConsoleTerminalRenderer());
	}
}
