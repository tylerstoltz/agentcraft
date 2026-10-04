package dev.agentcraft.world;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.command.AgentCraftCommands;
import dev.agentcraft.hq.HqBuilder;
import dev.agentcraft.hq.HqBuilders;
import dev.agentcraft.hq.HqSite;
import dev.agentcraft.layout.Anchors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the HQ intact on shared and survival worlds, in the area the HQ builder writes
 * deterministically (its whole site box: building, grounds, pond, garden, hills; shifted to the saved
 * site of a relocated HQ). Everything outside the box (including the earthworks ring) is ordinary world.
 *
 * <ul>
 *   <li><b>Players</b> who are not {@link Trust#isOperator operators} cannot break or place blocks, pour
 *       or scoop fluids, light fires, use tools or items on blocks (stripping, tilling, waxing, bone
 *       meal, ...), or change blocks by hand (pots, candles, signs, lecterns, repeaters, ...). Doors,
 *       trapdoors, gates, buttons, levers, beds, bells, crafting tables, containers and the AgentCraft
 *       stations stay usable. Item frames, paintings, armor stands and mannequins cannot be hit or used.</li>
 *   <li><b>The world</b> cannot change the box from outside or through mobs (mixins in
 *       {@code dev.agentcraft.mixin}): explosions leave its blocks alone, pistons and dispensers
 *       outside cannot push or pour into it, fluids from outside do not flow in, and non-player entities
 *       (endermen, ravagers, falling blocks, sheep, ...) cannot set blocks in it. Mob griefing outside
 *       the box stays as the game rule says.</li>
 * </ul>
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
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> useBlock(player, level, hand, hit) ? InteractionResult.FAIL : InteractionResult.PASS);
		// buckets aimed at fluids or air, and blocks placed on water (lily pads), never reach UseBlockCallback
		UseItemCallback.EVENT.register((player, level, hand) -> useItem(player, level, hand) ? InteractionResult.FAIL : InteractionResult.PASS);
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> fixture(entity) && denied(level, player, entity.blockPosition())
			? InteractionResult.FAIL : InteractionResult.PASS);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> fixture(entity) && denied(level, player, entity.blockPosition())
			? InteractionResult.FAIL : InteractionResult.PASS);
		AgentCraftCommands.sub(root -> root.then(Commands.literal("protect")
			.executes(ctx -> {
				ctx.getSource().sendSuccess(() -> Component.literal("HQ protection is " + (enabled ? "on" : "off")
					+ " (non-op players cannot change anything on the HQ site)"), false);
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

	/** Right-click on a block: true when it must be refused. */
	private static boolean useBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		BlockPos at = hit.getBlockPos();
		ItemStack stack = player.getItemInHand(hand);
		if (inside(at)) {
			// Sneaking with an item skips the block and uses the item on it (placing, stripping, waxing, ...).
			boolean itemFirst = player.isSecondaryUseActive() && !stack.isEmpty();
			if (itemFirst || !usable(level, at)) {
				return denied(level, player, at);
			}
			if (consumesUse(level, at)) {
				return false; // vanilla opens / presses / sleeps; the held item is never used
			}
		}
		// a block or fluid placed against an outside block can still land inside
		return builds(stack.getItem()) && denied(level, player, at.relative(hit.getDirection()));
	}

	/** Right-click in the air (or on a fluid): true when it must be refused. */
	private static boolean useItem(Player player, Level level, InteractionHand hand) {
		Item item = player.getItemInHand(hand).getItem();
		if (!builds(item) || box == null) {
			return false;
		}
		// Where the item would act, as BucketItem / PlaceOnWaterBlockItem aim: an empty bucket picks up
		// source fluids, everything else goes through fluids to the block behind.
		boolean empty = item instanceof BucketItem bucket && bucket.getContent() == Fluids.EMPTY;
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1).scale(player.blockInteractionRange()));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
			empty || item instanceof BlockItem ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, player));
		if (hit.getType() != HitResult.Type.BLOCK) {
			return false;
		}
		BlockPos at = hit.getBlockPos();
		return denied(level, player, at) || denied(level, player, at.relative(hit.getDirection()));
	}

	/** Items that change blocks where they are used: blocks, fluids, fire. */
	private static boolean builds(Item item) {
		return item instanceof BlockItem || item instanceof BucketItem || item instanceof FlintAndSteelItem || item instanceof FireChargeItem;
	}

	/**
	 * Blocks every player may use on the HQ site: they open, ring, sit or press but keep the build as
	 * built. Everything else (pots, candles, signs, lecterns, campfires, repeaters, bookshelves, ...)
	 * would change it.
	 */
	private static boolean usable(Level level, BlockPos pos) {
		Block block = level.getBlockState(pos).getBlock();
		if (block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock || block instanceof ButtonBlock
			|| block instanceof LeverBlock || block instanceof BedBlock || block instanceof BellBlock || block instanceof CraftingTableBlock
			|| BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(AgentCraft.MOD_ID)) {
			return true;
		}
		BlockEntity be = level.getBlockEntity(pos);
		// chests, barrels, furnaces...; a lectern's menu would let anyone take its book
		return be instanceof Container && be instanceof MenuProvider && !(be instanceof LecternBlockEntity);
	}

	/** Usable blocks whose use always succeeds, so a held block or bucket is never placed through them. */
	private static boolean consumesUse(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		Block block = state.getBlock();
		if (block instanceof DoorBlock door) {
			return door.type().canOpenByHand();
		}
		if (block instanceof TrapDoorBlock) {
			return state.is(BlockTags.WOODEN_TRAPDOORS);
		}
		return block instanceof FenceGateBlock || block instanceof ButtonBlock || block instanceof LeverBlock || block instanceof BedBlock
			|| block instanceof CraftingTableBlock || level.getBlockEntity(pos) instanceof MenuProvider;
	}

	/** Decoration entities that belong to the build. */
	private static boolean fixture(Entity entity) {
		return entity instanceof BlockAttachedEntity || entity instanceof ArmorStand || entity instanceof Mannequin;
	}

	/** True (and the player is told) when {@code player} may not change the block at {@code pos}. */
	private static boolean denied(Level level, Player player, BlockPos pos) {
		if (!(player instanceof ServerPlayer sp) || !guards(level) || !inside(pos)) {
			return false;
		}
		if (Trust.isOperator(sp.level().getServer(), sp)) {
			return false;
		}
		sp.sendOverlayMessage(Component.literal("The HQ site is protected - ask an op (/agentcraft protect)"));
		return true;
	}

	// ------------------------------------------------------------------ world changes (mixins, server thread)

	/** Non-player entity whose tick is running on the server thread, or null. */
	private static @Nullable Entity ticking;
	/** Set when a block change during {@link #ticking}'s tick was refused. */
	private static boolean refused;

	/** True when protection is on and {@code level} is the HQ world's overworld with a built HQ. */
	public static boolean guards(Level level) {
		MinecraftServer s = server;
		return enabled && box != null && s != null && !level.isClientSide() && level == s.overworld();
	}

	/** Explosions, fluids: {@code pos} is protected in {@code level}. */
	public static boolean protects(Level level, BlockPos pos) {
		return inside(pos) && guards(level);
	}

	/** Pistons, dispensers, fluids: a change from {@code from} reaching {@code to} crosses into the box. */
	public static boolean crossesIn(Level level, BlockPos from, BlockPos to) {
		return inside(to) && !inside(from) && guards(level);
	}

	/** Around a server entity tick; returns the previous value for {@link #endTick} (passengers nest). */
	public static @Nullable Entity beginTick(Entity entity) {
		Entity prev = ticking;
		ticking = entity instanceof Player ? null : entity;
		return prev;
	}

	public static void endTick(@Nullable Entity prev) {
		ticking = prev;
	}

	/**
	 * {@code Level.setBlock}: true when the ticking entity may not put {@code state} at {@code pos}.
	 * Only changes of block type are refused, so mobs still open doors and press plates.
	 */
	public static boolean refuseEntityEdit(Level level, BlockPos pos, BlockState state) {
		if (ticking == null || !inside(pos) || !guards(level) || level.getBlockState(pos).is(state.getBlock())) {
			return false;
		}
		refused = true;
		return true;
	}

	/** True once after a refused entity edit (an enderman then does not get the block it failed to take). */
	public static boolean takeRefused() {
		boolean r = refused;
		refused = false;
		return r;
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
