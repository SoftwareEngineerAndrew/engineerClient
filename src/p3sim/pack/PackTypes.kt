package com.engineerclient.p3sim.pack

import java.nio.file.Path

/*
 * Small value types shared by every data pack, and the report the loaders write their messages into.
 * Nothing here touches Minecraft, so the whole pack package runs in headless unit tests.
 */

/** A tile cell `(x, z)`: a world tile `(tx, tz)` in 0..5, or a canonical tile `(ix, iz)` of a room. */
data class Tile(val x: Int, val z: Int)

/** An integer block position `(x, y, z)`. In the rooms pack it is canonical room-frame (x, z) with absolute y. */
data class Pos(val x: Int, val y: Int, val z: Int)

/**
 * Odin's rotation words. [q] is the number of clockwise quarter turns from the canonical (SOUTH) frame that the rooms
 * pack's placement law uses (FORMAT-rooms 4.1).
 */
enum class Rot(val q: Int) {
    SOUTH(0), WEST(1), NORTH(2), EAST(3);

    companion object {
        /** The rotation for an Odin/ADR word (any case); null for anything else, including null. */
        fun fromWord(word: String?): Rot? = word?.let { w -> entries.firstOrNull { it.name.equals(w, ignoreCase = true) } }
    }
}

/**
 * What a loader has to say. Loaders never throw to their caller and never talk to the chat themselves (they run
 * off-thread and in unit tests): they put plain-English lines here, and [Packs] shows them to the player.
 */
class PackReport {
    private val lines = ArrayList<Line>()

    class Line(val level: Level, val text: String)
    enum class Level { INFO, WARNING, ERROR }

    @Synchronized fun info(text: String) { lines += Line(Level.INFO, text) }
    @Synchronized fun warn(text: String) { lines += Line(Level.WARNING, text) }
    @Synchronized fun error(text: String) { lines += Line(Level.ERROR, text) }

    @Synchronized fun all(): List<Line> = lines.toList()
    @Synchronized fun errors(): List<String> = lines.filter { it.level == Level.ERROR }.map { it.text }
    @Synchronized fun warnings(): List<String> = lines.filter { it.level == Level.WARNING }.map { it.text }
    @Synchronized fun hasErrors(): Boolean = lines.any { it.level == Level.ERROR }
}

/** Where the data packs live and what they are called. The jar bundles them; a copy in the packs folder overrides the bundled one, file by file. */
object PackFiles {
    const val ROOMS_INDEX = "rooms.index.json.gz"
    const val ROOMS_BLOCKS = "rooms.blocks.bin"
    const val LAYOUTS = "layouts.json.gz"
    const val MOBS = "mobs.json.gz"
    const val RUNSTATE = "runstate.json.gz"
    const val TRAPS = "traps.json"

    /** The six files bundled in the jar (the room library is two files). A pack rebuild replaces exactly these (`tools/bundle-ads-packs.sh`). */
    val BUNDLED: List<String> = listOf(ROOMS_INDEX, ROOMS_BLOCKS, LAYOUTS, MOBS, RUNSTATE, TRAPS)

    /** Where the bundled files sit inside the jar. */
    const val RESOURCE_ROOT = "assets/engineerclient/ads/packs"

    /** The folder the bundled files are unpacked into, next to the override folder: `<gameDir>/config/engineerclient/ads/packs-bundled/`; one sub-folder per content hash. */
    fun cacheRootFor(packsDir: Path): Path = packsDir.resolveSibling("packs-bundled")

    /** The packs folder under a game directory: `<gameDir>/config/engineerclient/ads/packs/`. */
    fun dirFor(gameDir: Path): Path = gameDir.resolve("config").resolve("engineerclient").resolve("ads").resolve("packs")

    /**
     * The packs folder of the running game. Reads the Fabric loader's game directory; only call this where the game
     * is running (tests pass a folder to the loaders directly).
     */
    fun defaultDir(): Path = dirFor(net.fabricmc.loader.api.FabricLoader.getInstance().gameDir)
}

/** Thrown inside the pack decoders for a malformed file; the loaders catch it and turn it into a [PackReport] line. */
class PackFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
