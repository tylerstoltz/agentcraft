package dev.agentcraft.hq;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.command.AgentCraftCommands;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.world.HqWorld;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * HQ feature (common side): {@code /agentcraft hq [builder] [force]} builds the HQ and publishes its
 * anchors. The studio builder keeps cells the player changed since its last build; {@code force}
 * resets them too.
 */
public final class HqFeature {
	private HqFeature() {
	}

	/** The report of the last build (for QA: {@code dev.state.hq.lastBuild}). */
	private static volatile @Nullable String lastReport;

	public static @Nullable String lastReport() {
		return lastReport;
	}

	public static void init() {
		HqBuilders.register(new TestRoomBuilder());
		HqBuilders.register(new StudioHqBuilder());
		HqBuilders.setDefault(StudioHqBuilder.ID);
		// A fresh HQ world (no saved layout yet) gets the default HQ built before the player joins, so
		// the first launch walks straight into it. AGENTCRAFT_HQ_AUTOBUILD=0 turns this off.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!HqWorld.isHq(server) || !Anchors.current().isEmpty() || !autoBuild()) {
				return;
			}
			HqBuilder builder = HqBuilders.get(HqBuilders.defaultId());
			if (builder != null) {
				try {
					buildAndPublish(server.overworld(), builder, HqBuilder.Options.DEFAULT);
					AgentCraft.LOGGER.info("Fresh HQ world: built the default HQ '{}'", builder.id());
				} catch (RuntimeException e) {
					AgentCraft.LOGGER.error("Auto-building the HQ failed (run /agentcraft hq)", e);
				}
			}
		});
		AgentCraftCommands.sub(root -> root.then(Commands.literal("hq")
			.executes(ctx -> build(ctx, HqBuilders.defaultId(), false))
			.then(Commands.literal("force").executes(ctx -> build(ctx, HqBuilders.defaultId(), true)))
			.then(Commands.argument("builder", StringArgumentType.word())
				.suggests((ctx, b) -> {
					HqBuilders.ids().forEach(b::suggest);
					return b.buildFuture();
				})
				.executes(ctx -> build(ctx, StringArgumentType.getString(ctx, "builder"), false))
				.then(Commands.literal("force").executes(ctx -> build(ctx, StringArgumentType.getString(ctx, "builder"), true))))));
	}

	private static boolean autoBuild() {
		String v = System.getProperty("agentcraft.hq.autobuild");
		if (v == null) {
			v = System.getenv("AGENTCRAFT_HQ_AUTOBUILD");
		}
		return v == null || !(v.trim().equals("0") || v.trim().equalsIgnoreCase("false") || v.trim().equalsIgnoreCase("off"));
	}

	private static int build(CommandContext<CommandSourceStack> ctx, String id, boolean force) {
		HqBuilder builder = HqBuilders.get(id);
		if (builder == null) {
			ctx.getSource().sendFailure(Component.literal("Unknown HQ builder '" + id + "' (known: " + HqBuilders.ids() + ")"));
			return 0;
		}
		Anchors.Layout layout;
		try {
			layout = buildAndPublish(ctx.getSource().getLevel(), builder, new HqBuilder.Options(force));
		} catch (RuntimeException e) {
			AgentCraft.LOGGER.error("HQ builder '{}' failed", id, e);
			ctx.getSource().sendFailure(Component.literal("HQ builder '" + id + "' failed: " + e));
			return 0;
		}
		String report = lastReport;
		ctx.getSource().sendSuccess(() -> Component.literal("Built HQ '" + id + "': " + layout.anchors().size() + " anchors"
			+ (report == null ? "" : ". " + report)), true);
		return layout.anchors().size();
	}

	/** Runs {@code builder} (server thread), publishes its layout and moves the world spawn to its spawn anchor. */
	public static Anchors.Layout buildAndPublish(ServerLevel level, HqBuilder builder) {
		return buildAndPublish(level, builder, HqBuilder.Options.DEFAULT);
	}

	/** {@code layout} moved by {@code o} (a relocatable builder reports its anchors in its own coordinates). */
	static Anchors.Layout shifted(Anchors.Layout layout, BlockPos o) {
		if (o.equals(BlockPos.ZERO)) {
			return layout;
		}
		Map<String, Anchor> moved = new LinkedHashMap<>();
		layout.anchors().forEach((name, a) -> moved.put(name, new Anchor(a.name(), a.x() + o.getX(), a.y() + o.getY(), a.z() + o.getZ(),
			a.yaw(), a.pitch())));
		Anchors.Bounds b = layout.bounds();
		Anchors.Bounds mb = b == null ? null : new Anchors.Bounds(b.minX() + o.getX(), b.minY() + o.getY(), b.minZ() + o.getZ(),
			b.maxX() + o.getX(), b.maxY() + o.getY(), b.maxZ() + o.getZ());
		return new Anchors.Layout(layout.name(), layout.revision(), mb, moved);
	}

	public static Anchors.Layout buildAndPublish(ServerLevel level, HqBuilder builder, HqBuilder.Options options) {
		long t0 = System.nanoTime();
		// another builder rewrites the same ground without a record: the studio's memory of its last
		// build no longer describes the world
		PlanStore.invalidateUnless(level.getServer(), builder.id());
		HqSite.Site site = HqSite.Site.CLASSIC;
		if (builder.relocatable()) {
			site = HqSite.resolve(level);
			options = new HqBuilder.Options(options.force(), site);
			AgentCraft.LOGGER.info("HQ site: {} (offset {})", site.report(), site.origin().toShortString());
		}
		Anchors.Builder anchors = Anchors.builder(builder.id());
		lastReport = builder.build(level, anchors, options);
		Anchors.Layout layout = shifted(anchors.build(), site.origin());
		Anchors.publish(level.getServer(), layout);
		Anchor spawn = layout.get(AnchorNames.SPAWN);
		if (spawn != null) {
			level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(),
				String.format(Locale.ROOT, "setworldspawn %d %d %d %.1f 0", (int) Math.floor(spawn.x()), (int) Math.floor(spawn.y()),
					(int) Math.floor(spawn.z()), spawn.yaw()));
		}
		AgentCraft.LOGGER.info("HQ '{}' built in {} ms{}", builder.id(), (System.nanoTime() - t0) / 1_000_000,
			lastReport == null ? "" : ": " + lastReport);
		return layout;
	}
}
