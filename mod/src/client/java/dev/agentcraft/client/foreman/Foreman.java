package dev.agentcraft.client.foreman;

import com.google.gson.JsonObject;
import dev.agentcraft.foreman.ForemanJson;
import dev.agentcraft.foreman.ForemanLink;
import dev.agentcraft.foreman.ForemanListener;
import dev.agentcraft.foreman.ForemanState;
import dev.agentcraft.foreman.Protocol.Ack;
import dev.agentcraft.foreman.Protocol.Diff;
import dev.agentcraft.foreman.Protocol.FsListing;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Static entry point to the Foreman for every client feature.
 *
 * <pre>
 * Foreman.state().agents()                      // read the model (client thread)
 * Foreman.addListener(new ForemanListener() {...}) // change callbacks (client thread)
 * Foreman.submitGoal("Add OAuth", null).thenAccept(ack -> ...)  // intents; futures complete on the client thread
 * Foreman.requestDiff(repoId, worktree).thenAccept(diff -> ...)
 * </pre>
 *
 * Intents fail fast (exceptionally) while the link is not synced; an {@link Ack} with
 * {@code ok=false} carries the Foreman's error text.
 */
public final class Foreman {
	private static ForemanState state;
	private static ForemanLink link;

	private Foreman() {
	}

	static void install(ForemanState s, ForemanLink l) {
		state = s;
		link = l;
	}

	public static ForemanState state() {
		return state;
	}

	public static ForemanLink link() {
		return link;
	}

	public static void addListener(ForemanListener l) {
		state.addListener(l);
	}

	public static boolean connected() {
		return link != null && link.status().synced();
	}

	/** Send any client message (type + payload); see docs/protocol.md "Mod -> Foreman". */
	public static CompletableFuture<Ack> send(String type, JsonObject payload) {
		JsonObject m = payload.deepCopy();
		m.addProperty("type", type);
		return link.send(m);
	}

	/** New goal for the lead (console: plain text). {@code repoId} null = the Foreman's default repo. */
	public static CompletableFuture<Ack> submitGoal(String text, @Nullable String repoId) {
		return link.send(ForemanJson.msg("goal.submit").put("text", text).put("repoId", repoId).json());
	}

	/** Message an agent ({@code to} = agent id) or everyone ({@code "all"}; a leading "@name" routes it). */
	public static CompletableFuture<Ack> message(String to, String text) {
		return link.send(ForemanJson.msg("user.message").put("to", to).put("text", text).json());
	}

	/** Answer a decision with an option label (preferred) and/or free text. */
	public static CompletableFuture<Ack> answer(String decisionId, @Nullable String option, @Nullable String text) {
		return link.send(ForemanJson.msg("decision.answer").put("decisionId", decisionId).put("option", option).put("text", text).json());
	}

	/** {@code action}: reassign | cancel | retry | prioritize; {@code arg}: agent id / priority. */
	public static CompletableFuture<Ack> taskAction(String taskId, String action, @Nullable String arg) {
		return link.send(ForemanJson.msg("task.action").put("taskId", taskId).put("action", action).put("arg", arg).json());
	}

	/** {@code action}: pause | resume | stop | spawn; {@code arg}: spawn task id. */
	public static CompletableFuture<Ack> agentAction(String agentId, String action, @Nullable String arg) {
		return link.send(ForemanJson.msg("agent.action").put("agentId", agentId).put("action", action).put("arg", arg).json());
	}

	public static CompletableFuture<Ack> addRepo(String path) {
		return addRepo(path, false);
	}

	/** {@code init}: a folder that is not a repository root is {@code git init}ed and committed as it is first. */
	public static CompletableFuture<Ack> addRepo(String path, boolean init) {
		return link.send(ForemanJson.msg("repo.add").put("path", path).put("init", init ? true : null).json());
	}

	/** Unregister a repo (nothing on disk changes); refused while agents have active worktrees in it. */
	public static CompletableFuture<Ack> removeRepo(String repoId) {
		return link.send(ForemanJson.msg("repo.remove").put("repoId", repoId).json());
	}

	/**
	 * The sub-folders of a folder on the Foreman's machine ({@code path} null = its home folder), for the
	 * repo folder picker. Fails with the Foreman's error text when it refuses (missing folder, no trust).
	 */
	public static CompletableFuture<FsListing> listDir(@Nullable String path, boolean hidden) {
		return link.send(ForemanJson.msg("fs.list").put("path", path).put("hidden", hidden ? true : null).json()).thenApply(ack -> {
			if (!ack.ok() || ack.result() == null) {
				throw new IllegalStateException(ack.error() != null ? ack.error() : "the Foreman refused it");
			}
			return ForemanJson.read(ack.result(), FsListing.class);
		});
	}

	/** Structured diff of a worktree (or an agent id: its current worktree) vs its base. */
	public static CompletableFuture<Diff> requestDiff(String repoId, String worktree) {
		return link.requestDiff(repoId, worktree);
	}
}
