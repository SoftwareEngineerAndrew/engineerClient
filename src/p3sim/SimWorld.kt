package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.p3sim.run.WorldEnv
import net.minecraft.SharedConstants
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackType
import net.minecraft.world.Difficulty
import net.minecraft.world.level.DataPackConfig
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.levelgen.FlatLevelSource
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.level.levelgen.structure.StructureSet
import java.nio.file.Files
import java.util.Optional
import java.util.zip.CRC32

/**
 * The singleplayer world "p3sim": an empty world the generator fills with the F7 boss arena
 * ([Arena.fill]) and, in a full run, the dungeon. Opening it from anywhere (title screen button, `/p3sim`, the keybind) leaves the
 * server you are on first. It is made fresh on every open, so nothing changed ever carries over.
 *
 * The world is Hypixel's dungeon world in shape: the overworld dimension type at 0 to 255 with no sky light ([WorldEnv]), plains everywhere
 * (the biome Hypixel's dungeon chunks carry), and no structures.
 */
object SimWorld {
    const val NAME = "p3sim"
    /** Written into the world folder: which arena it was built from. */
    private const val MARKER = "p3sim-arena.txt"
    /** Bump when the world itself must be made again (not just the arena data). 3: the world's own data pack ([WorldEnv]) and the plains biome. */
    private const val WORLD_VERSION = 3

    /** The arena build the jar carries (a world made from another one is rebuilt). */
    val arenaVersion: String by lazy {
        val crc = CRC32()
        for (f in listOf("arena.bin", "arena-ext.bin", "arena-fixes.json")) Arena::class.java.getResourceAsStream("/assets/engineerclient/p3sim/$f")?.use { crc.update(it.readAllBytes()) }
        java.lang.Long.toHexString(crc.value) + "-" + WORLD_VERSION
    }

    /**
     * Leaves whatever world or server you are in, then opens (or makes) the sim world. [forRun]: the full-run launcher ([com.engineerclient.p3sim.run.RunLauncher])
     * opens it for a run it has registered the regions for; any other open drops a launch that was never finished.
     */
    fun open(forRun: Boolean = false) {
        if (!forRun) com.engineerclient.p3sim.run.RunLauncher.abort()
        mc.execute {
            if (P3Sim.inSim) return@execute
            if (mc.level != null) mc.disconnectFromWorld(Component.literal("Opening ADS"))
            opening = true
            try { openOrCreate() } catch (t: Throwable) { failed("open", t) }
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
            mc.disconnectFromWorld(Component.literal("Leaving ADS"))
            mc.gui.setScreen(TitleScreen())
        }
    }

    /** Deletes the world and makes it again (`/p3sim rebuild`); [forRun] as for [open]. */
    fun rebuild(forRun: Boolean = false) {
        if (!forRun) com.engineerclient.p3sim.run.RunLauncher.abort()
        mc.execute {
            // A party's world is the host's: only they can rebuild it.
            if (P3Sim.inSim && !P3Sim.localSim) { mc.player?.sendSystemMessage(Component.literal("§8[§6ADS§8] §7Only the host can rebuild the world.")); return@execute }
            if (mc.level != null) mc.disconnectFromWorld(Component.literal("Rebuilding ADS"))
            opening = true
            try {
                mc.levelSource.createAccess(NAME).use { it.deleteLevel() }
                create()
            } catch (t: Throwable) { failed("rebuild", t) }
        }
    }

    private fun create() {
        mc.gui.setScreen(GenericMessageScreen(Component.literal(if (com.engineerclient.p3sim.run.RunLauncher.pending != null) "Building the dungeon..." else "Building the F7 boss...")))
        // Hypixel's sky: 0 to 255 and no sky light. Without the pack (it could not be written) the world is a vanilla overworld, as it was before.
        val envPack = installEnvPack()
        val dataConfiguration = if (!envPack) WorldDataConfiguration.DEFAULT
            else WorldDataConfiguration(DataPackConfig(listOf("vanilla", WorldEnv.PACK_ID), emptyList()), WorldDataConfiguration.DEFAULT.enabledFeatures())
        val settings = LevelSettings(
            // SURVIVAL from the login on (Hypixel sends ADVENTURE at login, then SURVIVAL; Fight.setup sets it too).
            NAME, GameType.SURVIVAL,
            // PEACEFUL for the boss practice; a full run is on NORMAL (its mobs are real; the player is kept unhurtable by the sim).
            LevelSettings.DifficultySettings(if (com.engineerclient.p3sim.run.RunLauncher.pending != null) Difficulty.NORMAL else Difficulty.PEACEFUL, false, true),
            true, dataConfiguration,
        )
        mc.createWorldOpenFlows().createFreshLevel(NAME, settings, WorldOptions(0L, false, false), { provider ->
            val biomes = provider.lookupOrThrow(Registries.BIOME)
            // Plains, as Hypixel's dungeon chunks are. No structure sets at all: left unset, a flat world uses every set the biome allows, and plains
            // would grow villages and mineshafts into the dungeon.
            val plains = biomes.getOrThrow(Biomes.PLAINS)
            val flat = FlatLevelGeneratorSettings(Optional.of<HolderSet<StructureSet>>(HolderSet.empty()), plains, emptyList())
            provider.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions().replaceOverworldGenerator(provider, FlatLevelSource(flat))
        }, TitleScreen())
    }

    /**
     * Writes the world's own data pack ([WorldEnv]) into the (just deleted) world folder, made from the game's own overworld dimension type. False
     * when it could not be written; the world is then built without it.
     */
    private fun installEnvPack(): Boolean = try {
        val vanilla = SimWorld::class.java.getResourceAsStream("/data/minecraft/dimension_type/overworld.json")?.use { it.readAllBytes().toString(Charsets.UTF_8) }
            ?: error("the game's overworld dimension type was not found")
        val format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA)
        WorldEnv.installPack(mc.levelSource.baseDir.resolve(NAME), vanilla, format.major(), format.minor())
        true
    } catch (t: Throwable) {
        EngineerClient.logger.warn("[ec] ADS world data pack not written; the world keeps the vanilla sky", t)
        false
    }

    /** Opening or rebuilding failed (the p3sim folder locked, say): back to the title screen, not left on the message screen. */
    private fun failed(what: String, t: Throwable) {
        EngineerClient.logger.error("[ec] p3sim $what failed", t)
        opening = false
        com.engineerclient.p3sim.run.RunLauncher.abort()
        mc.gui.setScreen(TitleScreen())
    }

    /** The mod is opening (or making) the sim world right now. */
    @Volatile var opening = false

    /** Built by the mod from the arena this jar carries: one from another build (opened from the world list) isn't the sim's. */
    fun isBuilt(worldDir: java.nio.file.Path) = runCatching { Files.readString(worldDir.resolve(MARKER)).trim() == arenaVersion }.getOrDefault(false)

    /** Called on the server once it runs: stamps which arena the world was built from. */
    fun markBuilt(worldDir: java.nio.file.Path) {
        EngineerClient.safely("p3sim marker") { Files.writeString(worldDir.resolve(MARKER), arenaVersion) }
    }
}
