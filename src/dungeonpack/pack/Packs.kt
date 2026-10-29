package com.engineerclient.dungeonpack.pack

import com.engineerclient.dungeonpack.pack.layouts.LayoutPack
import com.engineerclient.dungeonpack.pack.mobs.MobPack
import com.engineerclient.dungeonpack.pack.rooms.RoomPack
import com.engineerclient.dungeonpack.pack.runstate.RunStatePack
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * The data packs of one load (BRIEF decision 7: the packs are public and ship inside the jar; a file of the same name in
 * `<gameDir>/config/engineerclient/ads/packs/` overrides the bundled one, for development). Any pack may be null; [report] says why. A
 * missing or damaged pack is a message to the player ([userLines]), never a crash.
 */
class PackSet(
    /** The override folder (it may not exist; the bundled packs do not need it). */
    val dir: Path,
    val rooms: RoomPack?,
    val layouts: LayoutPack?,
    val mobs: MobPack?,
    val runstate: RunStatePack?,
    val report: PackReport,
    /** Where each file came from. A set built by hand (tests) reads every file from [dir]. */
    val source: PackSource = PackSource.ofDir(dir),
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

    /** The plain-English chat lines for the player: what is missing or damaged, plus any loader warning. Empty when everything loaded cleanly. */
    fun userLines(): List<String> = Packs.userLines(this)

    /** One line per pack file saying which copy was used: the jar's, the override folder's, or none. For `/ads dev packs`. */
    fun originLines(): List<String> = PackFiles.BUNDLED.map { name ->
        name + ": " + when (source.origin(name)) {
            PackOrigin.JAR -> "the copy bundled in the jar"
            PackOrigin.OVERRIDE -> "your copy in $dir (overrides the jar)"
            null -> "not found"
        }
    }

    private var pins = 0
    private var retired = false

    /**
     * A running dungeon pins the set it was started with: the chunk generator's worker threads read room
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
 *
 * Order per file: the copy in the override folder if there is one (development; logged once), else the copy bundled in the jar,
 * unpacked once into `packs-bundled/<content hash>/` next to the override folder ([PackSource]).
 */
object Packs {
    private val log = org.slf4j.LoggerFactory.getLogger("engineerclient")
    private val loggedOverrides = HashSet<String>()

    @Volatile var current: PackSet? = null
        private set

    /**
     * Loads all the packs ([dir]: the override folder, default the game's packs folder; [bundled]: the jar's files; [cacheRoot]: where
     * they are unpacked, default beside [dir]), makes the result [current] and retires the previous one (closed once no run uses it).
     */
    @Synchronized fun load(dir: Path = PackFiles.defaultDir(), bundled: BundledPacks = BundledPacks.JAR, cacheRoot: Path? = null): PackSet {
        val report = PackReport()
        val source = try {
            PackSource.resolve(dir, bundled, cacheRoot ?: PackFiles.cacheRootFor(dir), report)
        } catch (t: Throwable) {
            report.error("ADS could not look for its data packs: ${t.message ?: t.javaClass.simpleName}")
            PackSource.ofDir(dir)
        }
        for (name in PackFiles.BUNDLED) {
            if (source.origin(name) == PackOrigin.OVERRIDE && loggedOverrides.add("$dir/$name")) {
                log.info("[ads] using your copy of {} in {} instead of the one bundled in the jar", name, dir)
                report.info("Using your copy of $name from $dir instead of the bundled one.")
            }
        }
        val rooms = RoomPack.load(source, report)
        val layouts = LayoutPack.load(source, rooms, report)
        val mobs = MobPack.load(source, report)
        val runstate = RunStatePack.load(source, report)
        val set = PackSet(dir, rooms, layouts, mobs, runstate, report, source)
        val old = current
        current = set
        if (old != null && old !== set) old.retire()
        return set
    }

    /** [load] on a worker thread. */
    fun loadAsync(dir: Path = PackFiles.defaultDir(), bundled: BundledPacks = BundledPacks.JAR, cacheRoot: Path? = null): CompletableFuture<PackSet> =
        CompletableFuture.supplyAsync { load(dir, bundled, cacheRoot) }

    /** The loaded set, or loads once if nothing has been loaded yet. */
    fun ensureLoaded(): PackSet = current ?: load()

    /** Forgets the current packs and frees their file handles. */
    fun unload() { val c = current; current = null; c?.retire() }

    /**
     * The message when no pack could be loaded at all. That is rare: the packs are bundled in the jar, so it means this copy of the mod
     * lacks them (a failed unpack has its own, [unpackFailedMessage]), and no override copy is in [dir].
     */
    fun missingMessage(dir: Path): String =
        "ADS could not load its data packs. They are built into the Engineer Client jar (${PackFiles.ROOMS_INDEX}, ${PackFiles.ROOMS_BLOCKS}, ${PackFiles.LAYOUTS}, ${PackFiles.MOBS}, ${PackFiles.RUNSTATE}), " +
            "so this copy of the mod is damaged or incomplete; installing it again should fix that. While developing you can put your own copies in $dir instead. The boss practice works without them."

    /**
     * The message when no pack could be loaded because the bundled files could not be written out of the jar into [folder] (a folder
     * that is read-only, a full disk). Installing the mod again would not help; the error line after it says what the file system said.
     */
    fun unpackFailedMessage(folder: Path, dir: Path): String =
        "ADS could not load its data packs. They are built into the Engineer Client jar, but copying them out into $folder failed. " +
            "While developing you can put your own copies in $dir instead. The boss practice works without them."

    /** See [PackSet.userLines]. */
    fun userLines(set: PackSet): List<String> {
        val lines = ArrayList<String>()
        val missing = set.missing()
        val unpackFailedIn = set.source.unpackFailedIn
        if (missing.size == 4) return listOf(if (unpackFailedIn != null) unpackFailedMessage(unpackFailedIn, set.dir) else missingMessage(set.dir)) +
            set.report.errors().filter { !it.contains("missing from") }
        if (missing.isNotEmpty()) lines += "ADS could not load the " + missing.joinToString(", ") + ". " +
            (if (!set.canBuildDungeon) "A full run needs the room library and the layout pool." else "The run goes on with built-in defaults for what is missing.")
        for (e in set.report.errors()) if (lines.none { it.contains(e) }) lines += e
        for (w in set.report.warnings()) lines += w
        return lines
    }
}
