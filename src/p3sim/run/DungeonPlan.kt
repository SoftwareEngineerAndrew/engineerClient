package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.DoorModel
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.pack.layouts.Layout
import com.engineerclient.p3sim.pack.layouts.LayoutKey
import com.engineerclient.p3sim.pack.layouts.LayoutRoom
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.PlacementException
import com.engineerclient.p3sim.pack.rooms.RoomDesign
import com.engineerclient.p3sim.pack.rooms.RoomPack
import com.engineerclient.p3sim.pack.runstate.RunStatePack

/** The room types of the packs (FORMAT-rooms 7.2). */
enum class RoomType {
    NORMAL, PUZZLE, RARE, YELLOW, TRAP, FAIRY, ENTRANCE, BLOOD;

    companion object {
        fun fromWord(w: String?): RoomType = entries.firstOrNull { it.name.equals(w, ignoreCase = true) } ?: NORMAL
    }
}

/**
 * One room of the dungeon being built: the layout's room, resolved to a concrete design and a placement. Immutable and
 * pure data, safe on any thread. [design] and [placement] are null only in headless tests that build a plan without a
 * room pack.
 */
class PlacedRoom(
    val index: Int,
    /** The in-game name, with spaces ("Blue Skulls"). */
    val name: String,
    val family: String,
    /** The rooms-pack design id actually placed (a build of the family). */
    val designId: String,
    val design: RoomDesign?,
    val type: RoomType,
    /** `1x1 1x2 1x3 1x4 2x2 L`. */
    val shape: String,
    /** World tiles, sorted row-major. */
    val tiles: List<Tile>,
    val rotation: Rot,
    val placement: Placement?,
    /** The room's secret maximum as the sim prints it (the run-state pack's, else the layout's, else the design's Odin count). */
    val secretsMax: Int,
    /** First-occurrence ticks of this room's check marks in the one recorded run (null = not observed). One sample, not a distribution. */
    val recordedNone: Int?,
    val recordedWhite: Int?,
    val recordedGreen: Int?,
    val recordedRed: Int?,
    /** The design the layout record named, before the builder's choice (null = the builder chose). */
    val layoutRoom: LayoutRoom?,
    /**
     * The id of the design's variant to build (a puzzle or trap state whose Odin core the recorded room had), or null for the
     * base volume. Build the room from `RoomPack.volumeFor(design, variantId)`: the base volume would give Odin a different core.
     */
    val variantId: String? = null,
) {
    /** How many of the 36 tile units this room counts for. */
    val tileCount: Int get() = tiles.size

    fun containsTile(t: Tile) = t in tiles
}

/** One door slot between two different rooms (FORMAT-layouts 6). */
class PlacedDoor(
    val index: Int,
    /** `'E'`: joins `(a)` and the tile east of it; `'S'`: joins `(a)` and the tile south of it. */
    val axis: Char,
    val a: Tile,
    val b: Tile,
    val roomA: Int,
    val roomB: Int,
    val kind: DoorKind,
    /** When the recorded run's door fell (ticks relative to T0), or null. */
    val recordedOpen: Int?,
) {
    /** World (x, z) of the door's centre. */
    val spot: Pair<Int, Int> get() = DungeonFrame.doorSpot(axis, a.x, a.z)

    /** The 36 cells of the door's body (3 x 4 x 3 around [spot]); a plain door or a wall has no blocks there but the cells are the same. */
    fun bodyCells(): List<com.engineerclient.p3sim.pack.Pos> = spot.let { (x, z) -> DoorModel.bodyCells(x, z) }

    /** Whether the block is inside this door's body volume. */
    fun contains(x: Int, y: Int, z: Int): Boolean = spot.let { (cx, cz) -> DoorModel.inBody(cx, cz, x, y, z) }

    /** The closed body block (coal, red terracotta, infested stone), or null when the door has no body. */
    val bodyBlock: String? get() = DoorModel.bodyBlock(kind)

    /** Whether opening it costs a key: a WITHER or BLOOD door (the FAIRY door is free, the ENTRANCE door falls by itself). */
    val needsKey: Boolean get() = kind == DoorKind.WITHER || kind == DoorKind.BLOOD
}

/** Why a layout could not be turned into a plan. [message] is plain English for the player. */
class PlanFailure(val message: String)

/**
 * The dungeon of one run, decided and immutable: rooms with designs and placements, doors with their final kinds, the
 * entrance -> blood path. The layout module builds the world from it; `RunState` is created from it; every module reads it.
 * Pure data: no Minecraft types (a [Placement] is pure too).
 */
class DungeonPlan(
    val layoutId: String,
    val floor: String,
    val seed: Long,
    val rooms: List<PlacedRoom>,
    val doors: List<PlacedDoor>,
    /** Room indices entrance -> blood along connected doors. */
    val path: List<Int>,
    /** The Wither/Blood key stands seen in the recorded run (informational; the key plan is computed from the doors). */
    val recordedKeys: List<LayoutKey>,
    val puzzleNames: List<String>,
) {
    private val tileRoom: Map<Tile, PlacedRoom> = buildMap { for (r in rooms) for (t in r.tiles) put(t, r) }
    private val doorBySlot: Map<Triple<Char, Int, Int>, PlacedDoor> = doors.associateBy { Triple(it.axis, it.a.x, it.a.z) }

    fun roomAt(tx: Int, tz: Int): PlacedRoom? = tileRoom[Tile(tx, tz)]

    /** The room a world position stands in, or null in a gap column, outside the dungeon, or in an empty tile. */
    fun roomAtBlock(x: Double, z: Double): PlacedRoom? = DungeonFrame.tileOf(x, z)?.let { tileRoom[it] }

    fun doorAt(axis: Char, tx: Int, tz: Int): PlacedDoor? = doorBySlot[Triple(axis, tx, tz)]

    /** The door whose body volume contains the block (any kind, plain doorways included), or null. */
    fun doorContaining(x: Int, y: Int, z: Int): PlacedDoor? = doors.firstOrNull { it.contains(x, y, z) }

    /** Every door slot between two rooms: a room with several tiles can touch another through more than one slot (only one of them is a doorway). */
    fun doorsBetween(roomA: Int, roomB: Int): List<PlacedDoor> = doors.filter { (it.roomA == roomA && it.roomB == roomB) || (it.roomA == roomB && it.roomB == roomA) }

    /**
     * The door between two rooms: the CONNECTED slot when there is one (a multi-tile room touches its neighbour through several
     * slots and in a complete layout exactly one is a doorway, FORMAT-layouts 6.2), else the first slot (a wall), else null.
     */
    fun doorBetween(roomA: Int, roomB: Int): PlacedDoor? = doorsBetween(roomA, roomB).let { l -> l.firstOrNull { it.kind.connected } ?: l.firstOrNull() }

    val entrance: PlacedRoom? get() = rooms.firstOrNull { it.type == RoomType.ENTRANCE }
    val blood: PlacedRoom? get() = rooms.firstOrNull { it.type == RoomType.BLOOD }
    val fairy: PlacedRoom? get() = rooms.firstOrNull { it.type == RoomType.FAIRY }

    /** Doors that cost a wither key (paid), in path order where on the path. */
    val paidWitherDoors: List<PlacedDoor> get() = doors.filter { it.kind == DoorKind.WITHER }

    /** The doors on the entrance -> blood path, in path order. */
    val pathDoors: List<PlacedDoor> get() = path.zipWithNext().mapNotNull { (a, b) -> doorBetween(a, b)?.takeIf { it.kind.connected } }

    /** Total tile units (36 for a full map, fewer if the layout leaves tiles empty). */
    val tileUnits: Int get() = rooms.sumOf { it.tileCount }

    /** Sum of the rooms' secret maxima: the team total the bars print. */
    val secretsTotal: Int get() = rooms.sumOf { it.secretsMax }

    companion object {
        /**
         * Resolves a recorded [layout] into a plan. Design choice for a room: the layout's `design`; else the design its
         * `core` names in the rooms pack; else one build of its family, chosen deterministically from [seed] and the room
         * index (so the same seed always gives the same builds). Without a [rooms] pack, rooms carry no design (headless
         * tests). Secrets maxima: [runstate] first, then the layout, then the design's Odin count, else 0.
         *
         * Returns a [PlanFailure] (never throws) when a room has no rotation, no design, or fails the placement law.
         */
        fun build(layout: Layout, seed: Long, rooms: RoomPack?, runstate: RunStatePack? = null): Result {
            val placed = ArrayList<PlacedRoom>()
            for (r in layout.rooms) {
                val rot = r.rot ?: return Result.Failed(PlanFailure("${layout.id}: the room ${r.name} has no known rotation, so this layout cannot be built."))
                var design: RoomDesign? = null
                var designId = r.design ?: r.family
                var variantId: String? = null
                if (rooms != null) {
                    design = r.design?.let { rooms.design(it) }
                    if (design == null && r.core != null) design = rooms.designByCore(r.core)?.let { rooms.design(it.design) }
                    if (design == null) {
                        val builds = rooms.designsOfFamily(r.family).sortedBy { it.id }
                        if (builds.isNotEmpty()) design = builds[kotlin.random.Random(seed xor (r.i.toLong() * 0x9E3779B97F4A7C15uL.toLong())).nextInt(builds.size)]
                    }
                    if (design == null) return Result.Failed(PlanFailure("${layout.id}: the room library has no ${r.name} (${r.family}), so this layout cannot be built."))
                    designId = design.id
                }
                // The recorded Odin core says which variant (puzzle state, trap build) the room had; Odin names the room by that core.
                if (design != null && r.core != null) variantId = rooms?.designByCore(r.core)?.takeIf { it.design == design.id }?.variant
                var placement: Placement? = null
                if (design != null) {
                    placement = try { design.place(rot, r.tiles) } catch (e: PlacementException) {
                        return Result.Failed(PlanFailure("${layout.id}: ${r.name} cannot be placed: ${e.message}"))
                    }
                }
                val secrets = runstate?.room(r.name)?.secretsMax ?: r.secretsMax ?: design?.odinSecrets ?: 0
                placed += PlacedRoom(
                    r.i, r.name, r.family, designId, design, RoomType.fromWord(r.type), r.shape, r.tiles.sortedWith(compareBy({ it.z }, { it.x })),
                    placement?.rot ?: rot, placement, secrets, r.tNone, r.tWhite, r.tGreen, r.tRed, r, variantId,
                )
            }
            val indexed = placed.sortedBy { it.index }
            val doors = layout.doors.mapIndexed { i, d ->
                PlacedDoor(i, d.axis, d.a, d.b, d.ra, d.rb, d.kind, d.tOpen)
            }
            val path = layout.path
            if (!layout.doorsComplete || path == null) {
                return Result.Failed(PlanFailure("${layout.id}: its doors were not fully recorded, so this layout is not playable."))
            }
            return Result.Ok(DungeonPlan(layout.id, layout.floor, seed, indexed, doors, path, layout.keys, layout.puzzles))
        }
    }

    /** What [build] answers. */
    sealed interface Result {
        class Ok(val plan: DungeonPlan) : Result
        class Failed(val failure: PlanFailure) : Result
    }
}
