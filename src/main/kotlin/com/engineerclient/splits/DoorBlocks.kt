package com.engineerclient.splits

import kotlin.math.abs

/**
 * Finding dungeon doors in a burst of block changes — pure maths, so it tests without the game.
 *
 * A wither or blood door is 36 blocks. When it starts falling they all turn to barrier on one tick,
 * and when it is down those barriers all turn to air on one tick; nothing else in a run changes that
 * many at once. Where the blocks are says which two map tiles the door joins: tiles are 32 blocks
 * apart with the first centred at -185, and a door sits halfway between two.
 */
object DoorBlocks {

    /** Fewer blocks than this changing together is not a door. */
    const val DOOR_BLOCKS = 30

    /** A door: the two map tiles (x, z) either side of it. */
    data class Door(val a: Pair<Int, Int>, val b: Pair<Int, Int>)

    /**
     * The doors among one tick's changed blocks, given as (x, z). Two can fall on the same tick —
     * at the start, fairy's and the one out of Entrance — so the blocks are split into doors first.
     * Doors are at least 16 blocks apart.
     */
    fun doors(blocks: List<Pair<Int, Int>>): List<Door> {
        val groups = mutableListOf<MutableList<Pair<Int, Int>>>()
        for (p in blocks) {
            val g = groups.firstOrNull { abs(it[0].first - p.first) <= 4 && abs(it[0].second - p.second) <= 4 }
            if (g != null) g += p else groups += mutableListOf(p)
        }
        return groups.mapNotNull { g ->
            if (g.size < DOOR_BLOCKS) return@mapNotNull null
            val tx = (g.sumOf { it.first }.toDouble() / g.size + 185) / 32
            val tz = (g.sumOf { it.second }.toDouble() / g.size + 185) / 32
            if (abs(tx - Math.round(tx)) < 0.1 && abs(tz - Math.round(tz)) < 0.1) return@mapNotNull null // not between two tiles
            Door(
                Math.floor(tx + 0.01).toInt() to Math.floor(tz + 0.01).toInt(),
                Math.ceil(tx - 0.01).toInt() to Math.ceil(tz - 0.01).toInt(),
            )
        }
    }
}
