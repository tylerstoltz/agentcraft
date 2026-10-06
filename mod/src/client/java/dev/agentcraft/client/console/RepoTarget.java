package dev.agentcraft.client.console;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.foreman.ForemanState;
import dev.agentcraft.foreman.Protocol.Repo;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * The repo new goals go to: chosen explicitly (repo manager, {@code /repo use}) and sticky until it is
 * changed, also across game restarts ({@code config/agentcraft-console.properties}). With a single repo
 * that one is used; with several and none chosen (or the chosen one was removed) nothing is guessed:
 * the console asks for a pick before a goal can be sent.
 */
public final class RepoTarget {
	private static final String FILE = "agentcraft-console.properties";
	private static final String KEY = "goalRepo";
	private static boolean loaded;
	private static @Nullable String selected;

	private RepoTarget() {
	}

	/** The chosen repo id, as stored (it may no longer exist). */
	public static @Nullable String selected() {
		load();
		return selected;
	}

	/** Where a new goal goes now, or null when there is no repo or several and none chosen. */
	public static @Nullable Repo resolve(@Nullable ForemanState s) {
		if (s == null || s.repos().isEmpty()) {
			return null;
		}
		String id = selected();
		Repo r = id != null ? s.repo(id) : null;
		if (r != null) {
			return r;
		}
		return s.repos().size() == 1 ? s.repos().values().iterator().next() : null;
	}

	public static void select(String repoId) {
		load();
		if (repoId.equals(selected)) {
			return;
		}
		selected = repoId;
		save();
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE);
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path f = file();
			if (Files.isRegularFile(f)) {
				Properties p = new Properties();
				try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
					p.load(r);
				}
				String v = p.getProperty(KEY);
				selected = v == null || v.isBlank() ? null : v.strip();
			}
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.warn("could not read {}: {}", FILE, e.toString());
		}
	}

	private static void save() {
		try {
			Properties p = new Properties();
			if (selected != null) {
				p.setProperty(KEY, selected);
			}
			try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
				p.store(w, "AgentCraft console: the repo new goals go to");
			}
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.warn("could not write {}: {}", FILE, e.toString());
		}
	}
}
