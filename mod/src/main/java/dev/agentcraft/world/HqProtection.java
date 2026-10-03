package dev.agentcraft.world;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.command.AgentCraftCommands;
import dev.agentcraft.hq.HqBuilder;
import dev.agentcraft.hq.HqBuilders;
import dev.agentcraft.hq.HqSite;
import dev.agentcraft.layout.Anchors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the HQ intact on shared and survival worlds: players who are not op (and not the singleplayer
 * owner) cannot break blocks, place blocks, pour fluids or light fires anywhere in the area the HQ
 * builder writes deterministically (its whole site box: building, grounds, pond, garden, hills; shifted
 * to the saved site of a relocated HQ). Doors, chests, buttons and the stations stay usable, and
 * everything outside the box (including the earthworks ring) is ordinary world.
 *
 * <p>The region follows the published layout's builder ({@link HqBuilder#siteBox}); a builder that does
 * not report a box falls back to the layout's walkable bounds. On by default;
 * {@code /agentcraft protect on|off} switches it (saved in the world marker). Server side only, like
 * vanilla spawn protection.
 */
public final class HqProtection {
	private static final String KEY = "protect";

	private static volatile boolean enabled = true;
	private static volatile @Nullable MinecraftServer server;
	/** Protected box in world coordinates {minX, minY, minZ, maxX, maxY, maxZ}, or null (no HQ built). */
	private static volatile int @Nullable [] box;

	private HqProtection() {
	}

	public static void init() {
		// registered before Anchors: the server is known when the saved layout is loaded and published
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			box = null;
			if (HqWorld.isHq(s)) {
				server = s;
				WorldMarker m = WorldMarker.load(s);
				enabled = !m.json().has(KEY) || m.json().get(KEY).getAsBoolean();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			server = null;
			box = null;
		});
		Anchors.addListener(layout -> {
			MinecraftServer s = server;
			if (s != null) {
				box = region(s, layout);
				int[] b = box;
				if (b != null) {
					AgentCraft.LOGGER.info("HQ protection region {} {} {} .. {} {} {} ({})", b[0], b[1], b[2], b[3], b[4], b[5], enabled ? "on" : "off");
				}
			}
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> !denied(level, player, pos));
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!builds(player.getItemInHand(hand).getItem())) {
				return InteractionResult.PASS;
			}
			BlockPos at = hit.getBlockPos();
			return denied(level, player, at) || denied(level, player, at.relative(hit.getDirection())) ? InteractionResult.FAIL : InteractionResult.PASS;
		});
		// buckets aimed at nothing solid (fluids, air) never reach UseBlockCallback
		UseItemCallback.EVENT.register((player, level, hand) -> player.getItemInHand(hand).getItem() instanceof BucketItem
			&& denied(level, player, player.blockPosition()) ? InteractionResult.FAIL : InteractionResult.PASS);
		AgentCraftCommands.sub(root -> root.then(Commands.literal("protect")
			.executes(ctx -> {
				ctx.getSource().sendSuccess(() -> Component.literal("HQ protection is " + (enabled ? "on" : "off")
					+ " (non-op players cannot build or break anywhere on the HQ site)"), false);
				return enabled ? 1 : 0;
			})
			.then(Commands.literal("on").executes(ctx -> set(ctx.getSource(), true)))
			.then(Commands.literal("off").executes(ctx -> set(ctx.getSource(), false)))));
	}

	private static int set(CommandSourceStack src, boolean on) {
		MinecraftServer srv = src.getServer();
		if (!HqWorld.isHq(srv)) {
			src.sendFailure(Component.literal("Not an AgentCraft HQ world"));
			return 0;
		}
		enabled = on;
		WorldMarker m = WorldMarker.load(srv);
		m.json().addProperty(KEY, on);
		m.save();
		src.sendSuccess(() -> Component.literal("HQ protection " + (on ? "on" : "off")), true);
		return 1;
	}

	/** Items that change blocks where they are used: blocks, fluids, fire. */
	private static boolean builds(Item item) {
		return item instanceof BlockItem || item instanceof BucketItem || item instanceof FlintAndSteelItem || item instanceof FireChargeItem;
	}

	/** True (and the player is told) when {@code player} may not change the block at {@code pos}. */
	private static boolean denied(Level level, Player player, BlockPos pos) {
		if (!enabled || level.isClientSide() || !(player instanceof ServerPlayer sp) || level != sp.level().getServer().overworld()) {
			return false;
		}
		MinecraftServer srv = sp.level().getServer();
		if (!HqWorld.isHq(srv) || !inside(pos) || exempt(srv, sp)) {
			return false;
		}
		sp.sendOverlayMessage(Component.literal("The HQ site is protected - ask an op (/agentcraft protect)"));
		return true;
	}

	private static boolean exempt(MinecraftServer server, ServerPlayer player) {
		return server.isSingleplayerOwner(player.nameAndId()) || Commands.LEVEL_GAMEMASTERS.check(player.permissions());
	}

	/** Inside the protected region. Any thread. */
	public static boolean inside(BlockPos pos) {
		int[] b = box;
		return b != null && pos.getX() >= b[0] && pos.getY() >= b[1] && pos.getZ() >= b[2] && pos.getX() <= b[3] && pos.getY() <= b[4]
			&& pos.getZ() <= b[5];
	}

	/** The builder's site box at the saved site offset; the layout bounds when the builder reports none. */
	private static int @Nullable [] region(MinecraftServer s, Anchors.Layout layout) {
		if (layout.isEmpty()) {
			return null;
		}
		HqBuilder builder = HqBuilders.get(layout.name());
		int[] site = builder == null ? null : builder.siteBox();
		if (site != null) {
			BlockPos o = builder.relocatable() ? HqSite.savedOrigin(s) : BlockPos.ZERO;
			return new int[] {site[0] + o.getX(), site[1] + o.getY(), site[2] + o.getZ(), site[3] + o.getX(), site[4] + o.getY(),
				site[5] + o.getZ()};
		}
		Anchors.Bounds b = layout.bounds();
		return b == null ? null : new int[] {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()};
	}
}
