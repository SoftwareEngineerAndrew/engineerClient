package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.layouts.Layout
import com.engineerclient.p3sim.pack.layouts.LayoutPack
import com.engineerclient.p3sim.pack.rooms.PlacementException
import com.engineerclient.p3sim.pack.rooms.RoomPack
import com.engineerclient.p3sim.pack.runstate.RunStatePack

/**
 * The dungeon of a practice run ([LayoutSource.Practice]): one room, taken as a recorded run had it (its build, turn, and the puzzle or
 * trap state Odin saw), moved to the middle of the map with nothing round it. It is entered through the doorway the recorded party came
 * in by (the room's door that fell first; a puzzle's only door). That doorway is the room's only one ([DungeonPlan.practice]); every
 * other side is a wall. Pure.
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

    /** [room] of [layout], alone and centred. */
    fun from(layout: Layout, room: String, seed: Long, rooms: RoomPack?, runstate: RunStatePack?): DungeonPlan.Result {
        val full = when (val b = DungeonPlan.build(layout, seed, rooms, runstate)) {
            is DungeonPlan.Result.Failed -> return b
            is DungeonPlan.Result.Ok -> b.plan
        }
        val r = full.rooms.firstOrNull { it.name.equals(room, ignoreCase = true) }
            ?: return DungeonPlan.Result.Failed(PlanFailure("${layout.id} has no $room."))
        val entry = full.doors.filter { it.kind.connected && (it.roomA == r.index || it.roomB == r.index) }
            .minWithOrNull(compareBy(nullsLast<Int>()) { it.recordedOpen })
            ?: return DungeonPlan.Result.Failed(PlanFailure("$room has no doorway in ${layout.id}."))
        val (tile, side) = if (entry.roomA == r.index) entry.a to (if (entry.axis == 'E') 'E' else 'S') else entry.b to (if (entry.axis == 'E') 'W' else 'N')
        // Centred: the room's box in the middle of the 6 x 6 tiles (an odd width leans north-west).
        val minX = r.tiles.minOf { it.x }; val minZ = r.tiles.minOf { it.z }
        val w = r.tiles.maxOf { it.x } - minX + 1; val d = r.tiles.maxOf { it.z } - minZ + 1
        val dx = (DungeonFrame.TILES - w) / 2 - minX; val dz = (DungeonFrame.TILES - d) / 2 - minZ
        val tiles = r.tiles.map { Tile(it.x + dx, it.z + dz) }.sortedWith(compareBy({ it.z }, { it.x }))
        val placement = r.design?.let {
            try { it.place(r.rotation, tiles) } catch (e: PlacementException) { return DungeonPlan.Result.Failed(PlanFailure("$room cannot be placed for practice: ${e.message}")) }
        }
        val placed = PlacedRoom(
            0, r.name, r.family, r.designId, r.design, r.type, r.shape, tiles, r.rotation, placement, r.secretsMax,
            null, null, null, null, r.layoutRoom, r.variantId,
        )
        val puzzles = if (r.type == RoomType.PUZZLE) listOf(r.name) else emptyList()
        return DungeonPlan.Result.Ok(DungeonPlan(
            "practice: ${r.name} (${layout.id})", layout.floor, seed, listOf(placed), emptyList(), listOf(0), emptyList(), puzzles,
            PracticeStart(tile.x + dx, tile.z + dz, side),
        ))
    }
}
