package dev.agentcraft.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.world.Trust;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@code /agentcraft} command root. Features add their own sub-commands with
 * {@link #sub(Consumer)} from their {@code init()}, so nobody has to edit this file:
 * <pre>
 * AgentCraftCommands.sub(root -> root.then(Commands.literal("hq").executes(ctx -> ...)));
 * </pre>
 * Built in: {@code /agentcraft anchors} lists the published anchors.
 */
public final class AgentCraftCommands {
	private static final List<Consumer<LiteralArgumentBuilder<CommandSourceStack>>> SUBS = new CopyOnWriteArrayList<>();

	private AgentCraftCommands() {
	}

	public static void sub(Consumer<LiteralArgumentBuilder<CommandSourceStack>> contributor) {
		SUBS.add(contributor);
	}

	public static void init() {
		sub(root -> root.then(Commands.literal("anchors").executes(ctx -> {
			Anchors.Layout layout = Anchors.current();
			ctx.getSource().sendSuccess(() -> Component.literal("Layout '" + layout.name() + "' rev " + layout.revision() + ": "
				+ layout.anchors().size() + " anchors"), false);
			for (Anchor a : layout.anchors().values()) {
				ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "  %s  %.2f %.2f %.2f  yaw %.0f pitch %.0f",
					a.name(), a.x(), a.y(), a.z(), a.yaw(), a.pitch())), false);
			}
			return layout.anchors().size();
		})));
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
			LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("agentcraft").requires(AgentCraftCommands::allowed);
			for (var s : SUBS) {
				s.accept(root);
			}
			dispatcher.register(root);
		});
	}

	/**
	 * Players need {@link Trust#isOperator} (the ops list, not the effective level: a LAN world with
	 * Allow Commands gives every guest level 2, and {@code /agentcraft protect off} must not be theirs).
	 * The console, command blocks and functions keep the plain level 2 check.
	 */
	private static boolean allowed(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		return player != null ? Trust.isOperator(src.getServer(), player) : Commands.LEVEL_GAMEMASTERS.check(src.permissions());
	}
}
