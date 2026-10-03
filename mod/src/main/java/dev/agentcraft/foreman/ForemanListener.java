package dev.agentcraft.foreman;

import dev.agentcraft.foreman.Protocol.Agent;
import dev.agentcraft.foreman.Protocol.AgentSay;
import dev.agentcraft.foreman.Protocol.Decision;
import dev.agentcraft.foreman.Protocol.FeedItem;
import dev.agentcraft.foreman.Protocol.ForemanStatus;
import dev.agentcraft.foreman.Protocol.Goal;
import dev.agentcraft.foreman.Protocol.LogEntry;
import dev.agentcraft.foreman.Protocol.MemoryEntry;
import dev.agentcraft.foreman.Protocol.Notify;
import dev.agentcraft.foreman.Protocol.Repo;
import dev.agentcraft.foreman.Protocol.Task;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Change callbacks of {@link ForemanState}. Every method has a no-op default; implement what you
 * need. All callbacks run on the <b>client (render) thread</b>, after the state model was updated,
 * so reading {@link Foreman#state()} inside them sees the new state.
 *
 * <p>A {@code snapshot} (on every (re)connect) replaces the whole model and fires only
 * {@link #onSnapshot}: rebuild anything you derived from the state there. Upserts fire the
 * per-entity callbacks with the previous value ({@code null} when new).
 */
public interface ForemanListener {
	default void onConnection(LinkStatus status) {
	}

	default void onSnapshot(ForemanState state) {
	}

	default void onAgent(@Nullable Agent previous, Agent agent) {
	}

	default void onTask(@Nullable Task previous, Task task) {
	}

	default void onDecision(@Nullable Decision previous, Decision decision) {
	}

	default void onRepo(@Nullable Repo previous, Repo repo) {
	}

	default void onMemory(@Nullable MemoryEntry previous, MemoryEntry entry) {
	}

	default void onGoal(@Nullable Goal previous, Goal goal) {
	}

	default void onFeed(FeedItem item) {
	}

	default void onLog(String agentId, List<LogEntry> entries) {
	}

	default void onSay(AgentSay say) {
	}

	default void onNotify(Notify notify) {
	}

	default void onStatus(ForemanStatus status) {
	}

	/** Any change at all (after the specific callback). Cheap hook for "something to redraw". */
	default void onChange(long revision) {
	}
}
