package dev.agentcraft.world;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.Env;
import dev.agentcraft.command.AgentCraftCommands;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.jspecify.annotations.Nullable;

/**
 * The AgentCraft HQ world: identity, profile and rules. Other worlds are never touched.
 *
 * <p>A world is an HQ world when its level name is {@value #LEVEL_NAME}, when it carries a
 * {@link WorldMarker} from an earlier start, when the client's AutoWorld created it
 * ({@link #expectHqWorld}), or when the server runs with {@code AGENTCRAFT_HQ=1} (any world, e.g. a
 * normal seed on a dedicated server). Its {@link HqProfile} is taken from the world's own game mode
 * and hardcore flag at the first start, saved in the marker, and applied on every start.
 */
public final class HqWorld {
	/** Default folder name and level name of the HQ world. */
	public static final String LEVEL_NAME = "AgentCraft HQ";
	/** Golden-hour-ish day time set once when a studio world is created. */
	public static final long INITIAL_TIME = 12000L;
	/** Player tag: this player has been placed at the HQ entrance once. */
	private static final String ARRIVED_TAG = "agentcraft.arrived";

	/** A rule and the value a profile wants for it. */
	private record Rule<T>(GameRule<T> rule, T value) {
		static <T> Rule<T> of(GameRule<T> rule, T value) {
			return new Rule<>(rule, value);
		}

		void apply(MinecraftServer server) {
			server.getGameRules().set(rule, value, server);
		}

		void reset(MinecraftServer server) {
			server.getGameRules().set(rule, rule.defaultValue(), server);
		}
	}

	/** Every profile: keep the built HQ exactly as built, and allow the builder's large edits. */
	private static final List<Rule<?>> PROTECT = List.of(
		Rule.of(GameRules.MOB_GRIEFING, false),
		Rule.of(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0),
		Rule.of(GameRules.SPREAD_VINES, false),
		Rule.of(GameRules.MAX_SNOW_ACCUMULATION_HEIGHT, 0),
		// Respawn exactly at the spawn anchor, not scattered onto the roof.
		Rule.of(GameRules.RESPAWN_RADIUS, 0),
		Rule.of(GameRules.MAX_BLOCK_MODIFICATIONS, 1_000_000));

	/** Studio only: the calm workspace. Reset to vanilla defaults when a world leaves the studio profile. */
	private static final List<Rule<?>> STUDIO = List.of(
		Rule.of(GameRules.ADVANCE_TIME, false),
		Rule.of(GameRules.ADVANCE_WEATHER, false),
		Rule.of(GameRules.KEEP_INVENTORY, true),
		Rule.of(GameRules.SPAWN_MOBS, false),
		Rule.of(GameRules.SPAWN_MONSTERS, false),
		Rule.of(GameRules.SPAWN_PATROLS, false),
		Rule.of(GameRules.SPAWN_PHANTOMS, false),
		Rule.of(GameRules.SPAWN_WANDERING_TRADERS, false),
		Rule.of(GameRules.SPAWN_WARDENS, false),
		Rule.of(GameRules.LOCATOR_BAR, false),
		Rule.of(GameRules.SHOW_ADVANCEMENT_MESSAGES, false),
		Rule.of(GameRules.COMMAND_BLOCK_OUTPUT, false),
		Rule.of(GameRules.LOG_ADMIN_COMMANDS, false));

	private static volatile @Nullable MinecraftServer hqServer;
	private static volatile HqProfile profile = HqProfile.STUDIO;
	/** Level name the client's AutoWorld is about to create as an HQ world (singleplayer, same JVM). */
	private static volatile @Nullable String expected;

	private HqWorld() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(HqWorld::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			if (hqServer == server) {
				hqServer = null;
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!isHq(server)) {
				return;
			}
			ServerPlayer player = handler.getPlayer();
			// DevBridge camera shots switch to spectator; normal studio play resumes in creative.
			if (profile == HqProfile.STUDIO && (player.gameMode() == GameType.SPECTATOR || player.gameMode() == GameType.SURVIVAL)) {
				player.setGameMode(GameType.CREATIVE);
			}
			// Vanilla's first-join spawn search snaps to the highest block at the spawn column, which is
			// the entrance portico's roof: put first-time players exactly on the spawn anchor instead.
			if (player.addTag(ARRIVED_TAG)) {
				toSpawnAnchor(player);
			}
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (!alive && isHq(newPlayer.level().getServer()) && newPlayer.getRespawnConfig() == null) {
				toSpawnAnchor(newPlayer); // died with no bed / anchor of their own
			}
		});
		// /agentcraft mode [studio|survival]
		AgentCraftCommands.sub(root -> root.then(Commands.literal("mode")
			.executes(ctx -> {
				ctx.getSource().sendSuccess(() -> Component.literal(isHq(ctx.getSource().getServer())
					? "HQ profile: " + profile.wire() : "Not an AgentCraft HQ world"), false);
				return 1;
			})
			.then(Commands.literal("studio").executes(ctx -> mode(ctx.getSource(), HqProfile.STUDIO)))
			.then(Commands.literal("survival").executes(ctx -> mode(ctx.getSource(), HqProfile.SURVIVAL)))));
	}

	private static int mode(CommandSourceStack src, HqProfile to) {
		String error = switchProfile(src.getServer(), to);
		if (error != null) {
			src.sendFailure(Component.literal(error));
			return 0;
		}
		src.sendSuccess(() -> Component.literal("HQ profile: " + to.wire() + (to == HqProfile.STUDIO
			? " (creative, no mobs, frozen time)" : " (vanilla rules; the HQ stays protected from griefing). Set /difficulty as you like.")), true);
		return 1;
	}

	/** AutoWorld (client) is creating {@code levelName} as an HQ world: treat it as one when it starts. */
	public static void expectHqWorld(String levelName) {
		expected = levelName;
	}

	public static boolean isHq(@Nullable MinecraftServer server) {
		return server != null && server == hqServer;
	}

	/** The running HQ world's profile (studio when no HQ world runs). */
	public static HqProfile profile() {
		return profile;
	}

	/** Moves {@code player} onto the layout's spawn anchor (in front of the door), if there is one. */
	public static void toSpawnAnchor(ServerPlayer player) {
		Anchor spawn = Anchors.get(AnchorNames.SPAWN);
		if (spawn != null) {
			player.teleportTo(player.level().getServer().overworld(), spawn.x(), spawn.y(), spawn.z(), Set.of(), spawn.yaw(), 0f, true);
		}
	}

	private static boolean detect(MinecraftServer server) {
		String name = server.getWorldData().getLevelName();
		return LEVEL_NAME.equals(name) || name.equals(expected) || WorldMarker.exists(server) || Env.flag("AGENTCRAFT_HQ", false);
	}

	private static void onServerStarted(MinecraftServer server) {
		if (!detect(server)) {
			return;
		}
		hqServer = server;
		WorldMarker marker = WorldMarker.load(server);
		boolean fresh = !WorldMarker.exists(server);
		HqProfile p = HqProfile.parse(marker.str("profile"));
		if (p == null) {
			// First start (or a marker from before profiles existed: those worlds were all studio).
			p = fresh ? fromWorld(server) : HqProfile.STUDIO;
			marker.json().addProperty("profile", p.wire());
		}
		if (server.isHardcore() && p != HqProfile.HARDCORE) {
			p = HqProfile.HARDCORE; // the world decides; hardcore cannot be left
			marker.json().addProperty("profile", p.wire());
		}
		if (fresh) {
			marker.json().addProperty("createdBy", "agentcraft");
			marker.json().addProperty("created", Instant.now().toString());
		}
		marker.save();
		profile = p;
		applyRules(server, p, false);
		if (fresh && p == HqProfile.STUDIO) {
			run(server, "time set " + INITIAL_TIME);
			run(server, "weather clear");
		}
		AgentCraft.LOGGER.info("HQ world '{}' ({}{}, {}, difficulty {})", server.getWorldData().getLevelName(), p.wire(),
			fresh ? ", new" : "", server.getDefaultGameType().getName(), server.getWorldData().getDifficulty().getSerializedName());
	}

	private static HqProfile fromWorld(MinecraftServer server) {
		if (server.isHardcore()) {
			return HqProfile.HARDCORE;
		}
		GameType t = server.getDefaultGameType();
		return t == GameType.SURVIVAL || t == GameType.ADVENTURE ? HqProfile.SURVIVAL : HqProfile.STUDIO;
	}

	/**
	 * Switch the running HQ world between studio and survival ({@code /agentcraft mode}): saves the
	 * profile, applies its rules (leaving studio resets the studio rules to vanilla defaults) and
	 * moves the default and online players' game mode with it. Returns an error, or null.
	 */
	public static @Nullable String switchProfile(MinecraftServer server, HqProfile to) {
		if (!isHq(server)) {
			return "not an AgentCraft HQ world";
		}
		if (server.isHardcore() || to == HqProfile.HARDCORE) {
			return "hardcore is chosen when the world is created and cannot be switched";
		}
		if (to == profile) {
			return null;
		}
		WorldMarker marker = WorldMarker.load(server);
		marker.json().addProperty("profile", to.wire());
		marker.save();
		profile = to;
		applyRules(server, to, true);
		GameType mode = to == HqProfile.STUDIO ? GameType.CREATIVE : GameType.SURVIVAL;
		server.setDefaultGameType(mode);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.setGameMode(mode);
		}
		AgentCraft.LOGGER.info("HQ world switched to the {} profile", to.wire());
		return null;
	}

	/** Idempotent; applied on every start so rule changes in code reach existing worlds. */
	public static void applyRules(MinecraftServer server, HqProfile p, boolean switching) {
		for (Rule<?> r : PROTECT) {
			r.apply(server);
		}
		for (Rule<?> r : STUDIO) {
			if (p == HqProfile.STUDIO) {
				r.apply(server);
			} else if (switching) {
				r.reset(server);
			}
		}
	}

	private static void run(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
	}
}
