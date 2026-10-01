package dev.agentcraft.hq;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.command.AgentCraftCommands;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * HQ feature (common side): {@code /agentcraft hq [builder]} builds the HQ and publishes its anchors.
 * Phase 3's HQ specialist registers the real builder in {@link #init()} (or its own init) and makes
 * it the default; this package is theirs.
 */
public final class HqFeature {
	private HqFeature() {
	}

	public static void init() {
		HqBuilders.register(new TestRoomBuilder());
		AgentCraftCommands.sub(root -> root.then(Commands.literal("hq")
			.executes(ctx -> build(ctx, HqBuilders.defaultId()))
			.then(Commands.argument("builder", StringArgumentType.word())
				.suggests((ctx, b) -> {
					HqBuilders.ids().forEach(b::suggest);
					return b.buildFuture();
				})
				.executes(ctx -> build(ctx, StringArgumentType.getString(ctx, "builder"))))));
	}

	private static int build(CommandContext<CommandSourceStack> ctx, String id) {
		HqBuilder builder = HqBuilders.get(id);
		if (builder == null) {
			ctx.getSource().sendFailure(Component.literal("Unknown HQ builder '" + id + "' (known: " + HqBuilders.ids() + ")"));
			return 0;
		}
		Anchors.Layout layout = buildAndPublish(ctx.getSource().getLevel(), builder);
		ctx.getSource().sendSuccess(() -> Component.literal("Built HQ '" + id + "': " + layout.anchors().size() + " anchors"), true);
		return layout.anchors().size();
	}

	/** Runs {@code builder} (server thread), publishes its layout and moves the world spawn to its spawn anchor. */
	public static Anchors.Layout buildAndPublish(ServerLevel level, HqBuilder builder) {
		long t0 = System.nanoTime();
		Anchors.Builder anchors = Anchors.builder(builder.id());
		builder.build(level, anchors);
		Anchors.Layout layout = anchors.build();
		Anchors.publish(level.getServer(), layout);
		Anchor spawn = layout.get(AnchorNames.SPAWN);
		if (spawn != null) {
			level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(),
				String.format(Locale.ROOT, "setworldspawn %d %d %d %.1f 0", (int) Math.floor(spawn.x()), (int) Math.floor(spawn.y()),
					(int) Math.floor(spawn.z()), spawn.yaw()));
		}
		AgentCraft.LOGGER.info("HQ '{}' built in {} ms", builder.id(), (System.nanoTime() - t0) / 1_000_000);
		return layout;
	}
}
