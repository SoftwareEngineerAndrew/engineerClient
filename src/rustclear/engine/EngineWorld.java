package net.rustclear.mod.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.rustclear.mod.RustclearMod;

/**
 * The singleplayer world "rustclear": an empty void world the engine builds dungeons into. It is
 * made fresh every time it is opened, so nothing from an earlier run carries over. (The same idea
 * as engineerClient's p3sim world.)
 */
public final class EngineWorld {
    public static final String NAME = "rustclear";
    /** Written into the world folder once the engine has started it, so a world of that name made by hand isn't taken for it. */
    public static final String MARKER = "rustclear-engine.txt";

    /** The mod is opening the world right now. */
    public static volatile boolean opening;

    private EngineWorld() {
    }

    /** Leaves whatever world or server you are on and opens a fresh Rustclear world. */
    public static void open() {
        preload();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (EngineServer.server() != null) {
                return;
            }
            if (mc.level != null) {
                mc.disconnectFromWorld(Component.literal("Opening Rustclear"));
            }
            opening = true;
            try {
                LevelStorageSource source = mc.getLevelSource();
                if (Files.isDirectory(source.getBaseDir().resolve(NAME))) {
                    try (LevelStorageSource.LevelStorageAccess access = source.createAccess(NAME)) {
                        access.deleteLevel();
                    }
                }
                create(mc);
            } catch (IOException | RuntimeException e) {
                opening = false;
                RustclearMod.LOGGER.error("Couldn't open the Rustclear world", e);
                mc.gui.setScreen(new TitleScreen());
            }
        });
    }

    private static final AtomicBoolean PRELOADING = new AtomicBoolean();

    /**
     * Loads the dungeon data (rooms, then mobs, secrets and puzzles) and converts the rooms' blocks
     * on a background thread, while Minecraft creates the world, so the first dungeon doesn't wait. Only the first open does anything;
     * a build that gets there first just waits for it (RoomStore.get, LegacyBlocks.warm).
     */
    private static void preload() {
        if (!PRELOADING.compareAndSet(false, true)) {
            return;
        }
        Thread.ofPlatform().daemon().name("Rustclear preload").start(() -> {
            long started = System.nanoTime();
            try {
                LegacyBlocks.shared().warm(RoomStore.get());
                // Then what the first dungeon's run reads as it starts: mobs, secrets, puzzles.
                MobStore.get();
                RoomExtras.get();
                FallingBlocks.load();
                Superboom.data();
                WaterBoardPuzzle.data();
                BoulderPuzzle.patterns();
                IceFillPuzzle.patterns();
                RustclearMod.LOGGER.info("Preloaded Rustclear dungeon data in {} ms", (System.nanoTime() - started) / 1_000_000);
                long warmed = System.nanoTime();
                DungeonBuilder.warmWriter(RoomStore.get());
                RustclearMod.LOGGER.info("Warmed the dungeon writer in {} ms", (System.nanoTime() - warmed) / 1_000_000);
            } catch (RuntimeException e) {
                RustclearMod.LOGGER.warn("Couldn't preload Rustclear dungeon data", e);
            }
        });
    }

    private static void create(Minecraft mc) {
        mc.gui.setScreen(new GenericMessageScreen(Component.literal("Building the dungeon...")));
        LevelSettings settings = new LevelSettings(
            NAME, GameType.SURVIVAL,
            new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, true),
            true, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(NAME, settings, new WorldOptions(0L, false, false), provider -> {
            var biomes = provider.lookupOrThrow(Registries.BIOME);
            var flat = new FlatLevelGeneratorSettings(Optional.empty(), biomes.getOrThrow(Biomes.THE_VOID), List.of());
            var dimensions = provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value()
                .createWorldDimensions().replaceOverworldGenerator(provider, new FlatLevelSource(flat));
            // The duel teams' void dimensions, so each team's dungeon sits at the same coordinates.
            return TeamDimensions.withTeams(provider, dimensions, new FlatLevelSource(flat));
        }, new TitleScreen());
    }

    public static boolean isMarked(Path worldDir) {
        return Files.isRegularFile(worldDir.resolve(MARKER));
    }

    public static void mark(Path worldDir) {
        try {
            Files.writeString(worldDir.resolve(MARKER), RustclearMod.version());
        } catch (IOException e) {
            RustclearMod.LOGGER.warn("Couldn't mark the Rustclear world: {}", e.toString());
        }
    }
}
