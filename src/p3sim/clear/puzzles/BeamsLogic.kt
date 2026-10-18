package com.engineerclient.p3sim.clear.puzzles

/**
 * Creeper Beams as a state machine. The room has pairs of sea lanterns joined by a beam. An arrow that lands near either end turns
 * BOTH lanterns of the pair to prismarine on the same tick, for good; when every pair is done the chest appears. The pairs are
 * Odin's table (OdinFabric 0.3.4, BSD 3-Clause, copyright (c) 2025 odtheking, see [OdinTables]) and, like Odin, only pairs whose
 * two ends are both lanterns in the room count. Measured over 53 real runs: five pairs, a pair flips as one, no right clicks.
 *
 * Positions are in the room's own frame. Pure: no Minecraft.
 */
class BeamsLogic(pairs: List<Pair<Cell, Cell>>) {
    data class Cell(val x: Int, val y: Int, val z: Int)

    /** What a call changed: lanterns to turn to prismarine, lanterns to turn back to sea lanterns (a reset). */
    class Change(val toPrismarine: List<Cell>, val toLantern: List<Cell>)

    private val partner = HashMap<Cell, Cell>()
    private val pairCount: Int
    private val done = HashSet<Cell>()

    init {
        // A lantern belongs to one pair (the table repeats a pair or two): the first one wins.
        for ((a, b) in pairs) { if (a in partner || b in partner || a == b) continue; partner[a] = b; partner[b] = a }
        pairCount = partner.size / 2
    }

    val total: Int get() = pairCount
    val solvedPairs: Int get() = done.size / 2
    val solved: Boolean get() = pairCount > 0 && solvedPairs == pairCount

    /** The lanterns in play. */
    val lanterns: Set<Cell> get() = partner.keys

    /** The other end of the pair [c] belongs to. */
    fun partnerOf(c: Cell): Cell? = partner[c]

    /** Whether the pair holding [c] is already done. */
    fun isDone(c: Cell) = c in done

    /** The closest lantern of a pair still to do within [radius] blocks of the point, or null. */
    fun lanternNear(x: Double, y: Double, z: Double, radius: Double = ARROW_RADIUS): Cell? =
        partner.keys.filter { it !in done }.map { it to dist2(it, x, y, z) }.filter { it.second <= radius * radius }.minByOrNull { it.second }?.first

    private fun dist2(c: Cell, x: Double, y: Double, z: Double): Double {
        val dx = c.x + 0.5 - x; val dy = c.y + 0.5 - y; val dz = c.z + 0.5 - z
        return dx * dx + dy * dy + dz * dz
    }

    /** The lantern at [c] was hit: its whole pair turns to prismarine at once. */
    fun hit(c: Cell): Change {
        val p = partner[c] ?: return NONE
        if (c in done) return NONE
        done += c; done += p
        return Change(listOf(c, p), emptyList())
    }

    /** Back to the start (the lanterns are all lanterns again). */
    fun reset() { done.clear() }

    companion object {
        /** An arrow within this many blocks of a lantern counts as a hit on its pair (measured: arrows land within about 4 blocks of an end). */
        const val ARROW_RADIUS = 4.0
        private val NONE = Change(emptyList(), emptyList())

        /** Odin's pairs that exist in the room: [isLantern] says whether a cell of the room holds a sea lantern. */
        fun pairsIn(table: List<IntArray> = OdinTables.beams, isLantern: (Cell) -> Boolean): List<Pair<Cell, Cell>> =
            table.map { Cell(it[0], it[1], it[2]) to Cell(it[3], it[4], it[5]) }.filter { isLantern(it.first) && isLantern(it.second) }.distinctBy { setOf(it.first, it.second) }
    }
}
