package com.engineerclient.p3sim.clear.layout

import com.engineerclient.p3sim.pack.DoorModel
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.RoomDesign
import com.engineerclient.p3sim.pack.rooms.RoomVolume

/** One room that is pasted: its design, the volume to paste (base or a variant) and where it stands. */
class RoomInstance(val design: RoomDesign, val volume: RoomVolume, val placement: Placement) {
    /** The canonical state string of the block at a WORLD cell, or null when the cell is outside the room's box or air. */
    fun stateAt(x: Int, y: Int, z: Int): String? {
        val (cx, cz) = placement.toCanonical(x, z) ?: return null
        if (!volume.contains(cx, y, cz)) return null
        return volume.stateAt(cx, y, cz).takeIf { it != RoomVolume.AIR }
    }

    fun isSolid(x: Int, y: Int, z: Int): Boolean = stateAt(x, y, z) != null
}

/**
 * Turns a layout's doors into block edits on top of the pasted rooms: Hypixel pastes every room as a sealed box and carves its
 * doorways afterwards, and the recorded rooms arrive in whatever state they were captured (a doorway open, a wall, or a door shut
 * in it). For every side of every room:
 *
 *  - the layout has a doorway there and the room's side is a wall: the 3 x 4 opening is CARVED through the wall to air;
 *  - the layout has no doorway and the room's side is open: the room's own `seal` blocks are written, then anything still air in
 *    the first two planes of the opening is filled with the wall block (the recorded seals barely change the opening);
 *  - the layout has no doorway and the side is a wall that holds a closed door of a recorded run (all 12 cells coal, infested stone
 *    or red terracotta): the door is scrubbed to wall, so no door stands where the map has none.
 *
 * Between two rooms a doorway gets one frame style over its three core planes ([DoorFrames]), bedrock under it, and a wither, fairy, blood or entrance door its closed body
 * ([DoorModel]). The rule is pure so it can be tested without a world: the result is a list of [Edit]s in the order they apply.
 *
 * ACCEPTED SIMPLIFICATION: [seal] fills the first two planes of an unconnected open side, where Hypixel leaves a recess (air) beside the
 * wall on about 6 of 38 such sides (39 of 2,736 cells, 1.4 percent). A recess behind a sealed door is not visible from the dungeon.
 *
 * UNMEASURED: how deep a wall is. Recorded walls are mostly two blocks ([CARVE_DEPTH]); a wall that is still solid behind that is
 * carved up to [CARVE_MAX_DEPTH]. A room with a solid mass behind a door side gets a short dead-end there.
 */
object DoorPlanner {
    /** Planes of the opening carved through a wall (the tunnel is two deep in 234 of 500 recorded walls). */
    const val CARVE_DEPTH = 2
    /** The deepest a wall is carved while the plane just carved was still nearly solid. */
    const val CARVE_MAX_DEPTH = 4
    /** Solid cells (of 12) in a plane that make it a wall to carry on carving. */
    const val WALL_PLANE_CELLS = 10
    /** Planes at a room's edge that must not be air when the side is a wall. */
    const val SEAL_DEPTH = 2

    /** Blocks that mean "a door of a recorded run is standing here" when all 12 cells of the opening are one of them. */
    private fun isDoorBody(state: String): Boolean {
        val n = state.substringBefore('[')
        return n == "minecraft:coal_block" || n == "minecraft:red_terracotta" || n.startsWith("minecraft:infested_")
    }

    private val NOT_A_WALL = listOf(
        "stairs", "slab", "wall", "fence", "bars", "pane", "door", "trapdoor", "carpet", "lantern", "button", "lever", "sign", "banner",
        "skull", "head", "torch", "vine", "chain", "rail", "plate", "ladder", "flower", "pot", "bed", "chest", "air", "glass", "leaves",
        "grass", "fern", "bush", "candle", "cobweb", "web", "fire", "lava", "water", "barrier", "light", "hopper", "dispenser", "dropper",
        "anvil", "cauldron", "lectern", "brewing", "skeleton", "zombie", "creeper", "piglin", "dragon",
    )

    private fun looksLikeWall(state: String): Boolean {
        val n = state.substringBefore('[').removePrefix("minecraft:")
        return NOT_A_WALL.none { n.contains(it) }
    }

    /**
     * The block a wall at this side is built of: the most common wall-like block in the frame columns and the wall to each side of the
     * opening (plane 0, y 69 to 72), else [DoorGeometry.FRAME_BLOCK].
     */
    fun wallBlock(room: RoomInstance, tx: Int, tz: Int, side: Char): String {
        val counts = HashMap<String, Int>()
        for (a in (DoorGeometry.FRAME_MIN - 5)..(DoorGeometry.FRAME_MAX + 5)) {
            if (a in DoorGeometry.OPEN_MIN..DoorGeometry.OPEN_MAX) continue
            val (x, z) = DoorGeometry.sideCell(tx, tz, side, a, 0)
            for (y in DoorModel.BODY_Y_MIN..DoorModel.BODY_Y_MAX) {
                val s = room.stateAt(x, y, z)?.substringBefore('[') ?: continue
                if (looksLikeWall(s) && !isDoorBody(s)) counts.merge(s, 1, Int::plus)
            }
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key ?: DoorGeometry.FRAME_BLOCK
    }

    /** The floor block (y 68) a room has at a world cell, when it is a plain full block; null otherwise. */
    private fun floorBlock(rooms: List<RoomInstance>, x: Int, z: Int): String? {
        val s = rooms.firstNotNullOfOrNull { it.stateAt(x, DoorModel.THRESHOLD_Y, z) } ?: return null
        return s.takeIf { looksLikeWall(it) && !isDoorBody(it) }
    }

    /** How many of the 12 opening cells of plane [depth] are solid in the room. */
    fun solidCells(room: RoomInstance, tx: Int, tz: Int, side: Char, depth: Int): Int =
        DoorGeometry.openingCells(tx, tz, side, depth).count { (x, y, z) -> room.isSolid(x, y, z) }

    /** How many planes to carve through the wall on this side: [CARVE_DEPTH], more while the last plane carved is still a wall. */
    fun carveDepth(room: RoomInstance, tx: Int, tz: Int, side: Char): Int {
        var d = CARVE_DEPTH
        while (d < CARVE_MAX_DEPTH && solidCells(room, tx, tz, side, d - 1) >= WALL_PLANE_CELLS) d++
        return d
    }

    /**
     * The edits that turn the pasted [rooms] into the layout's doors. [doors] maps a slot to its final kind; a slot that is absent,
     * `NONE` or `UNKNOWN` is a wall. Edits come in application order: room sides first, then the gap frames, then the door bodies.
     */
    fun plan(rooms: List<RoomInstance>, doors: Map<Slot, DoorKind>, seed: Long = 0L): List<Edit> {
        val sides = ArrayList<Edit>()
        for (room in rooms) {
            val p = room.placement
            for (slot in room.design.slots) {
                val wt = p.worldTileOf(slot.tile.x, slot.tile.z)
                val side = DoorGeometry.turn(slot.side, p.quarterTurns)
                val gap = DoorGeometry.slotOf(wt.x, wt.z, side)
                val connected = gap != null && doors[gap]?.connected == true
                if (connected) {
                    if (!slot.open) carve(room, wt.x, wt.z, side, sides)
                } else if (slot.open) {
                    seal(room, slot.seal?.cells.orEmpty(), wt.x, wt.z, side, sides)
                } else {
                    scrub(room, wt.x, wt.z, side, sides)
                }
            }
        }
        val frames = ArrayList<Edit>()
        val bodies = ArrayList<Edit>()
        for ((slot, kind) in doors.entries.sortedWith(compareBy({ it.key.axis }, { it.key.tz }, { it.key.tx }))) {
            if (!kind.connected) continue
            frames += DoorFrames.frame(slot, DoorFrames.styleFor(kind, slot, seed)) { x, z -> floorBlock(rooms, x, z) }
            bodies += DoorGeometry.bodyEdits(slot, kind)
        }
        return sides + frames + bodies
    }

    private fun carve(room: RoomInstance, tx: Int, tz: Int, side: Char, out: MutableList<Edit>) {
        val depth = carveDepth(room, tx, tz, side)
        for (d in 0 until depth) for ((x, y, z) in DoorGeometry.openingCells(tx, tz, side, d)) {
            if (room.isSolid(x, y, z)) out += Edit(x, y, z, AIR)
        }
    }

    private fun seal(room: RoomInstance, cells: List<com.engineerclient.p3sim.pack.rooms.SealCell>, tx: Int, tz: Int, side: Char, out: MutableList<Edit>) {
        val p = room.placement
        val written = HashSet<Triple<Int, Int, Int>>()
        for (c in cells) {
            val w = p.toWorld(c.pos.x, c.pos.y, c.pos.z)
            out += Edit(w.x, w.y, w.z, c.state, p.quarterTurns)
            if (c.state != AIR) written += Triple(w.x, w.y, w.z)
        }
        val wall = wallBlock(room, tx, tz, side)
        for (d in 0 until SEAL_DEPTH) for (cell in DoorGeometry.openingCells(tx, tz, side, d)) {
            if (cell !in written && !room.isSolid(cell.first, cell.second, cell.third)) out += Edit(cell.first, cell.second, cell.third, wall)
        }
    }

    private fun scrub(room: RoomInstance, tx: Int, tz: Int, side: Char, out: MutableList<Edit>) {
        val cells = DoorGeometry.openingCells(tx, tz, side, 0)
        val first = cells.firstNotNullOfOrNull { (x, y, z) -> room.stateAt(x, y, z) } ?: return
        if (!isDoorBody(first)) return
        if (cells.any { (x, y, z) -> room.stateAt(x, y, z) != first }) return
        val wall = wallBlock(room, tx, tz, side)
        for ((x, y, z) in cells) out += Edit(x, y, z, wall)
    }

    private const val AIR = "minecraft:air"
}
