package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.levelgen.FlatLevelSource
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings
import net.minecraft.world.level.levelgen.presets.WorldPresets
import java.nio.file.Files
import java.util.Optional
import java.util.zip.CRC32

/**
 * The singleplayer world "p3sim": a void world the generator fills with the F7 boss arena
 * ([Arena.fill]). Opening it from anywhere (title screen button, `/p3sim`, the keybind) leaves the
 * server you are on first. A world made from an older arena is made again.
 */
object SimWorld {
    const val NAME = "p3sim"
    /** Written into the world folder: which arena it was built from. */
    private const val MARKER = "p3sim-arena.txt"
    /** Bump when the world itself must be made again (not just the arena data). */
    private const val WORLD_VERSION = 1

    /** The arena build the jar carries (a world made from another one is rebuilt). */
    val arenaVersion: String by lazy {
        val crc = CRC32()
        Arena::class.java.getResourceAsStream("/assets/engineerclient/p3sim/arena.bin")?.use { crc.update(it.readAllBytes()) }
        java.lang.Long.toHexString(crc.value) + "-" + WORLD_VERSION
    }

    /** Leaves whatever world or server you are in, then opens (or makes) the sim world. */
    fun open() {
        mc.execute {
            if (P3Sim.inSim) return@execute
            if (mc.level != null) mc.disconnectFromWorld(Component.literal("Opening P3 Sim"))
            EngineerClient.safely("p3sim open") { openOrCreate() }
        }
    }

    private fun openOrCreate() {
        val source = mc.levelSource
        val dir = source.baseDir.resolve(NAME)
        if (Files.isDirectory(dir)) {
            val built = try { Files.readString(dir.resolve(MARKER)).trim() } catch (_: Throwable) { "" }
            if (built == arenaVersion) {
                mc.createWorldOpenFlows().openWorld(NAME) { mc.setScreen(TitleScreen()) }
                return
            }
            EngineerClient.logger.info("[p3sim] rebuilding the sim world (arena {} -> {})", built, arenaVersion)
            source.createAccess(NAME).use { it.deleteLevel() }
        }
        create()
    }

    /** Back to the title screen (saving the world). */
    fun leave() {
        mc.execute {
            if (!P3Sim.inSim) return@execute
            mc.disconnectFromWorld(Component.literal("Leaving P3 Sim"))
            mc.setScreen(TitleScreen())
        }
    }

    /** Deletes the world and makes it again (the menu's "Rebuild world"). */
    fun rebuild() {
        mc.execute {
            if (mc.level != null) mc.disconnectFromWorld(Component.literal("Rebuilding P3 Sim"))
            EngineerClient.safely("p3sim rebuild") {
                mc.levelSource.createAccess(NAME).use { it.deleteLevel() }
                create()
            }
        }
    }

    private fun create() {
        mc.setScreen(GenericMessageScreen(Component.literal("Building the F7 boss...")))
        val settings = LevelSettings(
            NAME, GameType.ADVENTURE,
            LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, true),
            true, WorldDataConfiguration.DEFAULT,
        )
        mc.createWorldOpenFlows().createFreshLevel(NAME, settings, WorldOptions(0L, false, false), { provider ->
            val biomes = provider.lookupOrThrow(Registries.BIOME)
            val void = biomes.getOrThrow(Biomes.THE_VOID)
            val flat = FlatLevelGeneratorSettings(Optional.empty(), void, emptyList())
            WorldPresets.createFlatWorldDimensions(provider).replaceOverworldGenerator(provider, FlatLevelSource(flat))
        }, TitleScreen())
    }

    /** Called on the server once it runs: stamps which arena the world was built from. */
    fun markBuilt(worldDir: java.nio.file.Path) {
        EngineerClient.safely("p3sim marker") { Files.writeString(worldDir.resolve(MARKER), arenaVersion) }
    }
}
