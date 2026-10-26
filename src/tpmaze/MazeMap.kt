package com.engineerclient.tpmaze

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The Teleport Maze's pads in the room's own frame: Odin's pad order and its relative coordinates (TPMazeSolver's list), so a pad's
 * number is the same on every client however the room is turned. 28 pads in seven cells of four, then the end pad (28) and the
 * start pad (29). A pad drops you on the middle of the far corner of its 2x2 slab patch, its landing cell.
 *
 * What every maze does (tools/tpmaze, a week of public ADR runs): pads are two-way pairs (A drops you beside B, B beside A), never
 * inside one cell; the start pad pairs with a cell pad; one pad, the final pad, leads to the end and nothing ever drops you beside it;
 * every landing turns you to look at the middle of the final pad's landing cell; no pad of the cell the start leads into goes into
 * the final pad's cell; and nothing else is fixed: a fresh layout every run.
 */
object MazePads {
    const val END = 28
    const val START = 29
    /** A player's region that is no cell: the start pad's corridor, the end pad's room. */
    const val START_AREA = 7
    const val END_AREA = 8

    val FRAMES: Array<IntArray> = arrayOf(
        intArrayOf(4, 12), intArrayOf(4, 6), intArrayOf(10, 12), intArrayOf(10, 6), intArrayOf(4, 20), intArrayOf(4, 14), intArrayOf(10, 20), intArrayOf(10, 14),
        intArrayOf(4, 28), intArrayOf(4, 22), intArrayOf(10, 28), intArrayOf(10, 22), intArrayOf(12, 28), intArrayOf(12, 22), intArrayOf(18, 28), intArrayOf(18, 22),
        intArrayOf(20, 28), intArrayOf(20, 22), intArrayOf(26, 28), intArrayOf(26, 22), intArrayOf(26, 20), intArrayOf(26, 14), intArrayOf(20, 20), intArrayOf(20, 14),
        intArrayOf(26, 12), intArrayOf(26, 6), intArrayOf(20, 12), intArrayOf(20, 6), intArrayOf(15, 14), intArrayOf(15, 12),
    )

    /** Each pad's landing cell: one across and one along from its frame, into its patch. */
    val LAND: Array<IntArray> = Array(30) { i ->
        val (x, z) = FRAMES[i].let { it[0] to it[1] }
        intArrayOf(x + if (i == END || x % 8 == 4) 1 else -1, z + if (z % 8 == 6) 1 else -1)
    }

    private val CELL_X0 = intArrayOf(4, 4, 4, 12, 20, 20, 20)
    private val CELL_Z0 = intArrayOf(6, 14, 22, 22, 22, 14, 6)

    /** The cell of pad [p] (0-6), [START_AREA] for the start pad, [END_AREA] for the end pad. */
    fun cell(p: Int): Int = when { p < 28 -> p / 4; p == START -> START_AREA; else -> END_AREA }

    /** The pads of cell [c]. */
    fun padsOf(c: Int): IntRange = c * 4 until c * 4 + 4

    /** "1NW".."7SE" (cell 1-7 around the U from the start's left; N is the start's side, W is -x), "END", "START". */
    fun label(p: Int): String = when (p) {
        END -> "END"
        START -> "START"
        else -> "${cell(p) + 1}${if (FRAMES[p][1] == CELL_Z0[cell(p)]) 'N' else 'S'}${if (FRAMES[p][0] == CELL_X0[cell(p)]) 'W' else 'E'}"
    }

    /** Where a player at (x, z) is: a cell 0-6, [START_AREA], [END_AREA], or -1 (a wall, outside the room). */
    fun regionAt(x: Double, z: Double): Int {
        for (c in 0 until 7) if (x >= CELL_X0[c] && x < CELL_X0[c] + 7 && z >= CELL_Z0[c] && z < CELL_Z0[c] + 7) return c
        if (x >= 11 && x < 20 && z >= 4 && z < 13.5) return START_AREA
        if (x >= 11 && x < 20 && z >= 13.5 && z < 22) return END_AREA
        return -1
    }

    /** The pad whose landing cell's middle (x, z) is, or -1. */
    fun landingAt(x: Double, z: Double): Int {
        for (i in 0 until 30) if (abs(x - LAND[i][0] - 0.5) < 0.05 && abs(z - LAND[i][1] - 0.5) < 0.05) return i
        return -1
    }

    /** The pad a player at (x, z) stands on: the nearest frame of their own cell (or corridor) within [reach] of its middle, or -1. */
    fun padUnder(x: Double, z: Double, reach: Double = 1.5): Int {
        val pads = when (val r = regionAt(x, z)) { in 0 until 7 -> padsOf(r).toList(); START_AREA -> listOf(START); END_AREA -> listOf(END); else -> return -1 }
        return pads.minByOrNull { hypot(x - FRAMES[it][0] - 0.5, z - FRAMES[it][1] - 0.5) }?.takeIf { hypot(x - FRAMES[it][0] - 0.5, z - FRAMES[it][1] - 0.5) <= reach } ?: -1
    }

    /** MC yaw (0 looks along +z, 90 along -x) from (x, z) to the middle of pad [p]'s landing cell. */
    fun yawTo(x: Double, z: Double, p: Int): Double = Math.toDegrees(atan2(-(LAND[p][0] + 0.5 - x), LAND[p][1] + 0.5 - z))

    /**
     * The cell pads whose landing cells lie on the line of sight from pad [land]'s landing cell at [yaw] (within [tol] degrees,
     * ahead of it): where the final pad can be after that landing. Empty: the yaw looks at no pad, so it was no pad's teleport.
     */
    fun aim(land: Int, yaw: Double, tol: Double = 0.5): List<Int> {
        val lx = LAND[land][0] + 0.5; val lz = LAND[land][1] + 0.5
        val dx = -sin(Math.toRadians(yaw)); val dz = cos(Math.toRadians(yaw))
        return (0 until 28).filter { p ->
            if (p == land) return@filter false
            val tx = LAND[p][0] + 0.5 - lx; val tz = LAND[p][1] + 0.5 - lz
            if (tx * dx + tz * dz <= 0) return@filter false
            abs(((yawTo(lx, lz, p) - yaw) % 360 + 540) % 360 - 180) <= tol
        }
    }
}

/**
 * What a party knows of one Teleport Maze: the pad pairs anyone used, the final pad, and every landing's look (which pins the
 * final pad). Everything is in pad numbers ([MazePads]), so knowledge from any client merges as is. Not thread-safe: the client
 * thread owns it.
 */
class MazeMap {
    private val partner = IntArray(30) { -1 }
    /** The pair was read off a player's own client (theirs or ours); otherwise seen from outside, which an exact read replaces. */
    private val sure = BooleanArray(30)
    /** The pad into the end, once someone took it (or the looks leave one pad). */
    private var exitPad = -1
    /** Pads that can still be the final pad: every landing's line of sight so far, no partner, never landed beside. */
    private val candidate = BooleanArray(28) { true }
    private val looks = LinkedHashMap<Int, Double>()

    /** The pad [p] is paired with, or null while nobody has used either. */
    fun partnerOf(p: Int): Int? = partner[p].takeIf { it >= 0 }

    fun known(p: Int): Boolean = partner[p] >= 0 || p == exitPad

    /** Every pair known, each once (smaller pad first); [sureOnly]: just those read off a player's own client. */
    fun links(sureOnly: Boolean = false): List<IntArray> = (0 until 30).filter { partner[it] > it && (!sureOnly || sure[it]) }.map { intArrayOf(it, partner[it]) }

    /** Every landing's look, as landing pad to yaw (the room's frame). */
    fun looks(): Map<Int, Double> = looks

    /**
     * Someone stood on [from] and landed beside [to]: [from] and [to] are a pair (or [from] is the final pad, for the end). True if
     * new. [exact]: read off the player's own client; a pair seen from outside (a teammate's teleport packet) is a guess that an exact
     * read replaces. A pad has one partner: otherwise a different one is a misread and the first stays.
     */
    fun link(from: Int, to: Int, exact: Boolean = true): Boolean {
        if (from == to || from !in 0 until 30 || to !in 0 until 30 || from == MazePads.END) return false
        if (to == MazePads.END) {
            if (from >= 28 || exitPad == from || partner[from] >= 0) return false
            exitPad = from
            return true
        }
        if (from == exitPad || to == exitPad) return false
        if (partner[from] == to) { if (exact && !sure[from]) { sure[from] = true; sure[to] = true; return true }; return false }
        val clash = intArrayOf(from, to).filter { partner[it] >= 0 }
        if (clash.any { !exact || sure[it] }) return false
        for (p in clash) { val q = partner[p]; if (q >= 0) { partner[q] = -1; sure[q] = false; partner[p] = -1 } }
        partner[from] = to; partner[to] = from
        sure[from] = exact; sure[to] = exact
        if (from < 28) candidate[from] = false
        if (to < 28) candidate[to] = false
        return true
    }

    /** A landing beside pad [land] looking [yaw] (the room's frame). False when it looks at no pad, or at none still possible. */
    fun look(land: Int, yaw: Double): Boolean {
        if (land !in 0 until 28 && land != MazePads.START) return false
        val on = MazePads.aim(land, yaw).toSet()
        if (on.none { candidate[it] }) return false
        if (looks.containsKey(land)) return false
        looks[land] = yaw
        for (p in 0 until 28) if (p !in on) candidate[p] = false
        if (land < 28) candidate[land] = false
        return true
    }

    /** The final pad, once known. */
    val final: Int?
        get() = exitPad.takeIf { it >= 0 } ?: candidates().singleOrNull()

    /** Pads that may be the final pad (just it, once known). */
    fun candidates(): List<Int> = if (exitPad >= 0) listOf(exitPad) else if (looks.isEmpty()) emptyList() else (0 until 28).filter { candidate[it] && partner[it] < 0 }

    /** The cells the final pad may be in (empty while nobody has landed yet). */
    fun finalCells(): Set<Int> = candidates().mapTo(HashSet()) { MazePads.cell(it) }

    /** Merges a teammate's map in (their exact pairs, the final pad, their looks); true if anything was new. */
    fun merge(links: List<IntArray>, exit: Int?, looks: Map<Int, Double>): Boolean {
        var changed = false
        for (l in links) if (l.size == 2) changed = link(l[0], l[1]) or changed
        if (exit != null) changed = link(exit, MazePads.END) or changed
        for ((land, yaw) in looks) changed = look(land, yaw) or changed
        return changed
    }

    val exit: Int? get() = exitPad.takeIf { it >= 0 }
}

/**
 * Where each player should go next, the same on every client that knows the same: players in a cell split its unused pads between
 * them (by name, so nobody needs to agree on anything), a pad known to lead toward the final pad wins over exploring, and a player
 * with nothing left to try in their cell goes by known pads to the nearest cell that has some.
 */
object MazePlan {
    enum class Kind {
        /** Step on the final pad. */ FINAL,
        /** Known pads lead to the final pad's cell: take the first. */ ROUTE,
        /** An unused pad: nobody knows where it goes. */ EXPLORE,
        /** Nothing unused here for you: known pads to a cell with some. */ DETOUR,
        /** In the corridor: the start pad. */ START,
    }

    class Advice(val pad: Int, val kind: Kind, val steps: Int)

    /**
     * Every listed player's next pad. [where]: each player's region ([MazePads.regionAt]); players outside the maze are left out.
     * Order in a cell is by name, so every client gives the same answer for the same knowledge and positions.
     */
    fun plan(map: MazeMap, where: Map<String, Int>): Map<String, Advice> {
        val out = HashMap<String, Advice>()
        val fin = map.final
        val finCells = map.finalCells()
        val claimed = HashSet<Int>()
        // First, everyone with a fixed answer: the corridor, the final pad, a known way to it.
        val open = ArrayList<Pair<String, Int>>()
        for ((name, r) in where) {
            when {
                r == MazePads.START_AREA -> out[name] = Advice(MazePads.START, Kind.START, 1)
                r !in 0 until 7 -> {}
                fin != null && MazePads.cell(fin) == r -> out[name] = Advice(fin, Kind.FINAL, 1)
                else -> {
                    val route = if (finCells.size == 1 && r !in finCells) route(map, r) { it in finCells } else null
                    if (route != null) out[name] = Advice(route.pad, Kind.ROUTE, route.steps + 1) else open += name to r
                }
            }
        }
        // Then the unused pads, split in each cell by name.
        val needDetour = ArrayList<Pair<String, Int>>()
        for ((cell, names) in open.groupBy({ it.second }, { it.first }).toSortedMap()) {
            // A pad that may be the final pad first: stepping on it either ends the maze or explores like any other.
            val maybe = map.candidates().toSet()
            val fresh = MazePads.padsOf(cell).filter { !map.known(it) }.sortedBy { if (it in maybe) 0 else 1 }
            for ((i, name) in names.sorted().withIndex()) {
                if (i < fresh.size) { out[name] = Advice(fresh[i], Kind.EXPLORE, 1); claimed += fresh[i] }
                else needDetour += name to cell
            }
        }
        // Whoever is left over goes by known pads to the nearest cell with an unused pad nobody is taking yet.
        val sent = HashMap<Int, Int>()
        for ((name, cell) in needDetour.sortedBy { it.first }) {
            val way = route(map, cell) { c -> c != cell && MazePads.padsOf(c).count { !map.known(it) && it !in claimed } > (sent[c] ?: 0) }
            if (way != null) sent.merge(way.cell, 1, Int::plus)
            out[name] = when {
                way != null -> Advice(way.pad, Kind.DETOUR, way.steps)
                else -> {
                    // Nothing better: another unused pad here, else any pad that doesn't lead back to the corridor.
                    val fresh = MazePads.padsOf(cell).filter { !map.known(it) }
                    val any = fresh.firstOrNull() ?: MazePads.padsOf(cell).firstOrNull { map.partnerOf(it) != MazePads.START } ?: MazePads.padsOf(cell).first()
                    Advice(any, if (any in fresh) Kind.EXPLORE else Kind.DETOUR, 1)
                }
            }
        }
        return out
    }

    /** A way by known pads: its first pad, how many teleports, and the cell it ends in. */
    class Way(val pad: Int, val steps: Int, val cell: Int)

    /**
     * The fewest-teleport way from cell [from] by known pairs to a cell where [goal] holds; null if none. The corridor counts as a
     * stop: a pad back to it costs the start pad too.
     */
    fun route(map: MazeMap, from: Int, goal: (Int) -> Boolean): Way? {
        val dist = HashMap<Int, Int>(); val first = HashMap<Int, Int>()
        val queue = ArrayDeque<Int>()
        dist[from] = 0; queue += from
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (c != from && c in 0 until 7 && goal(c)) return Way(first[c]!!, dist[c]!!, c)
            val moves = if (c == MazePads.START_AREA) listOfNotNull(map.partnerOf(MazePads.START)?.let { MazePads.START to MazePads.cell(it) })
            else MazePads.padsOf(c).mapNotNull { p -> map.partnerOf(p)?.let { p to MazePads.cell(it) } }
            for ((pad, next) in moves) {
                if (next in dist) continue
                dist[next] = dist[c]!! + 1
                first[next] = if (c == from) pad else first[c]!!
                queue += next
            }
        }
        return null
    }
}

/**
 * The room's frame against the world's, from where Odin puts three of the room's blocks (relative (0, 0), (1, 0) and (0, 1)):
 * positions and yaws both ways. [ox], [oz]: the world block of relative (0, 0); ([axX], [axZ]) and ([azX], [azZ]): the world step
 * of one block along the room's x and z. A block's middle maps to a block's middle.
 */
class RoomFrame(private val ox: Int, private val oz: Int, private val axX: Int, private val axZ: Int, private val azX: Int, private val azZ: Int) {
    /** Room (x, z) of world (x, z). */
    fun toRoom(x: Double, z: Double): DoubleArray {
        val dx = x - ox - 0.5; val dz = z - oz - 0.5
        return doubleArrayOf(dx * axX + dz * axZ + 0.5, dx * azX + dz * azZ + 0.5)
    }

    /** World (x, z) of room (x, z). */
    fun toWorld(x: Double, z: Double): DoubleArray =
        doubleArrayOf(ox + 0.5 + axX * (x - 0.5) + azX * (z - 0.5), oz + 0.5 + axZ * (x - 0.5) + azZ * (z - 0.5))

    /** The room's yaw for world yaw [yaw] (MC's: 0 looks along +z, 90 along -x). */
    fun yawToRoom(yaw: Double): Double {
        val dx = -sin(Math.toRadians(yaw)); val dz = cos(Math.toRadians(yaw))
        return Math.toDegrees(atan2(-(dx * axX + dz * axZ), dx * azX + dz * azZ))
    }

    /** The same room (Odin found the same corner and turn). */
    fun sameAs(o: RoomFrame) = ox == o.ox && oz == o.oz && axX == o.axX && axZ == o.axZ && azX == o.azX && azZ == o.azZ

    /** The room's block (0, 0) in the world: what teammates' messages are checked against. */
    val anchor: IntArray get() = intArrayOf(ox, oz)
}
