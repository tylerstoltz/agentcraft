package dev.agentcraft.client.hud;

import dev.agentcraft.AgentCraft;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

/**
 * HUD feature: the Foreman connection/backend banner (Phase 2, {@link ConnectionBanner}). Phase 3
 * adds the boss-bar goal progress, in-game toasts for {@code notify} and decision prompts here.
 * Register HUD elements with {@code HudElementRegistry} using ids under {@code agentcraft:hud/...}.
 */
public final class HudFeature {
	private HudFeature() {
	}

	public static void init() {
		HudElementRegistry.addLast(AgentCraft.id("hud/connection"), new ConnectionBanner());
	}
}
