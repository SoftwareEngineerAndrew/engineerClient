package com.engineerclient.p3sim.pack

import com.engineerclient.p3sim.pack.layouts.LayoutPack
import com.engineerclient.p3sim.pack.mobs.MobPack
import com.engineerclient.p3sim.pack.rooms.RoomPack
import com.engineerclient.p3sim.pack.runstate.RunStatePack
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * The four data packs of one load (BRIEF decision 7: the jar ships no recorded data; everything is read at runtime from
 * `<gameDir>/config/engineerclient/ads/packs/`). Any pack may be null; [report] says why. A missing pack is a message to the
 * player ([userLines]), never a crash.
 */
class PackSet(
    val dir: Path,
    val rooms: RoomPack?,
    val layouts: LayoutPack?,
    val mobs: MobPack?,
    val runstate: RunStatePack?,
    val report: PackReport,
) : java.io.Closeable {
    /** What a full run needs at the least: the room library and the layout pool. Mobs and run-state tables degrade gracefully. */
    val canBuildDungeon: Boolean get() = rooms != null && layouts != null

    /** Names of the packs that did not load, for messages. */
    fun missing(): List<String> = buildList {
        if (rooms == null) add("room library (${PackFiles.ROOMS_INDEX} + ${PackFiles.ROOMS_BLOCKS})")
        if (layouts == null) add("layout pool (${PackFiles.LAYOUTS})")
        if (mobs == null) add("mob tables (${PackFiles.MOBS})")
        if (runstate == null) add("run-state tables (${PackFiles.RUNSTATE})")
    }

    /** The plain-English chat lines for the player: what is missing and where to put it, plus any loader warning. Empty when everything loaded cleanly. */
    fun userLines(): List<String> = Packs.userLines(this)

    private var pins = 0
    private var retired = false

    /**
     * A running [com.engineerclient.p3sim.run.Run] pins the set it was started with: the chunk generator's worker threads read room
     * volumes from it for as long as the run lasts, so a reload (`/ads dev packs`, starting another run) must not close its file
     * under them. Pair every [pin] with one [unpin].
     */
    @Synchronized fun pin() { pins++ }

    /** Releases one [pin]; a set that was retired meanwhile is closed when the last pin goes. */
    @Synchronized fun unpin() { pins--; if (pins <= 0 && retired) close() }

    /** [Packs] no longer uses this set: closes it now, or when the last run using it ends. */
    @Synchronized fun retire() { retired = true; if (pins <= 0) close() }

    override fun close() { rooms?.close() }
}

/**
 * Loads and holds the packs. Loading reads a few MB, so [loadAsync] is for a run start; [load] blocks. The loaders never
 * throw; [Packs.userLines] turns what they found into chat text. Server and client threads may both read [current].
 */
object Packs {
    @Volatile var current: PackSet? = null
        private set

    /** Loads all four packs from [dir] (default: the game's packs folder), makes the result [current] and retires the previous one (closed once no run uses it). */
    @Synchronized fun load(dir: Path = PackFiles.defaultDir()): PackSet {
        val report = PackReport()
        val rooms: RoomPack?; val layouts: LayoutPack?; val mobs: MobPack?; val runstate: RunStatePack?
        if (!Files.isDirectory(dir)) {
            report.error("The ADS data pack folder $dir does not exist.")
            rooms = null; layouts = null; mobs = null; runstate = null
        } else {
            rooms = RoomPack.load(dir, report)
            layouts = LayoutPack.load(dir, rooms, report)
            mobs = MobPack.load(dir, report)
            runstate = RunStatePack.load(dir, report)
        }
        val set = PackSet(dir, rooms, layouts, mobs, runstate, report)
        val old = current
        current = set
        if (old != null && old !== set) old.retire()
        return set
    }

    /** [load] on a worker thread. */
    fun loadAsync(dir: Path = PackFiles.defaultDir()): CompletableFuture<PackSet> = CompletableFuture.supplyAsync { load(dir) }

    /** The loaded set, or loads once if nothing has been loaded yet. */
    fun ensureLoaded(): PackSet = current ?: load()

    /** Forgets the current packs and frees their file handles. */
    fun unload() { val c = current; current = null; c?.retire() }

    /** The message when the folder or the packs are not there at all. */
    fun missingMessage(dir: Path): String =
        "ADS has no data packs yet. Put ${PackFiles.ROOMS_INDEX}, ${PackFiles.ROOMS_BLOCKS}, ${PackFiles.LAYOUTS}, ${PackFiles.MOBS} and ${PackFiles.RUNSTATE} in $dir to play a full run. The boss practice works without them."

    /** See [PackSet.userLines]. */
    fun userLines(set: PackSet): List<String> {
        val lines = ArrayList<String>()
        val missing = set.missing()
        if (missing.size == 4) return listOf(missingMessage(set.dir))
        if (missing.isNotEmpty()) lines += "ADS could not load the " + missing.joinToString(", ") + " from ${set.dir}. " +
            (if (!set.canBuildDungeon) "A full run needs the room library and the layout pool." else "The run goes on with built-in defaults for what is missing.")
        for (e in set.report.errors()) if (lines.none { it.contains(e) }) lines += e
        for (w in set.report.warnings()) lines += w
        return lines
    }
}
