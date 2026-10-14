package com.devgineerclient.practice

import com.engineerclient.p3sim.ShotPlan
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The i4 board and what one volley hits on it, for every crosshair spot your mouse can reach. Pure (no world, no
 * client), so it tests offline; [I4Aims] feeds it the board's real blocks and draws what it finds.
 *
 * The arrows are Hypixel's, measured ([ShotPlan], with the same numbers through [ShotPlan.planInto]): the main arrow
 * carries 1.8's aim noise (a gaussian of sd [NOISE] on each axis of the unit look), Terror's two Hydra Strike arrows
 * (10 stacks, +-8 deg) inherit it from the main arrow's move, and the Terminator's side arrows (+-5.5 deg of your
 * clean look) only move with it a little (they start where the main arrow was after its first tick).
 *
 * An arrow flies as 1.8 moves it: one straight move a tick, each 0.99 of the last and 0.05 lower. Near the board
 * that is the move that comes through z [NEAR] and, if it ends before the cells' back (z 51), the next one, which
 * falls a little more steeply (the [Crossing]'s kink and bend). [Board.hits] decides which cell such a path lands on,
 * exactly: through a face, through a side or the top from the block beside (decided from what that block holds), or
 * by the boxes themselves. Each arrow's crossing, slopes and kink move in a straight line with the noise
 * ([Evaluator] works out how, exactly, through the flight), so the chance that every cell of an aim keeps an arrow
 * is worked out in closed form for one arrow well inside a face, and over a fixed whitened low-discrepancy set of
 * draws otherwise. Offline, against replanning the volley with 4,000-40,000 random draws and flying each arrow tick
 * by tick onto the real board's boxes: off by 0.25-0.55% on average; at an 80% line, the spots along each painted
 * aim's edge are all at 78% or better, and no spot left out reaches 82%.
 *
 * The crosshair moves in whole mouse counts: each turns you [mouseStep] degrees (Minecraft's (s*0.6+0.2)^3*8 at
 * sensitivity s, x 0.15), so the spots you can reach from your look are a lattice of that pitch, and [Lattice] lists
 * them. Recordings: every recorded mouse turn is a whole multiple of that step.
 */
object I4Geometry {
    val CELLS: List<BlockPos> = (0 until 9).map { BlockPos(64 + (it % 3) * 2, 126 + (it / 3) * 2, 50) }

    /** The cells' face: they are full blocks at z 50. */
    const val FACE = 50.0
    /** Paths are described from here on (the move that comes through this z, and the next). */
    const val NEAR = 49.5
    /** How far past [NEAR] a cell can be hit: its back, z 51. */
    const val BACK = 1.5
    private const val G = 0.05
    /** 1.8's aim noise: a gaussian of this sd on each axis of the unit look. */
    const val NOISE = 0.0075

    /** Degrees one mouse count turns you, at sensitivity [s] (Options' 0..1). */
    fun mouseStep(s: Double): Double { val d = s * 0.6 + 0.2; return d * d * d * 8.0 * 0.15 }

    /** A path's height [d] past [NEAR]: through [py] with slope [sy], [bend] steeper from [kink] on. */
    fun yAt(py: Double, sy: Double, kink: Double, bend: Double, d: Double): Double = py + sy * d + if (d > kink) bend * (d - kink) else 0.0

    private const val LEFT = 0
    private const val RIGHT = 1
    private const val BOTTOM = 2
    private const val TOP = 3

    /**
     * The board's blocks as boxes: each cell's ([cellOf] its index in [CELLS]) and whatever else an arrow can hit
     * around them (the panes between the cells, the frame).
     */
    class Board(val boxes: List<AABB>, val cellOf: IntArray) {
        // The boxes as flat numbers (min x y z, max x y z), and by block column (x, y) from (X0, Y0), so a path only
        // tests the few it passes.
        private val b = DoubleArray(6 * boxes.size).also { a -> boxes.forEachIndexed { i, x -> a[6 * i] = x.minX; a[6 * i + 1] = x.minY; a[6 * i + 2] = x.minZ; a[6 * i + 3] = x.maxX; a[6 * i + 4] = x.maxY; a[6 * i + 5] = x.maxZ } }
        private val columns: Array<IntArray?> = arrayOfNulls<IntArray>(W * W).also { m ->
            val lists = HashMap<Int, ArrayList<Int>>()
            boxes.forEachIndexed { i, bx ->
                for (x in floor(bx.minX).toInt()..floor(bx.maxX - 1e-9).toInt()) for (y in floor(bx.minY).toInt()..floor(bx.maxY - 1e-9).toInt())
                    if (x - X0 in 0 until W && y - Y0 in 0 until W) lists.getOrPut((x - X0) * W + (y - Y0)) { ArrayList() } += i
            }
            lists.forEach { (k, v) -> m[k] = v.toIntArray() }
        }
        private fun column(x: Int, y: Int): IntArray? = if (x - X0 in 0 until W && y - Y0 in 0 until W) columns[(x - X0) * W + (y - Y0)] else null

        /** Per cell, every box that can come before it: in its block column and the eight around, starting before its back. Its own first. */
        private val near: Array<IntArray> = Array(9) { c ->
            val x0 = 64 + (c % 3) * 2; val y0 = 126 + (c / 3) * 2
            val all = (x0 - 1..x0 + 1).flatMap { x -> (y0 - 1..y0 + 1).flatMap { y -> column(x, y)?.toList() ?: emptyList() } }.distinct()
                .filter { b[6 * it + 2] < NEAR + BACK - 1e-9 && b[6 * it + 5] > NEAR }
            (all.filter { cellOf[it] == c } + all.filter { cellOf[it] != c }).toIntArray()
        }

        /** Per cell: its box is its full block and nothing around it starts in front of its face, so a path through the face hits it. */
        private val clear = BooleanArray(9) { c ->
            val x0 = 64.0 + (c % 3) * 2; val y0 = 126.0 + (c / 3) * 2
            val own = near[c].filter { cellOf[it] == c }
            own.size == 1 && own[0].let { o -> b[6 * o] == x0 && b[6 * o + 1] == y0 && b[6 * o + 2] == FACE && b[6 * o + 3] == x0 + 1 && b[6 * o + 4] == y0 + 1 && b[6 * o + 5] >= FACE + 1 } &&
                near[c].none { cellOf[it] != c && b[6 * it + 2] < FACE - 1e-9 }
        }

        /**
         * Per cell side (4 c + left, right, bottom, top), what the block beside it holds, as depths past [NEAR]:
         * [free], how deep it is empty (a path through it reaches the side by then: in); [stripLo] to [stripHi], where
         * one box covers the side's whole edge from outside (a path reaching the side then was in that box first: out);
         * [cover], from where one box fills the whole block (a path still in it then is in that box: out).
         */
        private val free = DoubleArray(36) { BACK }
        private val stripLo = DoubleArray(36) { Double.MAX_VALUE }
        private val stripHi = DoubleArray(36) { -1.0 }
        private val cover = DoubleArray(36) { Double.MAX_VALUE }

        init {
            for (c in 0 until 9) {
                val x0 = 64.0 + (c % 3) * 2; val y0 = 126.0 + (c / 3) * 2
                for (s in 0 until 4) {
                    val k = 4 * c + s
                    // The block beside, and the side's edge as seen from it.
                    val fx0 = when (s) { LEFT -> x0 - 1; RIGHT -> x0 + 1; else -> x0 }
                    val fy0 = when (s) { BOTTOM -> y0 - 1; TOP -> y0 + 1; else -> y0 }
                    for (i in near[c]) {
                        if (cellOf[i] == c) continue
                        val o = 6 * i
                        val lo = b[o + 2] - NEAR; val hi = b[o + 5] - NEAR
                        if (b[o] < fx0 + 1 && b[o + 3] > fx0 && b[o + 1] < fy0 + 1 && b[o + 4] > fy0) free[k] = minOf(free[k], lo)
                        if (b[o] <= fx0 && b[o + 3] >= fx0 + 1 && b[o + 1] <= fy0 && b[o + 4] >= fy0 + 1 && hi > lo) cover[k] = minOf(cover[k], maxOf(lo, 0.5))
                        val strip = when (s) {
                            LEFT -> b[o] < x0 && b[o + 3] >= x0 && b[o + 1] <= y0 && b[o + 4] >= y0 + 1
                            RIGHT -> b[o] <= x0 + 1 && b[o + 3] > x0 + 1 && b[o + 1] <= y0 && b[o + 4] >= y0 + 1
                            BOTTOM -> b[o + 1] < y0 && b[o + 4] >= y0 && b[o] <= x0 && b[o + 3] >= x0 + 1
                            else -> b[o + 1] <= y0 + 1 && b[o + 4] > y0 + 1 && b[o] <= x0 && b[o + 3] >= x0 + 1
                        }
                        if (strip && lo < stripLo[k]) { stripLo[k] = lo; stripHi[k] = hi }
                    }
                    free[k] = free[k].coerceIn(0.5, BACK)
                }
            }
        }

        /** The deepest a path in the block beside cell [c]'s side [s] can come in through that side (its [cover], or the back). */
        fun deepest(c: Int, s: Int): Double = minOf(cover[4 * c + s], BACK)

        /** Whether [c]'s face decides for paths through it (see [clear]). */
        fun clear(c: Int): Boolean = clear[c]

        /**
         * The depth past [NEAR] in [lo]..[hi] at which the line through ([px], [py]) with slopes ([sx], [sy]) enters box
         * [i], or MAX_VALUE.
         */
        private fun enter(i: Int, px: Double, py: Double, sx: Double, sy: Double, lo: Double, hi: Double): Double {
            val o = 6 * i
            var t0 = maxOf(b[o + 2] - NEAR, lo); var t1 = minOf(b[o + 5] - NEAR, hi)
            if (t0 > t1) return Double.MAX_VALUE
            if (sx == 0.0) { if (px < b[o] || px > b[o + 3]) return Double.MAX_VALUE }
            else {
                var a = (b[o] - px) / sx; var z = (b[o + 3] - px) / sx
                if (a > z) { val t = a; a = z; z = t }
                if (a > t0) t0 = a; if (z < t1) t1 = z
            }
            if (sy == 0.0) { if (py < b[o + 1] || py > b[o + 4]) return Double.MAX_VALUE }
            else {
                var a = (b[o + 1] - py) / sy; var z = (b[o + 4] - py) / sy
                if (a > z) { val t = a; a = z; z = t }
                if (a > t0) t0 = a; if (z < t1) t1 = z
            }
            return if (t0 > t1) Double.MAX_VALUE else t0
        }

        /** Where the path ([yAt]) first enters box [i] before the cells' back, or MAX_VALUE: the move through [NEAR] up to [kink], the next after. */
        private fun enterPath(i: Int, px: Double, py: Double, sx: Double, sy: Double, kink: Double, bend: Double): Double {
            if (kink >= BACK) return enter(i, px, py, sx, sy, 0.0, BACK)
            if (kink > 0) { val t = enter(i, px, py, sx, sy, 0.0, kink); if (t != Double.MAX_VALUE) return t }
            return enter(i, px, py - bend * kink, sx, sy + bend, maxOf(kink, 0.0), BACK)
        }

        /** Whether cell [c]'s box is the first of the boxes around it ([near]) the path enters. */
        private fun first(c: Int, px: Double, py: Double, sx: Double, sy: Double, kink: Double, bend: Double): Boolean {
            var best = Double.MAX_VALUE; var cell = -1
            for (i in near[c]) { val t = enterPath(i, px, py, sx, sy, kink, bend); if (t < best) { best = t; cell = cellOf[i] } }
            return cell == c
        }

        /**
         * Whether the path that comes through [NEAR] at ([px], [py]) with slopes ([sx], [sy]), falling [bend] more
         * steeply (dy/dz) from [kink] past [NEAR] on, lands on cell [c]: it is the first box the path enters. Exact.
         * Through the face it does (when the cell is [clear]); never over the cell's span within its depth, it
         * doesn't; beside the face on one axis, it comes in through that side where it reaches it, decided by what the
         * block beside holds ([free], [stripLo], [cover]); anything else (a corner, a block that isn't simple) is
         * decided by the boxes around the cell.
         */
        fun hits(c: Int, px: Double, py: Double, sx: Double, sy: Double, kink: Double, bend: Double): Boolean {
            if (!clear[c]) return first(c, px, py, sx, sy, kink, bend)
            val x0 = 64.0 + 2 * (c % 3); val y0 = 126.0 + 2 * (c / 3)
            val xf = px + sx * 0.5; val yf = yAt(py, sy, kink, bend, 0.5)
            val inX = xf >= x0 && xf <= x0 + 1; val inY = yf >= y0 && yf <= y0 + 1
            if (inX && inY) return true
            val xb = px + sx * BACK
            if ((xf < x0 && xb < x0) || (xf > x0 + 1 && xb > x0 + 1)) return false
            val yb = yAt(py, sy, kink, bend, BACK)
            // Its height is highest at an end or at the kink (it only ever bends down).
            val yTop = if (kink > 0.5 && kink < BACK) maxOf(yf, yb, py + sy * kink) else maxOf(yf, yb)
            if (yTop < y0 || (yf > y0 + 1 && yb > y0 + 1)) return false
            if (inX) {
                val top = yf > y0 + 1
                val edge = if (top) y0 + 1 else y0
                val d = reachY(yf, sy, kink, bend, edge, top)
                if (d.isNaN()) return false
                val xe = px + sx * d
                if (xe < x0 || xe > x0 + 1) return false
                // In the block beside all the way (not a block further out at the face, no bulge into the next one).
                if (if (top) yTop >= y0 + 2 else yf <= y0 - 1) return first(c, px, py, sx, sy, kink, bend)
                return side(4 * c + if (top) TOP else BOTTOM, d) ?: first(c, px, py, sx, sy, kink, bend)
            }
            if (inY) {
                if (sx == 0.0) return false
                val right = xf > x0 + 1
                val edge = if (right) x0 + 1 else x0
                val d = (edge - px) / sx
                if (d < 0.5 || d > BACK) return false
                val ye = yAt(py, sy, kink, bend, d)
                if (ye < y0 || ye > y0 + 1 || (kink > 0.5 && kink < d && py + sy * kink > y0 + 1) || abs(xf - edge) >= 1) return first(c, px, py, sx, sy, kink, bend)
                return side(4 * c + if (right) RIGHT else LEFT, d) ?: first(c, px, py, sx, sy, kink, bend)
            }
            return first(c, px, py, sx, sy, kink, bend)
        }

        /** A path that stays in the block beside side [k] reaches that side [d] past [NEAR]: in, out, or null (the boxes decide). */
        private fun side(k: Int, d: Double): Boolean? = when {
            d <= free[k] -> true
            d > stripLo[k] && d <= stripHi[k] -> false
            d > cover[k] -> false
            else -> null
        }

        /** The cell the path ([hits]) lands on, or -1. */
        fun land(px: Double, py: Double, sx: Double, sy: Double, kink: Double, bend: Double): Int {
            val xf = px + sx * 0.5; val yf = yAt(py, sy, kink, bend, 0.5)
            val rx = 0.5 + abs(sx) * (BACK - 0.5) + 1e-9; val ry = 0.5 + (abs(sy) + abs(bend)) * (BACK - 0.5) + 1e-9
            for (c in 0 until 9) {
                if (abs(xf - (64.5 + 2 * (c % 3))) > rx || abs(yf - (126.5 + 2 * (c / 3))) > ry) continue
                if (hits(c, px, py, sx, sy, kink, bend)) return c
            }
            return -1
        }

        /**
         * What the path ([hits]) enters first, from every box it passes (not just a cell's neighbours): the cell's index
         * (or -1, anything else) and how far past [NEAR] (NaN: nothing before the cells' back).
         */
        fun trace(px: Double, py: Double, sx: Double, sy: Double, kink: Double, bend: Double): Landing {
            val yb = yAt(py, sy, kink, bend, BACK); val yk = if (kink in 0.0..BACK) py + sy * kink else py
            val x0 = floor(minOf(px, px + sx * BACK)).toInt(); val x1 = floor(maxOf(px, px + sx * BACK)).toInt()
            val y0 = floor(minOf(py, yb, yk)).toInt(); val y1 = floor(maxOf(py, yb, yk)).toInt()
            var best = Double.MAX_VALUE; var cell = -1
            for (x in x0..x1) for (y in y0..y1) {
                val col = column(x, y) ?: continue
                for (i in col) { val t = enterPath(i, px, py, sx, sy, kink, bend); if (t < best) { best = t; cell = cellOf[i] } }
            }
            return if (best == Double.MAX_VALUE) Landing(-1, Double.NaN) else Landing(cell, best)
        }

        private companion object {
            const val X0 = 58; const val Y0 = 120; const val W = 20

            /** The first depth past 0.5 (the face) at which a path at [yf] there reaches [edge] (from above when [top]), or NaN before the back. */
            fun reachY(yf: Double, sy: Double, kink: Double, bend: Double, edge: Double, top: Boolean): Double {
                var d0 = 0.5; var y = yf
                if (kink > 0.5) {
                    val e = minOf(kink, BACK)
                    val ye = y + sy * (e - 0.5)
                    if (if (top) ye <= edge else ye >= edge) return d0 + (edge - y) / sy
                    if (e >= BACK) return Double.NaN
                    d0 = e; y = ye
                }
                val s2 = sy + bend
                val yb = y + s2 * (BACK - d0)
                return if (if (top) yb <= edge else yb >= edge) d0 + (edge - y) / s2 else Double.NaN
            }
        }
    }

    /** What a path enters first: [cell] (-1: anything else or nothing) at [depth] past [NEAR] (NaN: nothing). */
    class Landing(val cell: Int, val depth: Double)

    /**
     * Hypixel's board as the room shows it: magenta panes between the cells (their arms reach back, so they start
     * 7/16 behind the face) and a full frame around. Tests and the fallback when the world isn't loaded; in game the
     * real blocks are read ([I4Aims]).
     */
    val DEFAULT: Board = run {
        val boxes = ArrayList<AABB>(); val cellOf = ArrayList<Int>()
        for (x in 63..69) for (y in 125..131) {
            val cell = CELLS.indexOfFirst { it.x == x && it.y == y }
            val frame = x == 63 || x == 69 || y == 125 || y == 131
            val box = when {
                cell >= 0 -> AABB(x.toDouble(), y.toDouble(), 50.0, x + 1.0, y + 1.0, 51.0)
                frame -> AABB(x.toDouble(), y.toDouble(), 50.0, x + 1.0, y + 1.0, 51.0)
                else -> AABB(x.toDouble(), y.toDouble(), 50.4375, x + 1.0, y + 1.0, 51.0)
            }
            boxes += box; cellOf += cell
        }
        Board(boxes, cellOf.toIntArray())
    }

    /**
     * Where an arrow comes through z = [NEAR]: the point and the slopes (dx/dz, dy/dz) of the move that does that,
     * where that move ends ([kink], past [NEAR]) and how much more steeply the next one falls ([bend], dy/dz; its
     * dx/dz is the same).
     */
    class Crossing(val x: Double, val y: Double, val sx: Double, val sy: Double, val kink: Double, val bend: Double)

    /** An arrow's [crossing], and [ticks]: how many of its moves after [fly]'s `at` end where the move through [NEAR] ends. */
    class Flight(val crossing: Crossing, val ticks: Int) {
        /** Moves after `at` until the arrow is [depth] past [NEAR] (where it lands: its impact tick). */
        fun ticksTo(depth: Double): Int = if (depth <= crossing.kink) ticks else ticks + 1
    }

    /** The arrow that left [from], is at [at] and moves [v] next, flown as 1.8 does (move, then x0.99 and -0.05) to [NEAR]. */
    fun fly(from: Vec3, at: Vec3, v: Vec3): Flight? {
        var ax = from.x; var ay = from.y; var az = from.z
        var bx = at.x; var by = at.y; var bz = at.z
        var vx = v.x; var vy = v.y; var vz = v.z
        for (m in 0 until 80) {
            if (az < NEAR && bz >= NEAR) {
                val dz = bz - az
                val f = (NEAR - az) / dz
                val sy = (by - ay) / dz
                return Flight(Crossing(ax + (bx - ax) * f, ay + (by - ay) * f, (bx - ax) / dz, sy, bz - NEAR, if (vz > 0) vy / vz - sy else 0.0), m)
            }
            if (vz <= 0 || by < 100) return null
            ax = bx; ay = by; az = bz
            bx += vx; by += vy; bz += vz
            vx *= 0.99; vy = vy * 0.99 - G; vz *= 0.99
        }
        return null
    }

    /** The cell the arrow through [c] lands on, or -1 (anything else, or nothing). */
    fun hit(board: Board, c: Crossing): Int = board.land(c.x, c.y, c.sx, c.sy, c.kink, c.bend)

    /** What the arrow through [c] enters first, and how deep ([Board.trace]). */
    fun landing(board: Board, c: Crossing): Landing = board.trace(c.x, c.y, c.sx, c.sy, c.kink, c.bend)

    /**
     * Who shoots, from where: the eye is 1.62 up, 1.27 sneaking (Hypixel too: 728 of 730 recorded sneaking volleys
     * left from 1.27).
     */
    class Shooter(val bow: String, val pos: Vec3, val crouch: Boolean, val stacks: Int) {
        val eye: Vec3 get() = Vec3(pos.x, pos.y + if (crouch) 1.27 else 1.62, pos.z)
    }

    /** Your look at [at] (yaw, pitch) from [eye]. */
    fun lookAt(eye: Vec3, at: Vec3): Pair<Double, Double> {
        val d = at.subtract(eye)
        return Math.toDegrees(atan2(-d.x, d.z)) to -Math.toDegrees(atan2(d.y, hypot(d.x, d.z)))
    }

    /**
     * The spots your mouse reaches: your look ([yaw0], [pitch0]) plus whole counts of [step] degrees, on both axes.
     * [i] and [j] count steps from it.
     */
    class Lattice(val yaw0: Double, val pitch0: Double, val step: Double) {
        fun yaw(i: Int) = yaw0 + i * step
        fun pitch(j: Int) = pitch0 + j * step
        /** The nearest count to [yaw] / [pitch]. */
        fun i(yaw: Double) = floor((yaw - yaw0) / step + 0.5).toInt()
        fun j(pitch: Double) = floor((pitch - pitch0) / step + 0.5).toInt()
    }

    // ------------------------------------------------------------------ one volley, fast

    /**
     * One volley at a time, for one shooter, allocating nothing. [eval] flies every arrow of the shot at a look
     * (noise-free): its crossing, kink and bend, the cell it lands on, and how the crossing, its slopes and the kink
     * move with the noise (exactly, through the flight: the flight is linear in the noise). [chance] then works on
     * entries: each arrow within reach of a cell (on it, or a few sds off it: a main arrow in the gap can still land
     * on the cell beside it). Through the face it lands (that much is a bivariate gaussian over a rectangle); the
     * rest of each draw is decided by [Board.hits] on the path the draw makes. Not thread-safe: one per thread.
     */
    class Evaluator(val board: Board, val shooter: Shooter) {
        private val plan = ShotPlan.Arrows()
        /** Distinct arrows of the last [eval] (a Terminator's side arrows come twice, identical), in spawn order. */
        var n = 0; private set
        val x = DoubleArray(MAX); val y = DoubleArray(MAX); val sx = DoubleArray(MAX); val sy = DoubleArray(MAX)
        /** Where the move through [NEAR] ends (past it), and how much more steeply the next one falls. */
        val kink = DoubleArray(MAX); val bend = DoubleArray(MAX)
        /** The cell each arrow lands on with no noise, or -1. */
        val cell = IntArray(MAX)
        /** d(crossing x, y)/d(noise), d(slope x, y)/d(noise) and d(kink)/d(noise) per arrow (3 each). */
        val jx = DoubleArray(3 * MAX); val jy = DoubleArray(3 * MAX); val jsx = DoubleArray(3 * MAX); val jsy = DoubleArray(3 * MAX); val jk = DoubleArray(3 * MAX)

        // Per arrow, worked out on the first [chance] after an [eval]: its face point (z 50, before or after the kink),
        // how that moves with the noise (x row, y row) and its sds, and the slopes and kink at their REACH sds.
        private val fx = DoubleArray(MAX); private val fy = DoubleArray(MAX)
        private val jf = DoubleArray(6 * MAX); private val sdf = DoubleArray(2 * MAX)
        private val qxHi = DoubleArray(MAX); private val qxLo = DoubleArray(MAX); private val qyHi = DoubleArray(MAX); private val qyLo = DoubleArray(MAX)
        private val kHi = DoubleArray(MAX); private val kLo = DoubleArray(MAX)

        // Entries: an arrow within reach of a cell.
        private var prepared = false
        private var e = 0
        private val eArrow = IntArray(ME); private val eCell = IntArray(ME)
        /** Per entry: the face's edges as margins from the arrow's face point (left, right, bottom, top). */
        private val face = DoubleArray(4 * ME)
        /**
         * Per entry: how far beyond each edge (left, right, bottom, top) a face point can still come in through that
         * side, then the same through a corner (as wide as anything can come in through the cell's depth), so no draw
         * beyond them lands (at the slopes' [REACH] sds).
         */
        private val wide = DoubleArray(8 * ME)
        /** Per entry: no draw can move it off its face ([sure]); the face decides ([Board.clear]). */
        private val sure = BooleanArray(ME); private val faces = BooleanArray(ME)

        /** Flies the noise-free volley at [yaw]/[pitch]; the cells it covers as a bitmask (bit c: cell c). */
        fun eval(yaw: Float, pitch: Float): Int {
            val s = shooter
            ShotPlan.planInto(plan, s.bow, s.pos.x, s.pos.y, s.pos.z, yaw, pitch, s.crouch, s.stacks, 0.0, 0.0, 0.0, 4)
            n = 0; prepared = false
            var mask = 0
            val p = plan
            arrows@ for (k in 0 until p.n) {
                if (p.delay[k] != 0) continue
                for (o in 0 until k) if (p.delay[o] == 0 && p.v[3 * o] == p.v[3 * k] && p.v[3 * o + 1] == p.v[3 * k + 1] && p.v[3 * o + 2] == p.v[3 * k + 2] &&
                    p.at[3 * o] == p.at[3 * k] && p.at[3 * o + 1] == p.at[3 * k + 1] && p.at[3 * o + 2] == p.at[3 * k + 2]) continue@arrows
                if (!fly(k, n)) continue
                val a = n++
                val c = board.land(x[a], y[a], sx[a], sy[a], kink[a], bend[a])
                cell[a] = c
                if (c >= 0) mask = mask or (1 shl c)
            }
            return mask
        }

        /** Every entry of the last [eval]. */
        private fun prepare() {
            prepared = true
            e = 0
            for (a in 0 until n) {
                val kk = kink[a]; val bd = bend[a]
                // The face point (z 50: before or after the kink) and how it moves.
                val after = kk < 0.5
                for (q in 0 until 3) {
                    jf[6 * a + q] = jx[3 * a + q] + 0.5 * jsx[3 * a + q]
                    jf[6 * a + 3 + q] = jy[3 * a + q] + 0.5 * jsy[3 * a + q] - (if (after) bd * jk[3 * a + q] else 0.0)
                }
                val sfx = NOISE * len(jf, 6 * a); val sfy = NOISE * len(jf, 6 * a + 3)
                sdf[2 * a] = sfx; sdf[2 * a + 1] = sfy
                fx[a] = x[a] + 0.5 * sx[a]; fy[a] = yAt(y[a], sy[a], kk, bd, 0.5)
                val gx = REACH * NOISE * len(jsx, 3 * a); val gy = REACH * NOISE * len(jsy, 3 * a); val gk = REACH * NOISE * len(jk, 3 * a)
                qxHi[a] = sx[a] + gx; qxLo[a] = sx[a] - gx; qyHi[a] = sy[a] + gy; qyLo[a] = sy[a] - gy; kHi[a] = kk + gk; kLo[a] = kk - gk
                // Cells it can reach at all: within REACH sds and what the slopes carry it through a cell's depth.
                val rx = REACH * sfx + maxOf(abs(qxHi[a]), abs(qxLo[a])) * (BACK - 0.5) + 0.5
                val ry = REACH * sfy + (maxOf(abs(qyHi[a]), abs(qyLo[a])) + abs(bd)) * (BACK - 0.5) + 0.5
                for (c in 0 until 9) {
                    if (abs(fx[a] - (64.5 + 2 * (c % 3))) > rx || abs(fy[a] - (126.5 + 2 * (c / 3))) > ry) continue
                    entry(a, c)
                }
            }
        }

        private fun rise(a: Int, d: Double) = maxOf(0.0, qyHi[a] * (d - 0.5) + bend[a] * maxOf(0.0, d - maxOf(kHi[a], 0.5)))
        private fun drop(a: Int, d: Double) = maxOf(0.0, -qyLo[a] * (d - 0.5) - bend[a] * maxOf(0.0, d - maxOf(kLo[a], 0.5)))

        /** Entry for arrow [a] and cell [c], if any draw can bring it in. */
        private fun entry(a: Int, c: Int) {
            if (e == ME) return
            val k = e
            val x0 = 64.0 + 2 * (c % 3); val y0 = 126.0 + 2 * (c / 3)
            val sfx = sdf[2 * a]; val sfy = sdf[2 * a + 1]
            val fL = x0 - fx[a]; val fR = x0 + 1 - fx[a]; val fB = y0 - fy[a]; val fT = y0 + 1 - fy[a]
            val w = 8 * k
            wide[w] = maxOf(0.0, qxHi[a]) * (board.deepest(c, LEFT) - 0.5)
            wide[w + 1] = maxOf(0.0, -qxLo[a]) * (board.deepest(c, RIGHT) - 0.5)
            wide[w + 2] = rise(a, board.deepest(c, BOTTOM))
            wide[w + 3] = drop(a, board.deepest(c, TOP))
            wide[w + 4] = maxOf(0.0, qxHi[a]) * (BACK - 0.5)
            wide[w + 5] = maxOf(0.0, -qxLo[a]) * (BACK - 0.5)
            wide[w + 6] = rise(a, BACK)
            wide[w + 7] = drop(a, BACK)
            // Out of reach: no draw (REACH sds) gets it onto the face, into a band beside it or a corner's.
            val rx = REACH * sfx; val ry = REACH * sfy
            fun meets(x0: Double, x1: Double, y0: Double, y1: Double) = x0 <= rx && x1 >= -rx && y0 <= ry && y1 >= -ry
            if (!meets(fL - wide[w], fR + wide[w + 1], fB, fT) && !meets(fL, fR, fB - wide[w + 2], fT + wide[w + 3]) &&
                !meets(fL - wide[w + 4], fL, fB - wide[w + 6], fB) && !meets(fL - wide[w + 4], fL, fT, fT + wide[w + 7]) &&
                !meets(fR, fR + wide[w + 5], fB - wide[w + 6], fB) && !meets(fR, fR + wide[w + 5], fT, fT + wide[w + 7])) return
            face[4 * k] = fL; face[4 * k + 1] = fR; face[4 * k + 2] = fB; face[4 * k + 3] = fT
            faces[k] = board.clear(c)
            sure[k] = faces[k] && fL < -REACH * sfx && fR > REACH * sfx && fB < -REACH * sfy && fT > REACH * sfy
            eArrow[k] = a; eCell[k] = c; e++
        }

        private fun len(v: DoubleArray, o: Int) = sqrt(v[o] * v[o] + v[o + 1] * v[o + 1] + v[o + 2] * v[o + 2])

        /**
         * Plan arrow [k] flown to [NEAR], into slot [a]: its crossing, kink, bend and Jacobians. Its position after m
         * moves is at + sum of its moves, each 0.99 times the last less g, so it moves with the noise as dAt + C_m dV
         * (C_m = 1 + 0.99 + ... m terms); where it crosses, the crossing moves as (e_x - sx e_z) and (e_y - sy e_z)
         * times that, the slopes as the same of that move, over its z, and the kink as the z of where the move ends.
         * The next move's dx/dz is the same and its dy/dz is lower by g / its z (the bend, taken as fixed: it moves by
         * under 1e-4 with the noise).
         */
        private fun fly(k: Int, a: Int): Boolean {
            val p = plan
            var ax = p.from[3 * k]; var ay = p.from[3 * k + 1]; var az = p.from[3 * k + 2]
            var bx = p.at[3 * k]; var by = p.at[3 * k + 1]; var bz = p.at[3 * k + 2]
            var vx = p.v[3 * k]; var vy = p.v[3 * k + 1]; var vz = p.v[3 * k + 2]
            var cA = 0.0; var cB = 0.0; var pw = 1.0
            for (m in 0 until 60) {
                if (az < NEAR && bz >= NEAR) {
                    val dz = bz - az
                    val f = (NEAR - az) / dz
                    val ssx = (bx - ax) / dz; val ssy = (by - ay) / dz
                    x[a] = ax + (bx - ax) * f; y[a] = ay + (by - ay) * f; sx[a] = ssx; sy[a] = ssy
                    kink[a] = bz - NEAR; bend[a] = if (vz > 0) vy / vz - ssy else 0.0
                    // The first leg runs from [from] to [at]; later ones from at on, the leg's move being 0.99^(m-1) of the first.
                    val dI = if (m == 0) p.dFrom[k] + f * (p.dAt[k] - p.dFrom[k]) else p.dAt[k]
                    val cf = if (m == 0) 0.0 else cA + f * (cB - cA)
                    val leg = if (m == 0) 0.0 else cB - cA
                    val legI = if (m == 0) p.dAt[k] - p.dFrom[k] else 0.0
                    val d = p.dV; val o = 9 * k
                    for (c in 0 until 3) {
                        jx[3 * a + c] = cf * (d[o + c] - ssx * d[o + 6 + c])
                        jy[3 * a + c] = cf * (d[o + 3 + c] - ssy * d[o + 6 + c])
                        jsx[3 * a + c] = leg * (d[o + c] - ssx * d[o + 6 + c]) / dz
                        jsy[3 * a + c] = leg * (d[o + 3 + c] - ssy * d[o + 6 + c]) / dz
                        jk[3 * a + c] = cB * d[o + 6 + c]
                    }
                    jx[3 * a] += dI; jx[3 * a + 2] -= dI * ssx
                    jy[3 * a + 1] += dI; jy[3 * a + 2] -= dI * ssy
                    jsx[3 * a] += legI / dz; jsx[3 * a + 2] -= legI * ssx / dz
                    jsy[3 * a + 1] += legI / dz; jsy[3 * a + 2] -= legI * ssy / dz
                    jk[3 * a + 2] += p.dAt[k]
                    return true
                }
                if (vz <= 0 || by < 100) return false
                ax = bx; ay = by; az = bz
                bx += vx; by += vy; bz += vz
                vx *= 0.99; vy = vy * 0.99 - G; vz *= 0.99
                cA = cB; cB += pw; pw *= 0.99
            }
            return false
        }

        /** The cells of the last [eval] in the order their arrows tick (spawn order), each once, as indices. */
        fun order(mask: Int): IntArray {
            val out = IntArray(Integer.bitCount(mask)); var k = 0; var seen = 0
            for (a in 0 until n) { val c = cell[a]; if (c >= 0 && (mask shr c) and 1 == 1 && (seen shr c) and 1 == 0) { seen = seen or (1 shl c); out[k++] = c } }
            return out
        }

        /**
         * The chance that every cell of [mask] is still hit by some arrow. One cell: at least its likeliest arrow's
         * chance through its face (a bivariate gaussian over a rectangle, closed form), at most the sum over its
         * arrows of that plus every band beside the face they could still come in through ([bounds]); when those
         * differ, the fixed draws decide. Several cells: the draws. Stops early once it can't reach [atLeast] (then
         * returns something under it). [exact] false: when the bounds already put it on one side of [atLeast], that
         * bound is returned (the stance scan only needs the side).
         */
        fun chance(mask: Int, atLeast: Double = 0.0, exact: Boolean = true): Double {
            if (!prepared) prepare()
            var need = 0
            for (c in 0 until 9) {
                if ((mask shr c) and 1 == 0) continue
                var any = false; var certain = false
                for (k in 0 until e) if (eCell[k] == c) { any = true; if (sure[k]) certain = true }
                if (!any) return 0.0
                if (certain) continue
                need = need or (1 shl c)
            }
            if (need == 0) return 1.0
            if (Integer.bitCount(need) == 1) {
                // One cell: at least its likeliest arrow's chance, at most the sum of theirs, each bounded in closed form.
                val c = Integer.numberOfTrailingZeros(need)
                var lo = 0.0; var hi = 0.0; var ok = true
                for (k in 0 until e) if (eCell[k] == c) { if (!bounds(k)) { ok = false; break }; lo = maxOf(lo, bLo); hi += bHi }
                if (ok) {
                    hi = minOf(hi, 1.0)
                    if (hi - lo < 0.002) return (lo + hi) / 2
                    if (!exact) { if (hi < atLeast) return hi; if (lo >= atLeast) return lo }
                }
            }
            return draws(need, atLeast, !exact)
        }

        // What [bounds] found.
        private var bLo = 0.0; private var bHi = 0.0

        /**
         * Entry [k]'s chance of landing, bounded in closed form ([bLo], [bHi]): through its face at least, at most that
         * plus every band beside the face it could still come in through ([wide]). False when its face doesn't decide
         * ([Board.clear]) or its face point's two axes are too correlated for [rect].
         */
        private fun bounds(k: Int): Boolean {
            if (!faces[k]) return false
            val a = eArrow[k]
            val sx = sdf[2 * a]; val sy = sdf[2 * a + 1]
            val rho = (jf[6 * a] * jf[6 * a + 3] + jf[6 * a + 1] * jf[6 * a + 4] + jf[6 * a + 2] * jf[6 * a + 5]) * NOISE * NOISE / (sx * sy)
            if (abs(rho) > 0.3) return false
            val l = face[4 * k] / sx; val r = face[4 * k + 1] / sx; val b = face[4 * k + 2] / sy; val t = face[4 * k + 3] / sy
            val w = 8 * k
            val lo = band(l, r, b, t, rho)
            // Bands beside the face: the sides', then the corners'.
            val wl = wide[w] / sx; val wr = wide[w + 1] / sx; val wb = wide[w + 2] / sy; val wt = wide[w + 3] / sy
            val cl = wide[w + 4] / sx; val cr = wide[w + 5] / sx; val cb = wide[w + 6] / sy; val ct = wide[w + 7] / sy
            var hi = lo + band(l - wl, l, b, t, rho) + band(r, r + wr, b, t, rho) + band(l, r, b - wb, b, rho) + band(l, r, t, t + wt, rho)
            hi += band(l - cl, l, b - cb, b, rho) + band(l - cl, l, t, t + ct, rho) + band(r, r + cr, b - cb, b, rho) + band(r, r + cr, t, t + ct, rho)
            bLo = lo; bHi = minOf(hi, 1.0)
            return true
        }

        /** [rect], 0 for an empty or far-off band. */
        private fun band(l: Double, r: Double, b: Double, t: Double, rho: Double): Double =
            if (r <= l || t <= b || l > REACH || r < -REACH || b > REACH || t < -REACH) 0.0 else rect(l, r, b, t, rho)

        /**
         * [chance] over the fixed draws ([DRAWS]): the share in which every cell of [need] keeps an arrow. [staged]:
         * only which side of [atLeast] it is on matters, so it looks at the first 128, 256, then all 512 draws (each
         * prefix of a Halton sequence is itself evenly spread) and stops once the share is 3 binomial sds clear of
         * the line, returning that side's 3-sd bound.
         */
        private fun draws(need: Int, atLeast: Double, staged: Boolean): Double {
            val d = DRAWS
            val total = d.size / 3
            val maxFail = ((1 - atLeast) * total).toInt()
            var fail = 0
            var stage = if (staged) 128 else total
            for (t in 0 until total) {
                val n0 = d[3 * t]; val n1 = d[3 * t + 1]; val n2 = d[3 * t + 2]
                var ok = true
                var c = 0
                while (c < 9 && ok) {
                    if ((need shr c) and 1 == 1) {
                        var held = false
                        for (k in 0 until e) if (eCell[k] == c && inside(k, n0, n1, n2)) { held = true; break }
                        ok = held
                    }
                    c++
                }
                if (!ok && ++fail > maxFail) return (total - fail).toDouble() / total
                if (t + 1 == stage && stage < total) {
                    val p = 1 - fail.toDouble() / stage
                    val sdp = sqrt(p * (1 - p) / stage) + 1.0 / stage
                    if (p - 3 * sdp >= atLeast) return p - 3 * sdp
                    if (p + 3 * sdp < atLeast) return p + 3 * sdp
                    stage *= 2
                }
            }
            return (total - fail).toDouble() / total
        }

        /**
         * Entry [k]'s arrow, moved by noise ([n0], [n1], [n2]), lands on its cell: through the face; beyond every band
         * it could come in through ([wide]), not; otherwise as [Board.hits] decides for the path that draw makes.
         */
        private fun inside(k: Int, n0: Double, n1: Double, n2: Double): Boolean {
            val a = eArrow[k]; val f = 6 * a
            val dx = jf[f] * n0 + jf[f + 1] * n1 + jf[f + 2] * n2
            val dy = jf[f + 3] * n0 + jf[f + 4] * n1 + jf[f + 5] * n2
            val l = face[4 * k]; val r = face[4 * k + 1]; val b = face[4 * k + 2]; val t = face[4 * k + 3]
            val inX = dx >= l && dx <= r; val inY = dy >= b && dy <= t
            val w = 8 * k
            if (faces[k]) {
                if (inX && inY) return true
                // Beside the face on one axis: no further out than that side's band; off both, than the corner's.
                if (inY) { if (dx < l - wide[w] || dx > r + wide[w + 1]) return false }
                else if (inX) { if (dy < b - wide[w + 2] || dy > t + wide[w + 3]) return false }
                else if (dx < l - wide[w + 4] || dx > r + wide[w + 5] || dy < b - wide[w + 6] || dy > t + wide[w + 7]) return false
            } else if (dx < l - wide[w + 4] || dx > r + wide[w + 5] || dy < b - wide[w + 6] || dy > t + wide[w + 7]) return false
            val o = 3 * a
            return board.hits(eCell[k],
                x[a] + jx[o] * n0 + jx[o + 1] * n1 + jx[o + 2] * n2,
                y[a] + jy[o] * n0 + jy[o + 1] * n1 + jy[o + 2] * n2,
                sx[a] + jsx[o] * n0 + jsx[o + 1] * n1 + jsx[o + 2] * n2,
                sy[a] + jsy[o] * n0 + jsy[o + 1] * n1 + jsy[o + 2] * n2,
                kink[a] + jk[o] * n0 + jk[o + 1] * n1 + jk[o + 2] * n2,
                bend[a])
        }

        companion object {
            const val MAX = ShotPlan.Arrows.MAX
            private const val ME = 4 * MAX
            /** Edges this many sds away are past every draw ([DRAWS] reach about 3.3). */
            private const val REACH = 4.5
        }
    }

    /**
     * P(l < X < r, b < Y < t) for standard normals X, Y of correlation [rho], by the tetrachoric series to rho^3
     * (|rho| <= 0.3: off by under 3e-4).
     */
    fun rect(l: Double, r: Double, b: Double, t: Double, rho: Double): Double {
        val px = phiC(r) - phiC(l); val py = phiC(t) - phiC(b)
        if (rho == 0.0) return px * py
        val fl = pdf(l); val fr = pdf(r); val fb = pdf(b); val ft = pdf(t)
        val h1 = (fr - fl) * (ft - fb)
        val h2 = (r * fr - l * fl) * (t * ft - b * fb)
        val h3 = ((r * r - 1) * fr - (l * l - 1) * fl) * ((t * t - 1) * ft - (b * b - 1) * fb)
        return (px * py + rho * h1 + rho * rho / 2 * h2 + rho * rho * rho / 6 * h3).coerceIn(0.0, 1.0)
    }

    private fun pdf(x: Double): Double = if (abs(x) > 12) 0.0 else exp(-0.5 * x * x) * 0.3989422804014327

    /** The standard normal CDF (erfc to 1.2e-7). */
    fun phiC(x: Double): Double {
        if (x > 12) return 1.0
        if (x < -12) return 0.0
        val z = abs(x) / sqrt(2.0)
        val t = 1 / (1 + 0.5 * z)
        val r = t * exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 + t * (0.27886807 +
            t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))))
        return if (x >= 0) 1 - r / 2 else r / 2
    }

    /**
     * The noise draws for aims of several arrows: [DRAW_COUNT] points of a Halton sequence (bases 2, 3, 5) through
     * the normal quantile, then whitened (mean 0, covariance exactly the identity) and scaled to [NOISE], so they
     * have 1.8's spread exactly and cover it evenly (a few times closer to the true chance than as many random draws).
     */
    val DRAWS: DoubleArray = run {
        val n = DRAW_COUNT
        val z = Array(n) { i -> doubleArrayOf(quantile(halton(i + 1, 2)), quantile(halton(i + 1, 3)), quantile(halton(i + 1, 5))) }
        val mean = DoubleArray(3) { a -> z.sumOf { it[a] } / n }
        for (p in z) for (a in 0 until 3) p[a] -= mean[a]
        val cov = Array(3) { a -> DoubleArray(3) { b -> z.sumOf { it[a] * it[b] } / n } }
        // Cholesky: cov = L L^T; whitened = L^-1 z.
        val l = Array(3) { DoubleArray(3) }
        for (i in 0 until 3) for (j in 0..i) {
            var s = cov[i][j]
            for (k in 0 until j) s -= l[i][k] * l[j][k]
            l[i][j] = if (i == j) sqrt(s) else s / l[j][j]
        }
        val out = DoubleArray(3 * n)
        for ((t, p) in z.withIndex()) {
            val w0 = p[0] / l[0][0]
            val w1 = (p[1] - l[1][0] * w0) / l[1][1]
            val w2 = (p[2] - l[2][0] * w0 - l[2][1] * w1) / l[2][2]
            out[3 * t] = w0 * NOISE; out[3 * t + 1] = w1 * NOISE; out[3 * t + 2] = w2 * NOISE
        }
        out
    }
    const val DRAW_COUNT = 512

    private fun halton(i: Int, base: Int): Double {
        var f = 1.0; var r = 0.0; var k = i
        while (k > 0) { f /= base; r += f * (k % base); k /= base }
        return r
    }

    /** The standard normal quantile (Acklam's, to 1.2e-9). */
    private fun quantile(p: Double): Double {
        val a = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
        val b = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
        val c = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
        val d = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)
        val lo = 0.02425
        return when {
            p < lo -> { val q = sqrt(-2 * ln(p)); (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1) }
            p > 1 - lo -> { val q = sqrt(-2 * ln(1 - p)); -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1) }
            else -> { val q = p - 0.5; val r = q * q; (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1) }
        }
    }

    // ------------------------------------------------------------------ every aim of one stance

    /** The cells of aim mask [m], as indices. */
    fun cellsOf(m: Int): IntArray = (0 until 9).filter { (m shr it) and 1 == 1 }.toIntArray()

    /** The aims worked out: the three rows, the nine cells, and the six pairs side by side. */
    val AIM_MASKS: IntArray = IntArray(18) { a ->
        when {
            a < 3 -> 7 shl (3 * a)
            a < 12 -> 1 shl (a - 3)
            else -> { val r = (a - 12) / 2; val l = (a - 12) % 2; 3 shl (3 * r + l) }
        }
    }

    /**
     * One way to aim: the cells it covers ([mask]), the order its arrows tick in ([order]), its surest spot's
     * chance, and every lattice spot that lands all its cells at the min chance or better, as runs of counts along
     * each lattice row: row j (from [jMin]) has the runs [runs] from [rowStart] [j - jMin] to [rowStart] [j - jMin + 1],
     * each a pair (first i, last i).
     */
    class Aim(val mask: Int, val order: IntArray, val bestChance: Double, val spots: Int, val jMin: Int, val rowStart: IntArray, val runs: IntArray) {
        val cells: IntArray = cellsOf(mask)
        val size: Int get() = cells.size
        /** The board row (0 bottom) when all its cells are in one. */
        val row: Int get() = cells.map { it / 3 }.distinct().singleOrNull() ?: -1
        val jMax: Int get() = jMin + rowStart.size - 2
        fun has(c: Int): Boolean = (mask shr c) and 1 == 1
        /** Whether spot ([i], [j]) is one of its spots (for the offline checks; the paint reads [runs]). */
        fun contains(i: Int, j: Int): Boolean {
            if (j < jMin || j > jMax) return false
            for (k in rowStart[j - jMin] until rowStart[j - jMin + 1]) if (i >= runs[2 * k] && i <= runs[2 * k + 1]) return true
            return false
        }
        override fun toString() = cells.joinToString(",", "[", "]")
    }

    /**
     * Everything that depends on where you stand, what you hold and your mouse: every aim ([AIM_MASKS]) with its spots.
     * [evaluated]: the volleys flown; [millis]: how long it took.
     */
    class StanceGeometry(val board: Board, val shooter: Shooter, val lattice: Lattice, val minChance: Double, val aims: List<Aim>,
                         val evaluated: Int, val millis: Double) {
        /** The aim of [mask] (for the offline checks; the solver walks [aims]). */
        fun aim(mask: Int): Aim? = aims.firstOrNull { it.mask == mask }
    }

    /** Collects one aim's spots, row by row; rows are added in order. */
    private class Runs(rows: Int) {
        val perRow = arrayOfNulls<IntArray>(rows)
        var best = 0.0; var bestI = 0; var bestJ = 0; var count = 0; var order: IntArray? = null; var orderChance = -1.0
    }

    /** One lattice row's spots of every aim (pairs of i), filled by [scanRow]. */
    private class RowOut {
        val runs = Array(18) { IntArrayList() }
        val best = DoubleArray(18)
        val bestI = IntArray(18)
        val count = IntArray(18)
        val order = arrayOfNulls<IntArray>(18)
        val orderChance = DoubleArray(18) { -1.0 }
        var evaluated = 0
        fun clear() { runs.forEach { it.clear() }; best.fill(0.0); count.fill(0); order.fill(null); orderChance.fill(-1.0); evaluated = 0 }
    }

    class IntArrayList {
        private var a = IntArray(16); var size = 0; private set
        fun add(x: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = x }
        operator fun get(i: Int) = a[i]
        fun clear() { size = 0 }
        fun toArray(): IntArray = a.copyOf(size)
    }

    /**
     * Every aim of [shooter] on [board] at [minChance], on [lattice]. Scans each lattice row whose arrows can reach a
     * cell, and every spot along it where they can reach the board; a spot belongs to an aim when its noise-free
     * volley covers every cell of the aim (it may cover more) and [Evaluator.chance] of all of them is at least
     * [minChance]. [pool] (with [parts] > 1) splits the rows. [cancelled]: checked between rows; true gives up (an
     * empty geometry back).
     */
    @JvmOverloads
    fun stance(board: Board, shooter: Shooter, lattice: Lattice, minChance: Double, pool: ExecutorService? = null, parts: Int = 1,
               cancelled: () -> Boolean = { false }): StanceGeometry {
        val t0 = System.nanoTime()
        val eye = shooter.eye
        // Every look whose arrows can reach a cell: the main arrow within reach of the side arrows (+-1.75) of x 64-69.
        val yawLo = lookAt(eye, Vec3(71.3, eye.y, FACE)).first; val yawHi = lookAt(eye, Vec3(61.7, eye.y, FACE)).first
        val pitchLo = lookAt(eye, Vec3(66.5, 133.5, FACE)).second; val pitchHi = lookAt(eye, Vec3(66.5, 124.5, FACE)).second
        val iLo = lattice.i(yawLo) - 1; val iHi = lattice.i(yawHi) + 1
        val jLo = lattice.j(pitchLo) - 1; val jHi = lattice.j(pitchHi) + 1
        val rows = jHi - jLo + 1
        val all = Array(18) { Runs(rows) }
        var evaluated = 0
        fun merge(j: Int, out: RowOut) {
            evaluated += out.evaluated
            for (a in 0 until 18) {
                if (out.runs[a].size == 0) continue
                val r = all[a]
                r.perRow[j - jLo] = out.runs[a].toArray()
                r.count += out.count[a]
                if (out.best[a] > r.best) { r.best = out.best[a]; r.bestI = out.bestI[a]; r.bestJ = j }
                if (out.orderChance[a] > r.orderChance) { r.orderChance = out.orderChance[a]; r.order = out.order[a] }
            }
        }
        val empty = { StanceGeometry(board, shooter, lattice, minChance, emptyList(), 0, (System.nanoTime() - t0) / 1e6) }
        if (pool == null || parts <= 1) {
            val ev = Evaluator(board, shooter); val out = RowOut()
            for (j in jLo..jHi) { if (cancelled()) return empty(); out.clear(); scanRow(ev, lattice, j, iLo, iHi, minChance, out); merge(j, out) }
        } else {
            val tasks = (0 until parts).map { part ->
                Callable {
                    val ev = Evaluator(board, shooter)
                    val mine = ArrayList<Pair<Int, RowOut>>()
                    var j = jLo + part
                    while (j <= jHi && !cancelled()) { val out = RowOut(); scanRow(ev, lattice, j, iLo, iHi, minChance, out); mine += j to out; j += parts }
                    mine
                }
            }
            for (f in pool.invokeAll(tasks)) for ((j, out) in f.get()) merge(j, out)
            if (cancelled()) return empty()
        }
        val aims = ArrayList<Aim>()
        val ev = Evaluator(board, shooter)
        for (a in 0 until 18) {
            val r = all[a]
            if (r.count == 0) continue
            // The scan may have stopped at a bound: the surest spot's chance in full, at that spot and at the middle
            // of a run on up to 12 of its rows (the surest spots are in the middle).
            ev.eval(lattice.yaw(r.bestI).toFloat(), lattice.pitch(r.bestJ).toFloat())
            r.best = maxOf(r.best, ev.chance(AIM_MASKS[a], 0.0))
            val rowsWith = r.perRow.indices.filter { r.perRow[it] != null }
            for (n in 0 until minOf(12, rowsWith.size)) {
                val k = rowsWith[n * rowsWith.size / minOf(12, rowsWith.size)]
                val runs = r.perRow[k]!!
                var w = 0
                for (q in runs.indices step 2) if (runs[q + 1] - runs[q] > runs[w + 1] - runs[w]) w = q
                if (ev.eval(lattice.yaw((runs[w] + runs[w + 1]) / 2).toFloat(), lattice.pitch(jLo + k).toFloat()) and AIM_MASKS[a] == AIM_MASKS[a])
                    r.best = maxOf(r.best, ev.chance(AIM_MASKS[a], 0.0))
            }
            val first = r.perRow.indexOfFirst { it != null }; val last = r.perRow.indexOfLast { it != null }
            val rowStart = IntArray(last - first + 2)
            val runs = IntArrayList()
            for (k in first..last) {
                rowStart[k - first] = runs.size / 2
                r.perRow[k]?.let { for (x in it) runs.add(x) }
            }
            rowStart[last - first + 1] = runs.size / 2
            aims += Aim(AIM_MASKS[a], r.order ?: cellsOf(AIM_MASKS[a]), r.best, r.count, jLo + first, rowStart, runs.toArray())
        }
        return StanceGeometry(board, shooter, lattice, minChance, aims, evaluated, (System.nanoTime() - t0) / 1e6)
    }

    /** One lattice row [j]: every spot from [iLo] to [iHi] into [out], skipped whole when no arrow can reach a cell row. */
    private fun scanRow(ev: Evaluator, l: Lattice, j: Int, iLo: Int, iHi: Int, min: Double, out: RowOut) {
        val pitch = l.pitch(j).toFloat()
        // The main arrow's height along the row (5 samples; it is smooth): no cell row within reach, nothing here.
        var yMin = Double.MAX_VALUE; var yMax = -Double.MAX_VALUE
        for (k in 0..4) {
            ev.eval(l.yaw(iLo + (iHi - iLo) * k / 4).toFloat(), pitch)
            for (a in 0 until ev.n) { yMin = minOf(yMin, ev.y[a]); yMax = maxOf(yMax, ev.y[a]) }
        }
        if (yMin == Double.MAX_VALUE) return
        val reach = (0 until 3).any { r -> yMax + 0.25 >= 126.0 + 2 * r && yMin - 0.25 <= 127.0 + 2 * r }
        if (!reach) return
        val run = IntArray(18) { Int.MIN_VALUE }; val end = IntArray(18)
        val p = DoubleArray(9)
        fun close(a: Int) { if (run[a] != Int.MIN_VALUE) { out.runs[a].add(run[a]); out.runs[a].add(end[a]); run[a] = Int.MIN_VALUE } }
        fun add(a: Int, i: Int, chance: Double) {
            if (run[a] != Int.MIN_VALUE && end[a] == i - 1) end[a] = i else { close(a); run[a] = i; end[a] = i }
            out.count[a]++
            if (chance > out.best[a]) { out.best[a] = chance; out.bestI[a] = i }
            if (chance > out.orderChance[a]) { out.orderChance[a] = chance; out.order[a] = ev.order(AIM_MASKS[a]) }
        }
        for (i in iLo..iHi) {
            out.evaluated++
            val mask = ev.eval(l.yaw(i).toFloat(), pitch)
            if (mask == 0) continue
            var good = 0
            for (c in 0 until 9) {
                if ((mask shr c) and 1 == 0) continue
                p[c] = ev.chance(1 shl c, min, exact = false)
                if (p[c] >= min) { good = good or (1 shl c); add(3 + c, i, p[c]) }
            }
            // Several cells: no likelier than the least likely one, at least 1 - the sum of their misses; the draws
            // only where those bounds straddle the line.
            for (r in 0 until 3) {
                for (lft in 0 until 2) {
                    val m = 3 shl (3 * r + lft)
                    if (good and m != m) continue
                    val lo = 1 - (1 - p[3 * r + lft]) - (1 - p[3 * r + lft + 1])
                    val ch = if (lo >= min) lo else ev.chance(m, min, exact = false)
                    if (ch >= min) add(12 + 2 * r + lft, i, ch)
                }
                val m = 7 shl (3 * r)
                if (good and m != m) continue
                val lo = 1 - (1 - p[3 * r]) - (1 - p[3 * r + 1]) - (1 - p[3 * r + 2])
                val ch = if (lo >= min) lo else ev.chance(m, min, exact = false)
                if (ch >= min) add(r, i, ch)
            }
        }
        for (a in 0 until 18) close(a)
    }
}
