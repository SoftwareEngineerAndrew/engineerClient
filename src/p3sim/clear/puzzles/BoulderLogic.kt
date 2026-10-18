package com.engineerclient.p3sim.clear.puzzles

/**
 * Boulder as a state machine. The room's boulders sit on a 6 by 7 grid (read at y = 66 in the room's frame); Odin turns the grid
 * into a 42-character key and looks up the clicks (a wall sign or stone button at y = 65) that move the boulders out of the way.
 * The sim uses the same key and the same table (OdinFabric 0.3.4, BSD 3-Clause, copyright (c) 2025 odtheking, see [OdinTables]):
 * the puzzle is done when every click of the pattern has been made. UNMEASURED: the rules between the clicks (what a wrong click
 * does to the real room); here a wrong click only counts.
 */
class BoulderLogic(val clicks: List<IntArray>) {
    private val remaining = clicks.toMutableList()
    var misclicks = 0
        private set

    val solved: Boolean get() = clicks.isNotEmpty() && remaining.isEmpty()
    val left: Int get() = remaining.size

    /** A click at (x, z) of y = 65 in the room's frame; true when it was one of the pattern's. */
    fun click(x: Int, z: Int): Boolean {
        val hit = remaining.firstOrNull { it[2] == x && it[3] == z }
        if (hit == null) { misclicks++; return false }
        remaining.remove(hit)
        return true
    }

    fun reset() { remaining.clear(); remaining.addAll(clicks); misclicks = 0 }

    companion object {
        /** The grid cells Odin samples, in its order: z from 24 down to 9, x from 24 down to 6, step 3. */
        val GRID: List<Pair<Int, Int>> = buildList { for (z in 24 downTo 9 step 3) for (x in 24 downTo 6 step 3) add(x to z) }

        /** The key for a room: [isAir] says whether the cell (x, 66, z) is air. */
        fun key(isAir: (Int, Int) -> Boolean): String = GRID.joinToString("") { (x, z) -> if (isAir(x, z)) "0" else "1" }

        /** The clicks for a key, or null when Odin's table does not know the pattern. */
        fun solutionFor(key: String, table: Map<String, List<IntArray>> = OdinTables.boulder): List<IntArray>? = table[key]
    }
}
