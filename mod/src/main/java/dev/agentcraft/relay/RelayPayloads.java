package dev.agentcraft.relay;

import dev.agentcraft.AgentCraft;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Wire format of the Foreman relay: a dedicated (or LAN) server pipes each modded player's Foreman
 * WebSocket through the game connection, so clients never need a Foreman of their own.
 *
 * <p>Both directions carry {@code (conn, kind, data)} frames. {@code conn} is the client's connection
 * generation (frames of an older one are dropped). A Foreman text message travels as zero or more
 * {@link #PART}s followed by one {@link #END}; each piece is at most {@link #CHUNK} chars so it fits
 * the vanilla serverbound payload limit (32 KiB) even as 3-byte UTF-8.
 *
 * <pre>
 * C2S: OPEN, PART*, END, PING, CLOSE
 * S2C: OPENED, PART*, END, PONG, CLOSED(data = reason)
 * </pre>
 */
public final class RelayPayloads {
	public static final byte OPEN = 0;
	public static final byte OPENED = 0;
	public static final byte PART = 1;
	public static final byte END = 2;
	public static final byte PING = 3;
	public static final byte PONG = 3;
	public static final byte CLOSE = 4;
	public static final byte CLOSED = 4;

	public static final int CHUNK = 8000;
	/** Largest reassembled message accepted from a client (Foreman intents are small). */
	public static final int MAX_C2S_CHARS = 256 * 1024;

	private RelayPayloads() {
	}

	public record C2S(int conn, byte kind, String data) implements CustomPacketPayload {
		public static final Type<C2S> TYPE = new Type<>(AgentCraft.id("relay_c2s"));
		public static final StreamCodec<RegistryFriendlyByteBuf, C2S> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, C2S::conn, ByteBufCodecs.BYTE, C2S::kind, ByteBufCodecs.stringUtf8(CHUNK), C2S::data, C2S::new);

		@Override
		public Type<C2S> type() {
			return TYPE;
		}
	}

	public record S2C(int conn, byte kind, String data) implements CustomPacketPayload {
		public static final Type<S2C> TYPE = new Type<>(AgentCraft.id("relay_s2c"));
		public static final StreamCodec<RegistryFriendlyByteBuf, S2C> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, S2C::conn, ByteBufCodecs.BYTE, S2C::kind, ByteBufCodecs.stringUtf8(CHUNK), S2C::data, S2C::new);

		@Override
		public Type<S2C> type() {
			return TYPE;
		}
	}

	/** Both sides must know both payload types; called from the common initializer. */
	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(C2S.TYPE, C2S.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2C.TYPE, S2C.CODEC);
	}

	/** {@code text} as PART pieces plus a final END piece (possibly empty), each at most {@link #CHUNK} chars. */
	public static List<String> split(String text) {
		List<String> out = new ArrayList<>();
		int i = 0;
		while (text.length() - i > CHUNK) {
			int end = i + CHUNK;
			if (Character.isHighSurrogate(text.charAt(end - 1))) {
				end--; // never split a surrogate pair
			}
			out.add(text.substring(i, end));
			i = end;
		}
		out.add(text.substring(i));
		return out;
	}
}
