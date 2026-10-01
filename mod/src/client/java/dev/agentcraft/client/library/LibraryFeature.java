package dev.agentcraft.client.library;

import dev.agentcraft.block.entity.ModBlockEntities;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/**
 * Memory library (Phase 3 owner: library specialist). Browse {@code Foreman.state().memory()} (shared
 * + per agent scopes; markdown bodies) in a paper screen; open it from a memory archive
 * ({@code StationInteractions.onUse(ModBlocks.MEMORY_ARCHIVE, ...)}, binding = scope filter) and
 * register it as {@code DevBridge.registerScreen("library", ...)} for QA.
 */
public final class LibraryFeature {
	private LibraryFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.MEMORY_ARCHIVE, ctx -> new MemoryArchiveRenderer());
	}
}
