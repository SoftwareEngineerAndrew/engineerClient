package com.engineerclient.mappred

/**
 * What one floor's maps follow (F and M alike), from the recorded maps on ADR (tools/mappred floors.mjs).
 * Every floor keeps F7's rules ([MapStats]: one door tree, specials are the 1x1 dead ends, the wither path
 * and the fairy on it); what differs is here:
 *
 *  - the main grid: F1 4x5, F2-F4 5x5, F5-F6 5x6, F7 6x6 (tile x, z from the dungeon's north-west corner)
 *  - on F4-F6 the column east of it, the puzzle column, holds only 1x1 dead ends (puzzles), each behind one
 *    door from a normal room west of it (a big room, or a 1x1 with two doors already). They top the map's
 *    dead ends (puzzles, trap, yellow) up to [deadEndTarget]: as many as the main grid is short of, and none
 *    when the tab list's puzzles alone go past it
 *  - the big rooms ([combos]); no trap room on F1-F2
 *  - the wither path walks 7 or 8 steps with the fairy 3 before blood on F1-F4, 9 or 10 and 4 on F5-F7
 *  - the lower floors don't have every room ([missingNames])
 *  - the tab list's puzzles in their order on the map, column by column ([puzzleOrder]; F7 only so far)
 */
class FloorRules(
    val floor: Int,
    val w: Int,
    val h: Int,
    val puzzleColumn: Boolean,
    val deadEndTarget: Int,
    val trap: Boolean,
    val fairyBeforeBlood: Int,
    /** Big room shape counts (1x2, 1x3, 1x4, 2x2, L) and how many maps had them. */
    val combos: Array<IntArray>,
    /** Weight of the wither path's walk length (tile steps, entrance to blood). */
    val walk: FloatArray,
    /** Weight of blood's distance from the entrance (tiles, |dx| + |dz|). */
    val bloodFromEntrance: FloatArray,
    /** Weight of the fairy's distance from the entrance (tiles). */
    val fairyFromEntrance: FloatArray,
    /** Weight of a big room covering each tile, the map turned so the entrance is on the top row, left half (F7's 6x6 only). */
    val bigTile: FloatArray?,
    val missingNames: Set<String>,
    /** The tab lists the puzzles in their order on the map (x, then z), so a named one fixes how many come before it. */
    val puzzleOrder: Boolean = false,
    /** Each 2x2 block of 1x1 rooms weighs a map by e^bunch: the 1x1s bunch up, the more the fewer dead ends (puzzles) a map has. */
    val bunch: Double = 0.0,
    /**
     * Each big shape's own weight per orientation ([MapPredictor]'s placements, in order) and top-left tile
     * (z * 6 + x, the map's own directions, not turned to the entrance), on top of [bigTile] (F7's 6x6 only).
     */
    val anchorWeights: Array<Array<FloatArray>>? = null,
    /**
     * How many of the main grid's cells (scaled to the unknown ones) are kept free of big rooms while they're
     * placed, then become 1x1s: the real layouts look like that (tools/mappred README, "How the maps are made").
     */
    val reserve: Int = 0,
    /** a, b, d per [Cls] for the tiles' chances (MapPredictor.calibrated); null leaves them as the samples give them. */
    val calibration: Array<DoubleArray>? = null,
) {
    /** The most big rooms a map of this floor has (the loosest fallback's cap). */
    val maxBig = combos.maxOf { c -> (0 until 5).sumOf { c[it] } }

    companion object {
        val F1 = FloorRules(1, 4, 5, false, 0, false, 3, FloorStats.COMBOS_1, FloorStats.WALK_SHORT,
            FloorStats.BLOOD_FROM_ENTRANCE_1, FloorStats.FAIRY_FROM_ENTRANCE_1, null, FloorStats.MISSING_1)
        val F2 = FloorRules(2, 5, 5, false, 0, false, 3, FloorStats.COMBOS_2, FloorStats.WALK_SHORT,
            FloorStats.BLOOD_FROM_ENTRANCE_2_4, FloorStats.FAIRY_FROM_ENTRANCE_2_4, null, FloorStats.MISSING_2)
        val F3 = FloorRules(3, 5, 5, false, 0, true, 3, FloorStats.COMBOS_3_4, FloorStats.WALK_SHORT,
            FloorStats.BLOOD_FROM_ENTRANCE_2_4, FloorStats.FAIRY_FROM_ENTRANCE_2_4, null, FloorStats.MISSING_3)
        val F4 = FloorRules(4, 5, 5, true, 6, true, 3, FloorStats.COMBOS_3_4, FloorStats.WALK_SHORT,
            FloorStats.BLOOD_FROM_ENTRANCE_2_4, FloorStats.FAIRY_FROM_ENTRANCE_2_4, null, FloorStats.MISSING_4)
        val F5 = FloorRules(5, 5, 6, true, 5, true, 4, FloorStats.COMBOS_5_6, MapStats.WALK,
            FloorStats.BLOOD_FROM_ENTRANCE_5_6, FloorStats.FAIRY_FROM_ENTRANCE_5_6, null, FloorStats.MISSING_5)
        val F6 = FloorRules(6, 5, 6, true, 5, true, 4, FloorStats.COMBOS_5_6, MapStats.WALK,
            FloorStats.BLOOD_FROM_ENTRANCE_5_6, FloorStats.FAIRY_FROM_ENTRANCE_5_6, null, FloorStats.MISSING_6)
        val F7 = FloorRules(7, 6, 6, false, 0, true, MapStats.FAIRY_BEFORE_BLOOD, MapStats.COMBOS, MapStats.WALK,
            MapStats.BLOOD_FROM_ENTRANCE, MapStats.FAIRY_FROM_ENTRANCE, MapStats.BIG_TILE, emptySet(), puzzleOrder = true, reserve = 5,
            calibration = MapStats.CALIBRATION)

        /** The rules for floor [floor] (1-7, F or M); null for the entrance floor. */
        fun of(floor: Int): FloorRules? = when (floor) {
            1 -> F1; 2 -> F2; 3 -> F3; 4 -> F4; 5 -> F5; 6 -> F6; 7 -> F7
            else -> null
        }
    }
}
