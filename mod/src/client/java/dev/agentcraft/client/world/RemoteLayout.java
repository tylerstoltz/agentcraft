package dev.agentcraft.client.world;

import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.layout.LayoutSync;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * On a remote server, takes the HQ layout from {@link LayoutSync} so every client feature reading
 * {@link Anchors#current()} works as in singleplayer, and clears it on leaving. In singleplayer the
 * integrated server owns {@link Anchors} (same JVM) and the payload is ignored.
 */
public final class RemoteLayout {
	private static boolean applied;

	private RemoteLayout() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(LayoutSync.Payload.TYPE, (p, ctx) -> {
			if (ctx.client().hasSingleplayerServer()) {
				return;
			}
			try {
				Anchors.Layout layout = Anchors.fromJson(JsonParser.parseString(p.json()).getAsJsonObject());
				Anchors.applyRemote(layout);
				applied = true;
				AgentCraft.LOGGER.info("Layout '{}' rev {} from the server ({} anchors)", layout.name(), layout.revision(), layout.anchors().size());
			} catch (RuntimeException e) {
				AgentCraft.LOGGER.warn("Bad layout from the server", e);
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> {
			if (applied) {
				applied = false;
				Anchors.applyRemote(Anchors.Layout.EMPTY);
			}
		}));
	}
}
