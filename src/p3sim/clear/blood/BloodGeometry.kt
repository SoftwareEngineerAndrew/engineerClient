package com.engineerclient.p3sim.clear.blood

import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.RoomVolume
import com.engineerclient.p3sim.pack.rooms.WorldPos
import com.engineerclient.p3sim.run.plugin.HeadSlot

/**
 * Where things stand in a blood room build (pure, tested). Every Blood build of the rooms pack has the same fixed layout, so
 * nothing is searched for: the 30 wall-head stations and the exit alcove sit at the same canonical cells in all of them (recorded in
 * 515 rooms: each station was seen in 448 to 470 of them and no other position more than 4 times). Only the floor height is read
 * from the room's own blocks, and a room with no floor in the middle has no stations.
 *
 * Canonical frame: the room is 31 by 31 blocks, the blood door is on the north wall (z 0), the middle column is (15, 15) and the
 * floor a player stands on is y 69. [Placement.toWorld] turns it into the world for each of the four rotations.
 */
object BloodGeometry {
    const val ROWS = 3
    const val PER_ROW = 10

    /** Station offsets from the room's middle column, in the canonical frame (x, z). The same ten in every row. */
    val STATION_OFFSETS: List<IntArray> = listOf(
        intArrayOf(-12, -4), intArrayOf(-12, 0), intArrayOf(-12, 4),
        intArrayOf(12, -4), intArrayOf(12, 0), intArrayOf(12, 4),
        intArrayOf(-9, -12), intArrayOf(9, -12), intArrayOf(-9, 12), intArrayOf(9, 12),
    )

    /** The block cell of each row's heads, above the floor a player stands on (the stand itself is [STAND_ABOVE_CELL] higher: y 71.75, 75.75, 79.75). */
    val ROW_RISES = intArrayOf(2, 6, 10)

    /** How far above its cell's floor a head stand's feet are (recorded 71.75 over cell 71). */
    const val STAND_ABOVE_CELL = 0.75

    /** The exit alcove at the room's back wall: a portal 3 wide and 4 tall in the plane z = [EXIT_Z], with the lightning strike on the middle column. */
    const val EXIT_Z = 29
    const val PORTAL_X_MIN = 14
    const val PORTAL_X_MAX = 16
    const val PORTAL_HEIGHT = 4
    const val STRIKE_X = 15

    /** A canonical-frame head slot: the cell the head's stand is in, and the way it faces (toward the room's middle). */
    data class CanonSlot(val x: Int, val y: Int, val z: Int, val facing: String)

    /** The canonical middle column of the room's footprint. */
    fun centre(vol: RoomVolume): Pair<Int, Int> = (vol.w / 2) to (vol.d / 2)

    /**
     * The y a player stands at in the middle of the room: the first solid cell going up the middle column that has three open cells
     * above it, plus one. Null if the column has no such floor.
     */
    fun standY(vol: RoomVolume): Int? {
        val (cx, cz) = centre(vol)
        for (y in vol.y0 until vol.y0 + vol.h - 3) {
            if (!vol.isAir(cx, y, cz) && vol.isAir(cx, y + 1, cz) && vol.isAir(cx, y + 2, cz) && vol.isAir(cx, y + 3, cz)) return y + 1
        }
        return null
    }

    /** The head slots of a room, in room order (row by row, ten to a row), in the canonical frame. Empty if the room has no floor. */
    fun canonicalSlots(vol: RoomVolume): List<CanonSlot> {
        val stand = standY(vol) ?: return emptyList()
        val (cx, cz) = centre(vol)
        val out = ArrayList<CanonSlot>(ROWS * PER_ROW)
        for (rise in ROW_RISES) for ((ox, oz) in STATION_OFFSETS.map { it[0] to it[1] }) {
            out += CanonSlot(cx + ox, stand + rise, cz + oz, facingTowardMiddle(ox, oz))
        }
        return out
    }

    /** The way a station on the wall looks into the room: along x for the side walls, along z for the front and back walls. */
    private fun facingTowardMiddle(ox: Int, oz: Int): String = when {
        ox < 0 && Math.abs(ox) >= Math.abs(oz) -> "east"
        ox > 0 && Math.abs(ox) >= Math.abs(oz) -> "west"
        oz < 0 -> "south"
        else -> "north"
    }

    /** Turns a canonical facing word by the placement's clockwise quarter turns (north, east, south, west). */
    fun rotateFacing(facing: String, quarterTurns: Int): String {
        val order = listOf("north", "east", "south", "west")
        val i = order.indexOf(facing)
        if (i < 0) return facing
        return order[Math.floorMod(i + quarterTurns, 4)]
    }

    /** The slots in the world frame, numbered from 0 in room order. */
    fun worldSlots(vol: RoomVolume, place: Placement): List<HeadSlot> =
        canonicalSlots(vol).mapIndexed { i, s ->
            val w = place.toWorld(s.x, s.y, s.z)
            HeadSlot(i, w.x, w.y, w.z, rotateFacing(s.facing, place.quarterTurns))
        }

    /** World (x, z) of the room's middle column and the y a player stands at there; null if the room has no floor in the middle. */
    fun worldCentre(vol: RoomVolume, place: Placement): Triple<Double, Double, Double>? {
        val y = standY(vol) ?: return null
        val (cx, cz) = centre(vol)
        val w = place.toWorld(cx, y, cz)
        return Triple(w.x + 0.5, w.y.toDouble(), w.z + 0.5)
    }

    /**
     * The exit alcove in the world: the 12 cells of the portal, the portal's axis, the lightning's strike point, and the box a body
     * must overlap to start the jump. [floorY] is the y a player stands at (the portal's lowest cell).
     */
    class ExitSite(
        val cells: List<WorldPos>,
        /** True when the portal plane runs along x (SOUTH and NORTH rooms), false for z (WEST and EAST). */
        val axisX: Boolean,
        val strikeX: Double, val strikeY: Double, val strikeZ: Double,
    ) {
        val minX = cells.minOf { it.x }.toDouble()
        val maxX = cells.maxOf { it.x } + 1.0
        val minY = cells.minOf { it.y }.toDouble()
        val maxY = cells.maxOf { it.y } + 1.0
        val minZ = cells.minOf { it.z }.toDouble()
        val maxZ = cells.maxOf { it.z } + 1.0

        /** Whether a body standing with its feet at ([x], [y], [z]) overlaps the portal blocks (a player is 0.6 wide and 1.8 tall). */
        fun touches(x: Double, y: Double, z: Double): Boolean =
            x + HALF_WIDTH > minX && x - HALF_WIDTH < maxX &&
                y + HEIGHT > minY && y < maxY &&
                z + HALF_WIDTH > minZ && z - HALF_WIDTH < maxZ

        companion object {
            const val HALF_WIDTH = 0.3
            const val HEIGHT = 1.8
        }
    }

    /** The exit alcove of a room placed as [place] whose floor is [floorY]. */
    fun exitSite(place: Placement, floorY: Int): ExitSite {
        val cells = ArrayList<WorldPos>(3 * PORTAL_HEIGHT)
        for (dy in 0 until PORTAL_HEIGHT) for (x in PORTAL_X_MIN..PORTAL_X_MAX) cells += place.toWorld(x, floorY + dy, EXIT_Z)
        val s = place.toWorld(STRIKE_X, floorY, EXIT_Z)
        return ExitSite(cells, place.quarterTurns % 2 == 0, s.x + 0.5, floorY + 0.5, s.z + 0.5)
    }
}
