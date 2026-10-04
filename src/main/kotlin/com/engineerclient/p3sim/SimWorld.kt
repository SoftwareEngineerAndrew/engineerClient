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
 * server you are on first. It is made fresh on every open, so nothing changed ever carries over.
 */
object SimWorld {
    const val NAME = "p3sim"
    /** Written into the world folder: which arena it was built from. */
    private const val MARKER = "p3sim-arena.txt"
    /** Bump when the world itself must be made again (not just the arena data). */
    private const val WORLD_VERSION = 2

    /** The arena build the jar carries (a world made from another one is rebuilt). */
    val arenaVersion: String by lazy {
        val crc = CRC32()
        for (f in listOf("arena.bin", "arena-ext.bin", "arena-fixes.json")) Arena::class.java.getResourceAsStream("/assets/engineerclient/p3sim/$f")?.use { crc.update(it.readAllBytes()) }
        java.lang.Long.toHexString(crc.value) + "-" + WORLD_VERSION
    }

    /** Leaves whatever world or server you are in, then opens (or makes) the sim world. */
    fun open() {
        mc.execute {
            if (P3Sim.inSim) return@execute
            if (mc.level != null) mc.disconnectFromWorld(Component.literal("Opening P3 Sim"))
            opening = true
            EngineerClient.safely("p3sim open") { openOrCreate() }
        }
    }

    /**
     * Made fresh every time: a saved world keeps whatever an earlier session (or a crash before
     * the restore on leaving) left changed, so the arena is always built again from [Arena].
     */
    private fun openOrCreate() {
        val source = mc.levelSource
        if (Files.isDirectory(source.baseDir.resolve(NAME))) source.createAccess(NAME).use { it.deleteLevel() }
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
            opening = true
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
    /** The mod is opening (or making) the sim world right now. */
    @Volatile var opening = false

    fun isBuilt(worldDir: java.nio.file.Path) = Files.exists(worldDir.resolve(MARKER))

    fun markBuilt(worldDir: java.nio.file.Path) {
        EngineerClient.safely("p3sim marker") { Files.writeString(worldDir.resolve(MARKER), arenaVersion) }
    }
}
