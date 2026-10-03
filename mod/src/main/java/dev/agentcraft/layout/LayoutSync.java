package dev.agentcraft.layout;

import dev.agentcraft.AgentCraft;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Sends the published {@link Anchors} layout to modded players, so clients of a dedicated server know
 * the HQ's named positions (desks, seats, podium, ...) just like a singleplayer client that shares the
 * server's JVM. Sent on join and whenever a new layout is published.
 */
public final class LayoutSync {
	/** Anchors JSON can be large for a big HQ; clientbound payloads allow up to 1 MiB. */
	private static final int MAX_CHARS = 300_000;

	public record Payload(String json) implements CustomPacketPayload {
		public static final Type<Payload> TYPE = new Type<>(AgentCraft.id("layout"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC =
			StreamCodec.composite(ByteBufCodecs.stringUtf8(MAX_CHARS), Payload::json, Payload::new);

		@Override
		public Type<Payload> type() {
			return TYPE;
		}
	}

	private static volatile MinecraftServer server;

	private LayoutSync() {
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> send(handler.getPlayer(), Anchors.current()));
		// Publishing happens on the server thread; a listener also fires on a remote client's applyRemote, where server is null.
		Anchors.addListener(layout -> {
			MinecraftServer s = server;
			if (s != null) {
				for (ServerPlayer p : s.getPlayerList().getPlayers()) {
					send(p, layout);
				}
			}
		});
	}

	private static void send(ServerPlayer player, Anchors.Layout layout) {
		if (!ServerPlayNetworking.canSend(player, Payload.TYPE)) {
			return;
		}
		String json = Anchors.toJson(layout).toString();
		if (json.length() > MAX_CHARS) {
			AgentCraft.LOGGER.warn("Layout '{}' is too large to sync ({} chars)", layout.name(), json.length());
			return;
		}
		ServerPlayNetworking.send(player, new Payload(json));
	}
}
