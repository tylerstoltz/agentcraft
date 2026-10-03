package dev.agentcraft.client;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.Env;
import dev.agentcraft.world.HqWorld;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import dev.agentcraft.client.mixin.BackupConfirmScreenAccessor;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Boots straight into the HQ world without any clicks: the first time the title screen appears, the
 * world is loaded if it exists, or created if it does not. Disable with AGENTCRAFT_AUTOWORLD=0.
 *
 * <p>Creation settings (they only matter when the world is created; an existing world keeps its own):
 * <pre>
 * AGENTCRAFT_WORLD_NAME   folder + level name (default "AgentCraft HQ"); a new name = a new world
 * AGENTCRAFT_WORLD        flat (default: superflat meadow, no structures) | normal (vanilla terrain)
 * AGENTCRAFT_SEED         number or text; default: fixed for flat, random for normal
 * AGENTCRAFT_GAMEMODE     creative (default: the studio profile) | survival | hardcore
 * AGENTCRAFT_DIFFICULTY   peaceful | easy | normal | hard (default: peaceful in creative, normal
 *                         in survival; hardcore is always hard)
 * AGENTCRAFT_CHEATS       commands allowed (default on, off in hardcore)
 * </pre>
 * Where the HQ goes in a normal world is up to the server side (AGENTCRAFT_HQ_SITE, see HqSite).
 */
public final class AutoWorld {
	private static boolean attempted;
	/** True while AutoWorld itself is opening the HQ world (so its confirm screens may be auto-answered). */
	private static boolean openingHq;

	private AutoWorld() {
	}

	public static void init() {
		if (!ClientEnv.AUTO_WORLD) {
			AgentCraft.LOGGER.info("AutoWorld disabled (AGENTCRAFT_AUTOWORLD=0)");
			return;
		}
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> openingHq = false);
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (openingHq && screen instanceof BackupConfirmScreen backup) {
				// Registry content changed since the HQ world was saved (a block/entity was renamed or removed
				// while developing). The HQ world is generated, so take Fabric's backup and load it instead of
				// waiting forever on "Missing content detected!" in an unattended run.
				openingHq = false;
				AgentCraft.LOGGER.warn("AutoWorld: '{}' needs confirmation ({}); making a backup and loading it",
					worldName(), screen.getTitle().getString());
				client.execute(() -> ((BackupConfirmScreenAccessor) backup).agentcraft$onProceed().proceed(true, false));
				return;
			}
			if (attempted) {
				return;
			}
			if (screen instanceof TitleScreen || screen instanceof AccessibilityOnboardingScreen) {
				attempted = true;
				// Never switch screens from inside another screen's init.
				client.execute(() -> openOrCreate(client));
			}
		});
	}

	/** The HQ world's folder / level name. */
	public static String worldName() {
		return Env.str("AGENTCRAFT_WORLD_NAME", HqWorld.LEVEL_NAME);
	}

	public static void openOrCreate(Minecraft mc) {
		String name = worldName();
		try {
			HqWorld.expectHqWorld(name);
			if (mc.getLevelSource().levelExists(name)) {
				AgentCraft.LOGGER.info("AutoWorld: loading existing world '{}'", name);
				if (Env.raw("AGENTCRAFT_WORLD") != null || Env.raw("AGENTCRAFT_SEED") != null || Env.raw("AGENTCRAFT_GAMEMODE") != null) {
					AgentCraft.LOGGER.warn("AutoWorld: '{}' already exists, so AGENTCRAFT_WORLD/SEED/GAMEMODE are ignored"
						+ " (set AGENTCRAFT_WORLD_NAME to create another world)", name);
				}
				openingHq = true;
				mc.createWorldOpenFlows().openWorld(name, () -> mc.gui.setScreen(new TitleScreen()));
			} else {
				Creation c = Creation.fromEnv();
				AgentCraft.LOGGER.info("AutoWorld: creating world '{}' ({} terrain, seed {}, {}, {}{})", name, c.normal ? "normal" : "flat",
					c.seed, c.hardcore ? "hardcore" : c.mode.getName(), c.difficulty.getSerializedName(), c.cheats ? ", cheats" : "");
				LevelSettings settings = new LevelSettings(
					name,
					c.mode,
					new LevelSettings.DifficultySettings(c.difficulty, c.hardcore, false),
					c.cheats,
					WorldDataConfiguration.DEFAULT
				);
				WorldOptions options = new WorldOptions(c.seed, c.normal, false);
				mc.createWorldOpenFlows().createFreshLevel(name, settings, options,
					c.normal ? WorldPresets::createNormalWorldDimensions : AutoWorld::meadowDimensions, new TitleScreen());
			}
		} catch (Exception e) {
			AgentCraft.LOGGER.error("AutoWorld failed; staying on the title screen", e);
			mc.gui.setScreen(new TitleScreen());
		}
	}

	/** World creation settings from the environment (see the class doc). */
	record Creation(boolean normal, long seed, GameType mode, boolean hardcore, Difficulty difficulty, boolean cheats) {
		static Creation fromEnv() {
			String world = Env.str("AGENTCRAFT_WORLD", "flat").toLowerCase(Locale.ROOT);
			boolean normal = switch (world) {
				case "normal", "default", "vanilla", "random" -> true;
				case "flat", "meadow", "superflat" -> false;
				default -> {
					AgentCraft.LOGGER.warn("AGENTCRAFT_WORLD='{}' is not flat|normal; using flat", world);
					yield false;
				}
			};
			String seedText = Env.raw("AGENTCRAFT_SEED");
			long seed = seedText != null ? WorldOptions.parseSeed(seedText).orElse(WorldOptions.randomSeed())
				: normal ? WorldOptions.randomSeed() : "agentcraft-hq".hashCode();
			String gm = Env.str("AGENTCRAFT_GAMEMODE", "creative").toLowerCase(Locale.ROOT);
			boolean hardcore = gm.equals("hardcore");
			GameType mode = hardcore ? GameType.SURVIVAL : GameType.byName(gm, null);
			if (mode == null) {
				AgentCraft.LOGGER.warn("AGENTCRAFT_GAMEMODE='{}' is not creative|survival|hardcore; using creative", gm);
				mode = GameType.CREATIVE;
			}
			Difficulty difficulty = Difficulty.byName(Env.str("AGENTCRAFT_DIFFICULTY",
				mode == GameType.CREATIVE ? "peaceful" : "normal").toLowerCase(Locale.ROOT));
			if (difficulty == null) {
				AgentCraft.LOGGER.warn("AGENTCRAFT_DIFFICULTY is not peaceful|easy|normal|hard; using normal");
				difficulty = Difficulty.NORMAL;
			}
			if (hardcore) {
				difficulty = Difficulty.HARD;
			}
			boolean cheats = Env.flag("AGENTCRAFT_CHEATS", !hardcore);
			return new Creation(normal, seed, mode, hardcore, difficulty, cheats);
		}
	}

	/** Normal dimensions, with the overworld replaced by a flat plains meadow (grass top at y=64). */
	private static WorldDimensions meadowDimensions(HolderLookup.Provider registries) {
		Holder<Biome> plains = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
		FlatLevelGeneratorSettings base = new FlatLevelGeneratorSettings(Optional.of(HolderSet.empty()), plains, List.of());
		// y=-64 bedrock, stone up to 60, dirt 61..63, grass 64.
		List<FlatLayerInfo> layers = List.of(
			new FlatLayerInfo(1, Blocks.BEDROCK),
			new FlatLayerInfo(124, Blocks.STONE),
			new FlatLayerInfo(3, Blocks.DIRT),
			new FlatLayerInfo(1, Blocks.GRASS_BLOCK)
		);
		FlatLevelGeneratorSettings flat = base.withBiomeAndLayers(layers, Optional.of(HolderSet.empty()), plains);
		return WorldPresets.createNormalWorldDimensions(registries).replaceOverworldGenerator(registries, new FlatLevelSource(flat));
	}
}
