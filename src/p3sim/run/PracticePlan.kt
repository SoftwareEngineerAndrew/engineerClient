package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.layouts.Layout
import com.engineerclient.p3sim.pack.layouts.LayoutPack
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.PlacementException
import com.engineerclient.p3sim.pack.rooms.RoomPack
import com.engineerclient.p3sim.pack.runstate.RunStatePack

/**
 * The dungeon of a practice run ([LayoutSource.Practice]): one room, taken as a recorded run had it (its build and the puzzle or trap
 * state Odin saw), moved to the middle of the map with nothing round it. It is entered through one of its recorded doorways (the one the
 * party came in by when a turn of the room allows, a puzzle's only door), turned to the room's south side on the map's middle line, so
 * every practice starts the same way: at the door, facing north. That doorway is the room's only one ([DungeonPlan.practice]); every other
 * side is a wall. Pure.
 */
object PracticePlan {
    /** A room that can be practised: its in-game name, type and shape, and in how many recorded runs it is. */
    class Choice(val name: String, val type: RoomType, val shape: String, val runs: Int)

    private val NOT_PRACTISED = setOf(RoomType.ENTRANCE, RoomType.BLOOD)

    /** The recorded layouts practice rooms come from: complete doors, not a duplicate, the floor. */
    private fun sources(pack: LayoutPack, floor: String): List<Layout> =
        pack.layouts.filter { it.floor == floor && it.doorsComplete && it.path != null && it.dupOf == null }

    /** Every room of the recorded runs but the entrance and blood, puzzles first, then by name. */
    fun choices(pack: LayoutPack, floor: String = "F7"): List<Choice> =
        sources(pack, floor).flatMap { it.rooms }.filter { RoomType.fromWord(it.type) !in NOT_PRACTISED }.groupBy { it.name }
            .map { (n, l) -> Choice(n, RoomType.fromWord(l[0].type), l[0].shape, l.size) }
            .sortedWith(compareBy({ it.type != RoomType.PUZZLE }, { it.name }))

    /**
     * The practice plan for [room]. The recorded run it comes from is drawn by [seed] among those that have the room (so the same seed gives the same
     * build and puzzle state); one that cannot be built is skipped.
     */
    fun build(room: String, pack: LayoutPack, seed: Long, rooms: RoomPack?, runstate: RunStatePack?, floor: String = "F7"): DungeonPlan.Result {
        val have = sources(pack, floor).filter { l -> l.rooms.any { it.name.equals(room, ignoreCase = true) } }
        if (have.isEmpty()) return DungeonPlan.Result.Failed(PlanFailure("No recorded $floor run has a room called $room, so it cannot be practised."))
        val start = java.util.Random(seed xor 0x9AC71CEL).nextInt(have.size)
        var last: String? = null
        for (i in have.indices) {
            val l = have[(start + i) % have.size]
            when (val r = from(l, room, seed, rooms, runstate)) {
                is DungeonPlan.Result.Ok -> return r
                is DungeonPlan.Result.Failed -> last = r.failure.message
            }
        }
        return DungeonPlan.Result.Failed(PlanFailure(last ?: "$room could not be built for practice."))
    }

    /** [room] of [layout], alone, turned so its way in is on its south side ([southFacing]) when it can be, else as recorded and centred. */
    fun from(layout: Layout, room: String, seed: Long, rooms: RoomPack?, runstate: RunStatePack?): DungeonPlan.Result {
        val full = when (val b = DungeonPlan.build(layout, seed, rooms, runstate)) {
            is DungeonPlan.Result.Failed -> return b
            is DungeonPlan.Result.Ok -> b.plan
        }
        val r = full.rooms.firstOrNull { it.name.equals(room, ignoreCase = true) }
            ?: return DungeonPlan.Result.Failed(PlanFailure("${layout.id} has no $room."))
        val doors = full.doors.filter { it.kind.connected && (it.roomA == r.index || it.roomB == r.index) }
            .sortedWith(compareBy(nullsLast<Int>()) { it.recordedOpen })
        if (doors.isEmpty()) return DungeonPlan.Result.Failed(PlanFailure("$room has no doorway in ${layout.id}."))
        fun sideOf(d: PlacedDoor) = if (d.roomA == r.index) d.a to (if (d.axis == 'E') 'E' else 'S') else d.b to (if (d.axis == 'E') 'W' else 'N')
        val turned = southFacing(r, doors.map { sideOf(it) })
        val (tiles, rot, door) = turned ?: run {
            // No turn of the room puts a doorway south: as recorded, centred (an odd width leans north-west).
            val (tile, side) = sideOf(doors.first())
            val minX = r.tiles.minOf { it.x }; val minZ = r.tiles.minOf { it.z }
            val w = r.tiles.maxOf { it.x } - minX + 1; val d = r.tiles.maxOf { it.z } - minZ + 1
            val dx = (DungeonFrame.TILES - w) / 2 - minX; val dz = (DungeonFrame.TILES - d) / 2 - minZ
            Triple(r.tiles.map { Tile(it.x + dx, it.z + dz) }.sortedWith(compareBy({ it.z }, { it.x })), r.rotation, PracticeStart(tile.x + dx, tile.z + dz, side))
        }
        val placement = r.design?.let {
            try { it.place(rot, tiles) } catch (e: PlacementException) { return DungeonPlan.Result.Failed(PlanFailure("$room cannot be placed for practice: ${e.message}")) }
        }
        val placed = PlacedRoom(
            0, r.name, r.family, r.designId, r.design, r.type, r.shape, tiles, rot, placement, r.secretsMax,
            null, null, null, null, r.layoutRoom, r.variantId,
        )
        val puzzles = if (r.type == RoomType.PUZZLE) listOf(r.name) else emptyList()
        return DungeonPlan.Result.Ok(DungeonPlan(
            "practice: ${r.name} (${layout.id})", layout.floor, seed, listOf(placed), emptyList(), listOf(0), emptyList(), puzzles,
            door,
        ))
    }

    /** Directions in quarter turns clockwise from south, as [Rot.q] counts them. */
    private const val SIDES = "SWNE"

    /**
     * [r] turned so that one of its doorways ([doors], world tile and side, the recorded way in first; then the design's other open ones) is on its south side, that doorway on the
     * map's middle line (the gap between tile rows 2 and 3, or as near as the room allows) and in tile column 2 or 3, whichever centres the room:
     * every practice is walked into from the same place, facing north. Only turns the room's design is placed in (its recorded rotations, and
     * the shape's law: a long room has two, a 2x2 one). Null when no allowed turn puts any of its doorways south.
     */
    private fun southFacing(r: PlacedRoom, doors: List<Pair<Tile, Char>>): Triple<List<Tile>, Rot, PracticeStart>? {
        val design = r.design ?: return null
        val now = r.placement ?: return null
        val cells = Placement.canonicalTiles(r.shape)
        val rots = listOf(r.rotation) + Rot.entries.filter { it != r.rotation }
        // The doorways in the room's own frame (canonical tile cell and side): the recorded ones, then its other open doorways.
        val recorded = doors.mapNotNull { (tile, side) ->
            cells.firstOrNull { now.worldTileOf(it.x, it.z) == tile }?.let { it to SIDES[Math.floorMod(SIDES.indexOf(side) - now.rot.q, 4)] }
        }
        val ways = (recorded + design.slots.filter { it.open }.map { it.tile to it.side }).distinct()
        for ((cell, canSide) in ways) {
            for (rot in rots) {
                if (SIDES[(SIDES.indexOf(canSide) + rot.q) % 4] != 'S') continue
                val at0 = try { design.place(rot, Placement.tilesFor(r.shape, rot, 0, 0)) } catch (e: PlacementException) { continue }
                val door0 = at0.worldTileOf(cell.x, cell.z)
                // The door's tile: as near the middle row as fits, then the column that centres the room.
                val best = (2 until DungeonFrame.TILES).flatMap { tz -> listOf(2, 3).map { tx -> Tile(tx, tz) } }.mapNotNull { at ->
                    val t = Placement.tilesFor(r.shape, rot, at.x - door0.x, at.z - door0.z)
                    if (t.any { it.x !in 0 until DungeonFrame.TILES || it.z !in 0 until DungeonFrame.TILES }) null
                    else Triple(at, t, Math.abs(t.sumOf { it.x + 0.5 } / t.size - DungeonFrame.TILES / 2.0))
                }.minWithOrNull(compareBy({ it.first.z }, { it.third })) ?: continue
                return Triple(best.second, rot, PracticeStart(best.first.x, best.first.z, 'S'))
            }
        }
        return null
    }
}
