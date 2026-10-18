package com.engineerclient.p3sim.clear.puzzles

/**
 * Ice Fill as a state machine over three floors. Each floor is a patch of ice (feet level 70, 71 and 72 in the room's frame, the
 * ice one block below). Walking onto a patch cell covers it (the ice under it becomes packed ice); a floor is done when every
 * cell is covered, and the chests open when the third floor is done.
 *
 * The rules, as the recorded rooms show them: the first step on a floor may be any cell of its patch; every later step must be on a
 * cell next to the previous one (not a diagonal, not a jump), and never on a cell that is already covered. Either mistake collapses
 * the floor that is being filled (see [collapse]); floors that are done stay done.
 *
 * Odin recognises the room by two marker cells per floor (the first must be air, the second not) and draws a path from its table
 * (OdinFabric 0.3.4, BSD 3-Clause, copyright (c) 2025 odtheking, see [OdinTables]); [patterns] makes the same choice so the sim
 * can check that a room is one Odin can solve. UNMEASURED: whether stepping the exit cell early completes its floor (the sim needs
 * every cell of the patch). Positions are in the room's own frame.
 */
class IceFillLogic(val floors: List<Set<Cell>>) {
    data class Cell(val x: Int, val y: Int, val z: Int)

    /** What a step did. */
    enum class Outcome {
        /** Not on the active floor's patch (or no floor left): nothing happens. */
        NOTHING,
        /** The cell is covered now. */
        COVERED,
        /** The cell was already covered: "Oops! You stepped on the wrong block!" and the floor collapses. */
        WRONG_BLOCK,
        /** The cell is not next to the last one: "Don't move diagonally! Bad!" and the floor collapses. */
        DIAGONAL,
    }

    /** The result of [step]: what happened, and for [Outcome.COVERED] the cell and whether it finished its floor. */
    class Step(val outcome: Outcome, val cell: Cell? = null, val floorDone: Boolean = false)

    private val covered: List<MutableSet<Cell>> = floors.map { HashSet() }

    /** The cell stepped on last on the active floor, or null at the start of a floor (and after a collapse). */
    var last: Cell? = null
        private set

    fun coveredCount(f: Int) = covered[f].size
    /** A floor with nothing to cover counts as done (a volume captured part way through the puzzle can have empty floors). */
    fun floorDone(f: Int) = covered[f].size >= floors[f].size

    /** The first floor not done, or -1 when all are. */
    val activeFloor: Int get() = floors.indices.firstOrNull { !floorDone(it) } ?: -1
    val solved: Boolean get() = floors.any { it.isNotEmpty() } && activeFloor == -1

    /** Someone stands with their feet at (x, y, z), having just moved onto that cell. */
    fun step(x: Int, y: Int, z: Int): Step {
        val f = activeFloor
        if (f < 0) return Step(Outcome.NOTHING)
        val c = Cell(x, y, z)
        if (c !in floors[f]) return Step(Outcome.NOTHING)
        if (c in covered[f]) return Step(Outcome.WRONG_BLOCK, c)
        val l = last
        if (l != null && Math.abs(l.x - c.x) + Math.abs(l.z - c.z) != 1) return Step(Outcome.DIAGONAL, c)
        covered[f].add(c)
        val done = floorDone(f)
        last = if (done) null else c
        return Step(Outcome.COVERED, c, done)
    }

    /** The active floor falls: its covered cells are forgotten and the next step may be anywhere. Done floors are untouched. */
    fun collapse() {
        val f = activeFloor
        if (f >= 0) covered[f].clear()
        last = null
    }

    fun reset() { covered.forEach { it.clear() }; last = null }

    companion object {
        /** Feet level of the three floors. */
        val FLOOR_Y = listOf(70, 71, 72)

        /** Every column of floor [f] with ice under it (feet level minus one), whatever stands at feet level: the cells a collapse takes away. */
        fun scanIce(f: Int, size: Int = 31, isIce: (Int, Int, Int) -> Boolean): Set<Cell> {
            val y = FLOOR_Y[f]
            val out = LinkedHashSet<Cell>()
            for (z in 0 until size) for (x in 0 until size) if (isIce(x, y - 1, z)) out += Cell(x, y, z)
            return out
        }

        /** The patch of floor [f]: every column of the room whose ice (at feet level minus one) is ice and whose feet-level cell is air. */
        fun scan(f: Int, size: Int = 31, isIce: (Int, Int, Int) -> Boolean, isAir: (Int, Int, Int) -> Boolean): Set<Cell> {
            val y = FLOOR_Y[f]
            val out = LinkedHashSet<Cell>()
            for (z in 0 until size) for (x in 0 until size) if (isIce(x, y - 1, z) && isAir(x, y, z)) out += Cell(x, y, z)
            return out
        }

        /**
         * Odin's choice per floor: the index of the first pattern whose marker pair reads (air, not air), or -1 when none does
         * (the room is not one of Odin's patterns). [isAir] reads a cell of the room.
         */
        fun patterns(table: List<List<OdinTables.IcePattern>> = OdinTables.iceFloors, isAir: (Int, Int, Int) -> Boolean): List<Int> =
            table.map { ps ->
                ps.indexOfFirst { isAir(it.identifierAir[0], it.identifierAir[1], it.identifierAir[2]) && !isAir(it.identifierSolid[0], it.identifierSolid[1], it.identifierSolid[2]) }
            }
    }
}
