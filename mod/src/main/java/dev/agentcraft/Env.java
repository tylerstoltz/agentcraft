package dev.agentcraft;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Launch settings, read on either side: a JVM property ({@code AGENTCRAFT_HQ_SITE} ->
 * {@code -Dagentcraft.hq.site=...}) wins over the environment variable of the same name.
 */
public final class Env {
	private Env() {
	}

	/** Trimmed value, or null when unset or blank. */
	public static @Nullable String raw(String envName) {
		String prop = System.getProperty(envName.toLowerCase(Locale.ROOT).replace('_', '.'));
		if (prop != null && !prop.isBlank()) {
			return prop.trim();
		}
		String env = System.getenv(envName);
		return env == null || env.isBlank() ? null : env.trim();
	}

	public static String str(String envName, String def) {
		String v = raw(envName);
		return v == null ? def : v;
	}

	public static boolean flag(String envName, boolean def) {
		String v = raw(envName);
		if (v == null) {
			return def;
		}
		return switch (v.toLowerCase(Locale.ROOT)) {
			case "1", "true", "yes", "on" -> true;
			case "0", "false", "no", "off" -> false;
			default -> def;
		};
	}

	public static int intValue(String envName, int def) {
		String v = raw(envName);
		if (v == null) {
			return def;
		}
		try {
			return Integer.parseInt(v);
		} catch (NumberFormatException e) {
			return def;
		}
	}
}
