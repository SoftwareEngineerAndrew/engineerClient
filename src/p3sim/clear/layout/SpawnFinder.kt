package com.engineerclient.p3sim.clear.layout

import com.engineerclient.p3sim.pack.DungeonFrame

/** Where a player can stand in a pasted room: feet position (block centre), as world coordinates. */
data class StandSpot(val x: Double, val y: Double, val z: Double)

/**
 * Finds a place to stand in a room: the open floor nearest a tile's centre. Pure; reads only the pasted [RoomInstance].
 * The dev commands use it to put the player in a room they just built (the sim's own lobby is in the boss arena).
 */
object SpawnFinder {
    /** Floors of the rooms' playable level lie in this band (the door band is y 69 to 72 with its threshold at 68). */
    const val SEARCH_TOP = 76
    const val SEARCH_BOTTOM = 62
    /** Feet height on the floor of the door band (threshold y 68). */
    const val MAIN_LEVEL = 69
    /** How far from the tile centre to look, in blocks. */
    const val RADIUS = 13

    /** The y a player's feet would stand at in column ([x], [z]): solid below, two free blocks above; the door level (feet at 69) first, then upward, then downward. Null if none. */
    fun standY(room: RoomInstance, x: Int, z: Int, onlyMainLevel: Boolean = false): Int? {
        val levels = if (onlyMainLevel) listOf(MAIN_LEVEL) else listOf(MAIN_LEVEL) + (MAIN_LEVEL + 1..SEARCH_TOP) + (MAIN_LEVEL - 1 downTo SEARCH_BOTTOM)
        for (y in levels) {
            if (room.isSolid(x, y - 1, z) && !room.isSolid(x, y, z) && !room.isSolid(x, y + 1, z)) return y
        }
        return null
    }

    /** The spot nearest the centre of the room's tile ([tx], [tz]) that has a floor and head room, or null for a room with none within [RADIUS]. */
    fun find(room: RoomInstance, tx: Int, tz: Int): StandSpot? =
        findAt(room, tx, tz, onlyMainLevel = true) ?: findAt(room, tx, tz, onlyMainLevel = false)

    private fun findAt(room: RoomInstance, tx: Int, tz: Int, onlyMainLevel: Boolean): StandSpot? {
        val cx = DungeonFrame.tileCentre(tx); val cz = DungeonFrame.tileCentre(tz)
        for (r in 0..RADIUS) {
            // The ring of cells at Chebyshev distance r, nearest first within a ring by Euclid.
            val ring = ArrayList<Pair<Int, Int>>()
            for (dx in -r..r) for (dz in -r..r) if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) == r) ring += dx to dz
            ring.sortBy { it.first * it.first + it.second * it.second }
            for ((dx, dz) in ring) {
                val y = standY(room, cx + dx, cz + dz, onlyMainLevel) ?: continue
                // One free block of margin on each side so the player is not wedged in a corner.
                if (listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (ax, az) -> room.isSolid(cx + dx + ax, y, cz + dz + az) }) continue
                return StandSpot(cx + dx + 0.5, y.toDouble(), cz + dz + 0.5)
            }
        }
        return null
    }
}
