package dev.agentcraft.foreman;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.hq.HqWorldDriver;
import dev.agentcraft.world.HqWorld;
import java.net.URI;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * A dedicated server's own Foreman link and state model: the server, not a client, then drives the HQ
 * blocks ({@link HqWorldDriver}), so every player sees the same lamps, podium and stations, also with
 * no modded player online. Only on dedicated HQ servers; in singleplayer (and LAN) the host client
 * drives the integrated server as before.
 *
 * <pre>
 * AGENTCRAFT_PORT     Foreman port (default 7878), always 127.0.0.1
 * AGENTCRAFT_FOREMAN  0 disables the link
 * </pre>
 */
public final class ServerForeman {
	private static volatile @Nullable ForemanState state;
	private static volatile @Nullable ForemanLink link;

	private ServerForeman() {
	}

	/** The server's model, or null when not running (not dedicated, not an HQ world, or disabled). */
	public static @Nullable ForemanState state() {
		return state;
	}

	public static @Nullable ForemanLink link() {
		return link;
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(ServerForeman::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ForemanState st = state;
			if (st != null) {
				HqWorldDriver.tick(st, task -> task.accept(server.overworld()));
			}
		});
	}

	private static void start(MinecraftServer server) {
		if (!server.isDedicatedServer() || !HqWorld.isHq(server) || !envFlag("AGENTCRAFT_FOREMAN", true)) {
			return;
		}
		URI uri = URI.create("ws://127.0.0.1:" + envInt("AGENTCRAFT_PORT", 7878));
		String modVersion = FabricLoader.getInstance().getModContainer(AgentCraft.MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
		ForemanState st = new ForemanState(new LinkStatus(LinkStatus.Phase.WAITING_RETRY, uri.toString(), 0, null,
			System.currentTimeMillis(), System.currentTimeMillis(), false));
		ForemanLink l = new ForemanLink(uri, modVersion, st, server::execute, true);
		l.setClientName("server");
		state = st;
		link = l;
		l.start();
	}

	private static void stop() {
		ForemanLink l = link;
		link = null;
		state = null;
		if (l != null) {
			l.stop();
		}
	}

	static int envInt(String name, int def) {
		try {
			String v = System.getenv(name);
			return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	static boolean envFlag(String name, boolean def) {
		String v = System.getenv(name);
		if (v == null || v.isBlank()) {
			return def;
		}
		v = v.trim().toLowerCase(java.util.Locale.ROOT);
		return !(v.equals("0") || v.equals("false") || v.equals("off") || v.equals("no"));
	}
}
