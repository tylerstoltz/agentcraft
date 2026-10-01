package dev.agentcraft;

import dev.agentcraft.world.HqWorld;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (both sides) entrypoint. Server-side game logic (entities, blocks, HQ builder, Foreman
 * link) registers from here in later phases.
 */
public class AgentCraft implements ModInitializer {
	public static final String MOD_ID = "agentcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		HqWorld.init();
		LOGGER.info("AgentCraft common init done");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
