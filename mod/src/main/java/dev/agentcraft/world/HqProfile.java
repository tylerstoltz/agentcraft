package dev.agentcraft.world;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * How an HQ world plays. Chosen when the world first starts (from its game mode and hardcore flag),
 * saved in {@link WorldMarker} and applied on every start; {@code /agentcraft mode} switches between
 * studio and survival later (hardcore is fixed for the life of the world, as in vanilla).
 *
 * <ul>
 *   <li>{@link #STUDIO}: the calm workspace: creative, no mobs, frozen golden-hour time, clear
 *       weather, keep inventory;</li>
 *   <li>{@link #SURVIVAL} / {@link #HARDCORE}: vanilla play around the HQ. Only the rules that
 *       protect the build stay (no mob griefing, no fire spread, no vine/snow growth).</li>
 * </ul>
 */
public enum HqProfile {
	STUDIO, SURVIVAL, HARDCORE;

	public String wire() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static @Nullable HqProfile parse(@Nullable String s) {
		if (s == null) {
			return null;
		}
		for (HqProfile p : values()) {
			if (p.wire().equalsIgnoreCase(s.trim())) {
				return p;
			}
		}
		return null;
	}

	public boolean vanillaPlay() {
		return this != STUDIO;
	}
}
