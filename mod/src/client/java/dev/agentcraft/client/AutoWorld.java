package dev.agentcraft.client;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.world.HqWorld;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
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
 * Boots straight into the "AgentCraft HQ" world without any clicks: the first time the title
 * screen appears, the world is loaded if it exists, or created (creative, peaceful, superflat
 * grass meadow with no structures/decoration) if it does not. Disable with AGENTCRAFT_AUTOWORLD=0.
 */
public final class AutoWorld {
	private static boolean attempted;

	private AutoWorld() {
	}

	public static void init() {
		if (!ClientEnv.AUTO_WORLD) {
			AgentCraft.LOGGER.info("AutoWorld disabled (AGENTCRAFT_AUTOWORLD=0)");
			return;
		}
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
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

	public static void openOrCreate(Minecraft mc) {
		try {
			if (mc.getLevelSource().levelExists(HqWorld.LEVEL_NAME)) {
				AgentCraft.LOGGER.info("AutoWorld: loading existing world '{}'", HqWorld.LEVEL_NAME);
				mc.createWorldOpenFlows().openWorld(HqWorld.LEVEL_NAME, () -> mc.gui.setScreen(new TitleScreen()));
			} else {
				AgentCraft.LOGGER.info("AutoWorld: creating world '{}'", HqWorld.LEVEL_NAME);
				LevelSettings settings = new LevelSettings(
					HqWorld.LEVEL_NAME,
					GameType.CREATIVE,
					new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
					true,
					WorldDataConfiguration.DEFAULT
				);
				WorldOptions options = new WorldOptions("agentcraft-hq".hashCode(), false, false);
				mc.createWorldOpenFlows().createFreshLevel(HqWorld.LEVEL_NAME, settings, options, AutoWorld::meadowDimensions, new TitleScreen());
			}
		} catch (Exception e) {
			AgentCraft.LOGGER.error("AutoWorld failed; staying on the title screen", e);
			mc.gui.setScreen(new TitleScreen());
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
