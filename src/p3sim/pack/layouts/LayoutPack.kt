package com.engineerclient.p3sim.pack.layouts

import com.engineerclient.p3sim.pack.*
import com.engineerclient.p3sim.pack.rooms.RoomPack
import com.engineerclient.p3sim.pack.rooms.checkHeader
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/*
 * The "Layout pool" data pack, version 1 (FORMAT-layouts.md): recorded dungeon layouts to replay. Rooms are placed by
 * the rooms pack's law from (design, rotation, tiles); doors come with their final kinds and the entrance -> blood path.
 * Filled by the skeleton, owned by the layout work item afterwards.
 */

/** FORMAT-layouts 6.1: the final door kind. `FAIRY` is the free wither door into the Fairy room (no key, no chat line). */
enum class DoorKind {
    NONE, NORMAL, WITHER, BLOOD, ENTRANCE, FAIRY, UNKNOWN;

    /** A doorway exists (kind is not `none` and not `unknown`). */
    val connected: Boolean get() = this != NONE && this != UNKNOWN

    companion object {
        fun fromWord(w: String?): DoorKind = entries.firstOrNull { it.name.equals(w, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * One room of a recorded layout (FORMAT-layouts 5.1). `design` null means the family has several builds and nothing
 * picked one: the builder picks one deterministically from the seed (FORMAT-layouts 11.4). `rot` null means unresolved:
 * the layout cannot be built (refuse with a chat message, offer another).
 *
 * The `t*` fields are the FIRST tick (relative to the run's `t0`, 10-tick resolution) the room showed that check mark in
 * the one recorded run; null = not observed. They are one sample, not a distribution.
 */
data class LayoutRoom(
    val i: Int,
    val name: String,
    val family: String,
    val design: String?,
    val designBy: String?,
    val core: Int?,
    /** `normal puzzle rare yellow trap fairy entrance blood`. */
    val type: String,
    /** `1x1 1x2 1x3 1x4 2x2 L`. */
    val shape: String,
    val tiles: List<Tile>,
    val rot: Rot?,
    val rotSource: String?,
    val rotOdin: String?,
    val secretsMax: Int?,
    val secretsFound: Int?,
    val tSeen: Int?,
    val tNone: Int?,
    val tWhite: Int?,
    val tGreen: Int?,
    val tRed: Int?,
)

/** FORMAT-layouts 6.1. `a` is the west (E) or north (S) tile, `b` the other; `(axis, a)` is the slot id. */
data class LayoutDoor(
    val axis: Char, val a: Tile, val b: Tile, val ra: Int, val rb: Int,
    val read: String?, val kind: DoorKind, val fix: Boolean,
    /** Tick (relative to `t0`) the door started falling in the recorded run; null for plain doors and doors never seen to fall. */
    val tOpen: Int?,
) {
    /** World (x, z) of the door's centre. */
    val spot: Pair<Int, Int> get() = DungeonFrame.doorSpot(axis, a.x, a.z)
}

/** FORMAT-layouts 8: a Wither or Blood Key stand seen in the recorded run. `room` is the room it came from. */
data class LayoutKey(val kind: String, val tSpawn: Int?, val x: Double, val z: Double, val room: Int?, val tPickup: Int?, val tGone: Int?)

/** One recorded layout (FORMAT-layouts 5). */
class Layout(
    val id: String,
    /** `F7` or `M7` (v1 builds F7 only). */
    val floor: String,
    val date: String?,
    val source: String?,
    val rooms: List<LayoutRoom>,
    val doors: List<LayoutDoor>,
    val doorsComplete: Boolean,
    /** Room indices entrance -> blood along connected doors; null unless [doorsComplete]. */
    val path: List<Int>?,
    val keys: List<LayoutKey>,
    /** Names of the rooms with `type == "puzzle"`, in `rooms` order. */
    val puzzles: List<String>,
    val secretsTotal: Int,
    val t0Basis: String?,
    val endTick: Int?,
    /** Class names of the recorded party (`MAGE ARCHER ...`); no names. */
    val party: List<String>,
    val buildable: Boolean?,
    val playable: Boolean?,
    val dupOf: String?,
    val rotSummary: Map<String, Int>,
) {
    private val tileRoom: Map<Tile, LayoutRoom> = buildMap { for (r in rooms) for (t in r.tiles) put(t, r) }
    private val doorBySlot: Map<Triple<Char, Int, Int>, LayoutDoor> = doors.associateBy { Triple(it.axis, it.a.x, it.a.z) }

    /** The room that covers world tile (tx, tz); null for an empty tile. */
    fun roomAt(tx: Int, tz: Int): LayoutRoom? = tileRoom[Tile(tx, tz)]

    /** The door record for slot (axis, tx, tz); null when the slot separates a room from itself or has no record (FORMAT-layouts 6.2). */
    fun doorAt(axis: Char, tx: Int, tz: Int): LayoutDoor? = doorBySlot[Triple(axis, tx, tz)]

    /** World (x, z) of a slot's centre (FORMAT-layouts 2). */
    fun doorSpot(axis: Char, tx: Int, tz: Int): Pair<Int, Int> = DungeonFrame.doorSpot(axis, tx, tz)

    /** The entrance room, the blood room and the Fairy room (null if the layout lacks one). */
    val entrance: LayoutRoom? get() = rooms.firstOrNull { it.type == "entrance" }
    val blood: LayoutRoom? get() = rooms.firstOrNull { it.type == "blood" }
    val fairy: LayoutRoom? get() = rooms.firstOrNull { it.type == "fairy" }

    /** The default pool of FORMAT-layouts 9: playable, not a duplicate, F7. */
    val inDefaultPool: Boolean get() = playable == true && dupOf == null && floor == "F7"
}

/** The loaded layout pool. */
class LayoutPack internal constructor(
    val version: Int,
    val roomsPackVersion: Int?,
    val aliases: Map<String, String>,
    val stats: JsonObject?,
    /** Sorted by id ascending (string order). */
    val layouts: List<Layout>,
) {
    private val byId = layouts.associateBy { it.id }

    fun layout(id: String): Layout? = byId[id]

    /** The family a room name belongs to (this pack's `aliases`). */
    fun familyOf(name: String): String? = aliases[name.replace("​", "")]

    /** The layouts that pass [filter], in id order. */
    fun pool(filter: (Layout) -> Boolean = DEFAULT_FILTER): List<Layout> = layouts.filter(filter)

    /**
     * The seed's layout: `kotlin.random.Random(seed)` picks an index into the filtered list sorted by id, so the same
     * seed and the same pack give the same layout (FORMAT-layouts 11.2). Null when the pool is empty.
     */
    fun pick(seed: Long, filter: (Layout) -> Boolean = DEFAULT_FILTER): Layout? {
        val pool = pool(filter)
        if (pool.isEmpty()) return null
        return pool[kotlin.random.Random(seed).nextInt(pool.size)]
    }

    companion object {
        const val FORMAT = "ads-layouts"
        const val SUPPORTED_VERSION = 1

        /** FORMAT-layouts 9: `playable && dupOf == null && floor == "F7"`. */
        val DEFAULT_FILTER: (Layout) -> Boolean = { it.inDefaultPool }

        /**
         * Loads `layouts.json.gz` from [dir]. Null, with the reason in [report], when it is missing, unreadable or the
         * wrong format/version. [roomPack]: when given, a different `roomsPackVersion` is a warning, not a failure.
         * Never throws.
         */
        fun load(dir: Path, roomPack: RoomPack? = null, report: PackReport = PackReport()): LayoutPack? {
            val path = dir.resolve(PackFiles.LAYOUTS)
            if (!Files.isRegularFile(path)) { report.error("The layout pool ${PackFiles.LAYOUTS} is missing from $dir."); return null }
            val root = try { Files.newInputStream(path).use { readGzipJson(it) } } catch (e: Exception) {
                report.error("The layout pool ${PackFiles.LAYOUTS} is unreadable: ${e.message}"); return null
            }
            return fromJson(root, roomPack, report)
        }

        /** Builds the pool from a parsed file (tests, tools). */
        fun fromJson(root: JsonObject, roomPack: RoomPack? = null, report: PackReport = PackReport()): LayoutPack? {
            if (!checkHeader(root, FORMAT, SUPPORTED_VERSION, PackFiles.LAYOUTS, report)) return null
            val rv = root.int("roomsPackVersion")
            if (rv != null && roomPack != null && rv != roomPack.version) {
                report.warn("The layout pool was made for room library version $rv but version ${roomPack.version} is loaded. Layouts may not match their rooms.")
            }
            val layouts = ArrayList<Layout>()
            for (o in root.arr("layouts").objects()) {
                try { layouts += parseLayout(o) } catch (e: Exception) { report.warn("A layout was skipped (${o.str("id") ?: "no id"}): ${e.message}") }
            }
            layouts.sortBy { it.id }
            if (layouts.isEmpty()) report.warn("The layout pool has no layouts.")
            val aliases = root.obj("aliases")?.entrySet()?.mapNotNull { (k, v) -> v.asStr()?.let { k.replace("​", "") to it } }?.toMap().orEmpty()
            return LayoutPack(root.int("version") ?: 1, rv, aliases, root.obj("stats"), layouts)
        }

        private fun parseLayout(o: JsonObject): Layout {
            val id = o.str("id") ?: throw PackFormatException("no id")
            val rooms = o.arr("rooms").objects().map { r ->
                LayoutRoom(
                    i = r.int("i") ?: throw PackFormatException("a room has no index"),
                    name = (r.str("name") ?: "").replace("​", ""),
                    family = r.str("family") ?: (r.str("name") ?: "").replace(' ', '_'),
                    design = r.str("design"),
                    designBy = r.str("designBy"),
                    core = r.int("core"),
                    type = r.str("type") ?: "normal",
                    shape = r.str("shape") ?: "1x1",
                    tiles = r.get("tiles").asTiles(),
                    rot = Rot.fromWord(r.str("rot")),
                    rotSource = r.str("rotSource"),
                    rotOdin = r.str("rotOdin"),
                    secretsMax = r.int("secretsMax"),
                    secretsFound = r.int("secretsFound"),
                    tSeen = r.int("tSeen"), tNone = r.int("tNone"), tWhite = r.int("tWhite"), tGreen = r.int("tGreen"), tRed = r.int("tRed"),
                )
            }.sortedBy { it.i }
            val doors = o.arr("doors").objects().mapNotNull { d ->
                val axis = d.str("axis")?.firstOrNull() ?: return@mapNotNull null
                LayoutDoor(axis, d.get("a").asTile() ?: return@mapNotNull null, d.get("b").asTile() ?: return@mapNotNull null,
                    d.int("ra") ?: -1, d.int("rb") ?: -1, d.str("read"), DoorKind.fromWord(d.str("kind")), d.bool("fix") ?: false, d.int("tOpen"))
            }
            return Layout(
                id = id,
                floor = o.str("floor") ?: "F7",
                date = o.str("date"),
                source = o.str("source"),
                rooms = rooms,
                doors = doors,
                doorsComplete = o.bool("doorsComplete") ?: false,
                path = o.arr("path")?.ints()?.toList(),
                keys = o.arr("keys").objects().map { k ->
                    LayoutKey(k.str("kind") ?: "wither", k.int("tSpawn"), k.dbl("x") ?: 0.0, k.dbl("z") ?: 0.0, k.int("room"), k.int("tPickup"), k.int("tGone"))
                },
                puzzles = o.arr("puzzles").strings(),
                secretsTotal = o.int("secretsTotal") ?: rooms.sumOf { it.secretsMax ?: 0 },
                t0Basis = o.str("t0Basis"),
                endTick = o.int("endTick"),
                party = o.arr("party").strings(),
                buildable = o.bool("buildable"),
                playable = o.bool("playable"),
                dupOf = o.str("dupOf"),
                rotSummary = o.obj("rotSummary")?.entrySet()?.mapNotNull { (k, v) -> v.asIntOrNull()?.let { k to it } }?.toMap().orEmpty(),
            )
        }
    }
}
