package com.engineerclient.mappred

/**
 * What differs between floors (F and M alike) for Room Prediction:
 *
 *  - the main grid: F1 4x5, F2-F4 5x5, F5-F6 5x6, F7 6x6 (tile x, z from the dungeon's north-west corner)
 *  - on F4-F6 the column east of it, the puzzle column, holds only 1x1 dead ends (puzzles), each behind one
 *    door from the west
 *  - the lower floors don't have every room ([missingNames])
 */
class FloorRules(
    val floor: Int,
    val w: Int,
    val h: Int,
    val puzzleColumn: Boolean,
    val missingNames: Set<String>,
) {
    companion object {
        val F1 = FloorRules(1, 4, 5, false, FloorStats.MISSING_1)
        val F2 = FloorRules(2, 5, 5, false, FloorStats.MISSING_2)
        val F3 = FloorRules(3, 5, 5, false, FloorStats.MISSING_3)
        val F4 = FloorRules(4, 5, 5, true, FloorStats.MISSING_4)
        val F5 = FloorRules(5, 5, 6, true, FloorStats.MISSING_5)
        val F6 = FloorRules(6, 5, 6, true, FloorStats.MISSING_6)
        val F7 = FloorRules(7, 6, 6, false, emptySet())

        /** The rules for floor [floor] (1-7, F or M); null for the entrance floor. */
        fun of(floor: Int): FloorRules? = when (floor) {
            1 -> F1; 2 -> F2; 3 -> F3; 4 -> F4; 5 -> F5; 6 -> F6; 7 -> F7
            else -> null
        }
    }
}
