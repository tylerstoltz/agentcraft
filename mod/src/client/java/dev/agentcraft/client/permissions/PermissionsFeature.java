package dev.agentcraft.client.permissions;

/**
 * Permission prompts (Phase 3 owner: permissions specialist). Open decisions with
 * {@code kind == PERMISSION} (tool, context with the cwd and the "Always allow for this agent"
 * scope line) need a clear, fast in-world UX: {@code Allow once} / {@code Always allow for this agent}
 * / {@code Deny} (exact labels in {@code Protocol}), answered with {@code Foreman.answer}. Listen with
 * {@code Foreman.addListener(new ForemanListener() { onDecision(...) })}; the HUD feature shows toasts.
 */
public final class PermissionsFeature {
	private PermissionsFeature() {
	}

	public static void init() {
		// Phase 3
	}
}
