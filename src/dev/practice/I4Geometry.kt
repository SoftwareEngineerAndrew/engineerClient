package com.devgineerclient.practice

import com.engineerclient.p3sim.ShotPlan
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The i4 board and what one volley hits on it, for every crosshair spot your mouse can reach. Pure (no world, no
 * client), so it tests offline; [I4Aims] feeds it the board's real blocks and draws what it finds.
 *
 * The arrows are Hypixel's, measured ([ShotPlan]): the main arrow carries 1.8's aim noise (a gaussian of sd 0.0075 on
 * each axis of the unit look), Terror's two Hydra Strike arrows (10 stacks, +-8 deg) inherit it from the main arrow's
 * move, and the Terminator's side arrows (+-5.5 deg of your clean look) have none. So a cell a side arrow lands on is
 * certain, and one the main or a Hydra arrow lands on is a chance, worked out here over a fixed set of noise draws.
 *
 * The crosshair moves in whole mouse counts: each turns you [mouseStep] degrees (Minecraft's (s*0.6+0.2)^3*8 at
 * sensitivity s, x 0.15), so the spots you can reach from your look are a lattice of that pitch, and [Lattice] lists
 * them. Recordings: every recorded mouse turn is a whole multiple of that step.
 */
object I4Geometry {
    val CELLS: List<BlockPos> = (0 until 9).map { BlockPos(64 + (it % 3) * 2, 126 + (it / 3) * 2, 50) }

    /** The cells' face: they are full blocks at z 50. */
    const val FACE = 50.0
    /** Arrows are followed tick by tick up to here, then in a straight line through the board (it bends ~0.01 in that last stretch). */
    private const val NEAR = 49.5
    private const val FAR = 52.0
    private const val G = 0.05

    /** Degrees one mouse count turns you, at sensitivity [s] (Options' 0..1). */
    fun mouseStep(s: Double): Double { val d = s * 0.6 + 0.2; return d * d * d * 8.0 * 0.15 }

    /**
     * The board's blocks as boxes: each cell's ([cellOf] its index in [CELLS]) and whatever else an arrow can hit
     * around them (the panes between the cells, the frame).
     */
    class Board(val boxes: List<AABB>, val cellOf: IntArray) {
        /** The boxes by block column (x, y), so a line only tests the few it passes. */
        private val columns = HashMap<Long, IntArray>().also { m ->
            val lists = HashMap<Long, ArrayList<Int>>()
            boxes.forEachIndexed { i, b ->
                for (x in floor(b.minX).toInt()..floor(b.maxX - 1e-9).toInt()) for (y in floor(b.minY).toInt()..floor(b.maxY - 1e-9).toInt())
                    lists.getOrPut(key(x, y)) { ArrayList() } += i
            }
            lists.forEach { (k, v) -> m[k] = v.toIntArray() }
        }
        private fun key(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
        fun column(x: Int, y: Int): IntArray? = columns[key(x, y)]
    }

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

    /** Where an arrow comes through z = [NEAR]: the point and its slopes (dx/dz, dy/dz). */
    class Crossing(val x: Double, val y: Double, val sx: Double, val sy: Double)

    /** The arrow that left [from], is at [at] and moves [v] next, flown as 1.8 does (move, then x0.99 and -0.05) to [NEAR]. */
    fun cross(from: Vec3, at: Vec3, v: Vec3): Crossing? {
        var a = from; var b = at; var vel = v
        repeat(80) {
            if (a.z < NEAR && b.z >= NEAR) {
                val dz = b.z - a.z
                val f = (NEAR - a.z) / dz
                return Crossing(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, (b.x - a.x) / dz, (b.y - a.y) / dz)
            }
            if (vel.z <= 0 || b.y < 100) return null
            a = b; b = b.add(vel)
            vel = Vec3(vel.x * 0.99, vel.y * 0.99 - G, vel.z * 0.99)
        }
        return null
    }

    /** What the straight line through [c], shifted by ([dx], [dy]), hits first: a cell's index, or -1 (anything else, or nothing). */
    fun hit(board: Board, c: Crossing, dx: Double = 0.0, dy: Double = 0.0): Int {
        val px = c.x + dx; val py = c.y + dy
        var best = Double.MAX_VALUE; var cell = -1
        val boxes = board.boxes
        val reach = FAR - NEAR
        val x0 = floor(minOf(px, px + c.sx * reach)).toInt(); val x1 = floor(maxOf(px, px + c.sx * reach)).toInt()
        val y0 = floor(minOf(py, py + c.sy * reach)).toInt(); val y1 = floor(maxOf(py, py + c.sy * reach)).toInt()
        for (x in x0..x1) for (y in y0..y1) {
            val col = board.column(x, y) ?: continue
            for (i in col) {
                val t = enter(boxes[i], px, py, c.sx, c.sy)
                if (t < best) { best = t; cell = board.cellOf[i] }
            }
        }
        return if (best == Double.MAX_VALUE) -1 else cell
    }

    /** The z-distance past [NEAR] at which the line enters [b], or MAX_VALUE if it doesn't before [FAR]. */
    private fun enter(b: AABB, px: Double, py: Double, sx: Double, sy: Double): Double {
        var t0 = b.minZ - NEAR; var t1 = b.maxZ - NEAR
        if (t1 < 0) return Double.MAX_VALUE
        if (sx == 0.0) { if (px < b.minX || px > b.maxX) return Double.MAX_VALUE }
        else {
            var a = (b.minX - px) / sx; var c = (b.maxX - px) / sx
            if (a > c) { val s = a; a = c; c = s }
            if (a > t0) t0 = a; if (c < t1) t1 = c
        }
        if (sy == 0.0) { if (py < b.minY || py > b.maxY) return Double.MAX_VALUE }
        else {
            var a = (b.minY - py) / sy; var c = (b.maxY - py) / sy
            if (a > c) { val s = a; a = c; c = s }
            if (a > t0) t0 = a; if (c < t1) t1 = c
        }
        if (t0 > t1 || t0 > FAR - NEAR) return Double.MAX_VALUE
        return t0.coerceAtLeast(0.0)
    }

    /** The fixed noise draws: 1.8's three gaussians x 0.0075, in +- pairs so the set is centred. */
    val NOISE: List<Vec3> = java.util.Random(0x14).let { r ->
        (0 until 48).flatMap { val n = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).scale(0.0075); listOf(n, n.scale(-1.0)) }
    }

    /** Who shoots, and how: the inputs every spot's volley depends on besides the look. */
    /**
     * Who shoots, from where. [legacy]: a 1.8 server (Hypixel), where sneaking lowers the eye (and the arrows) by only
     * 0.08 to 1.54; a modern one (P3 Sim) drops it to 1.27.
     */
    class Shooter(val bow: String, val pos: Vec3, val crouch: Boolean, val stacks: Int, val legacy: Boolean = true) {
        val eye: Vec3 get() = Vec3(pos.x, pos.y + if (!crouch) 1.62 else if (legacy) 1.54 else 1.27, pos.z)
    }

    /**
     * One volley at [yaw]/[pitch]: the cell each arrow lands on with no noise (-1: none), and, worked out when first
     * asked, how far each arrow's crossing moves per unit of noise ([jx], [jy]: x and y per noise axis; all 0 for an
     * arrow the noise doesn't move).
     */
    class Volley(val cells: IntArray, val crossings: Array<Crossing?>, private val moved: BooleanArray, private val shift: () -> Pair<Array<DoubleArray>, Array<DoubleArray>>) {
        private val j by lazy(LazyThreadSafetyMode.NONE, shift)
        val jx: Array<DoubleArray> get() = j.first
        val jy: Array<DoubleArray> get() = j.second
        /** Whether the noise moves arrow [i] at all (the Terminator's side arrows: no). */
        fun noisy(i: Int): Boolean = moved[i]
        /** The cells the volley hits with no noise. */
        val covers: Set<Int> get() = cells.filter { it >= 0 }.toSet()
        /** Those cells in the order the server ticks their arrows (spawn order), each once. */
        val order: List<Int> get() = cells.filter { it >= 0 }.distinct()
    }

    private fun plan(s: Shooter, yaw: Float, pitch: Float, noise: Vec3) =
        if (s.crouch && s.legacy) ShotPlan.plan(s.bow, s.pos.add(0.0, -0.08, 0.0), yaw, pitch, false, s.stacks, noise, 4).filter { it.delay == 0 }
        else ShotPlan.plan(s.bow, s.pos, yaw, pitch, s.crouch, s.stacks, noise, 4).filter { it.delay == 0 }

    fun volley(board: Board, s: Shooter, yaw: Float, pitch: Float): Volley {
        val arrows = plan(s, yaw, pitch, Vec3.ZERO)
        val clean = arrows.map { cross(it.from, it.at, it.v) }
        val n = clean.size
        val cells = IntArray(n) { i -> clean[i]?.let { hit(board, it) } ?: -1 }
        val probe = plan(s, yaw, pitch, Vec3(0.0075, 0.0075, 0.0075))
        val moved = BooleanArray(n) { i -> probe.getOrNull(i)?.let { it.v.distanceToSqr(arrows[i].v) > 1e-12 || it.at.distanceToSqr(arrows[i].at) > 1e-12 } ?: false }
        return Volley(cells, clean.toTypedArray(), moved) {
            val jx = Array(n) { DoubleArray(3) }; val jy = Array(n) { DoubleArray(3) }
            val h = 0.0075
            for (axis in 0 until 3) {
                val e = Vec3(if (axis == 0) h else 0.0, if (axis == 1) h else 0.0, if (axis == 2) h else 0.0)
                val up = plan(s, yaw, pitch, e).map { cross(it.from, it.at, it.v) }
                val down = plan(s, yaw, pitch, e.scale(-1.0)).map { cross(it.from, it.at, it.v) }
                for (i in 0 until n) {
                    val a = up.getOrNull(i) ?: continue; val b = down.getOrNull(i) ?: continue
                    jx[i][axis] = (a.x - b.x) / (2 * h); jy[i][axis] = (a.y - b.y) / (2 * h)
                }
            }
            jx to jy
        }
    }

    /**
     * The chance (over [NOISE]) that every cell of [want] is still hit by some arrow: a cell a noise-free arrow (a
     * Terminator's side arrow) lands on is certain however close the noisy ones beside it are to its edge.
     */
    fun chance(board: Board, v: Volley, want: Set<Int>): Double {
        val sure = v.cells.indices.filter { v.cells[it] in want && !v.noisy(it) }.map { v.cells[it] }.toSet()
        val idx = v.cells.indices.filter { v.cells[it] in want && v.cells[it] !in sure }
        if (idx.isEmpty()) return 1.0
        val need = want - sure
        var ok = 0
        val got = HashSet<Int>()
        for (n in NOISE) {
            got.clear()
            for (i in idx) {
                val c = v.crossings[i]!!
                val dx = v.jx[i][0] * n.x + v.jx[i][1] * n.y + v.jx[i][2] * n.z
                val dy = v.jy[i][0] * n.x + v.jy[i][1] * n.y + v.jy[i][2] * n.z
                if (hit(board, c, dx, dy) == v.cells[i]) got += v.cells[i]
            }
            if (got.containsAll(need)) ok++
        }
        return ok.toDouble() / NOISE.size
    }

    /** Your look at [at] (yaw, pitch) from [eye]. */
    fun lookAt(eye: Vec3, at: Vec3): Pair<Double, Double> {
        val d = at.subtract(eye)
        return Math.toDegrees(atan2(-d.x, d.z)) to -Math.toDegrees(atan2(d.y, hypot(d.x, d.z)))
    }

    /** Where your crosshair at [yaw]/[pitch] sits on the plane z = [z] (null if you look away from it). */
    fun onPlane(eye: Vec3, yaw: Double, pitch: Double, z: Double = FACE): Vec3? {
        val y = Math.toRadians(yaw); val p = Math.toRadians(pitch)
        val dz = cos(y) * cos(p)
        if (dz <= 1e-6) return null
        val t = (z - eye.z) / dz
        return Vec3(eye.x - sin(y) * cos(p) * t, eye.y - sin(p) * t, z)
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

    /** One spot you can reach that lands every arrow of its aim: lattice counts and the chance. */
    class Pixel(val i: Int, val j: Int, val chance: Double)

    /** One way to aim: the cells it covers, every spot that does it at [Aims.minChance] or better, and the best one. */
    class Aim(val cells: Set<Int>, val pixels: List<Pixel>, val best: Pixel)

    /**
     * Every aim the board offers you now: scans the board coarsely (every [coarse] degrees) for the cell sets a
     * volley covers, keeps those whose best spot reaches [minChance], then lists each one's reachable spots on
     * [lattice]. [keep] picks which of the coarse aims get the fine pass (it's the costly one).
     */
    fun aims(board: Board, s: Shooter, lattice: Lattice, minChance: Double, coarse: Double = 0.2,
             keep: (List<Coarse>) -> List<Coarse> = { it }): List<Aim> =
        keep(scan(board, s, minChance, coarse)).parallelStream().map { c -> fine(board, s, lattice, minChance, c, coarse) }.toList().filterNotNull()

    /**
     * The coarse pass alone: the cell sets a volley covers from [s] (every [coarse] degrees), each with its best chance,
     * those reaching [minChance]. It depends on where you stand and what you hold, not on the board's state.
     */
    fun scan(board: Board, s: Shooter, minChance: Double, coarse: Double = 0.2): List<Coarse> {
        val eye = s.eye
        val (y0, p0) = lookAt(eye, Vec3(62.5, 133.0, FACE))
        val (y1, p1) = lookAt(eye, Vec3(70.5, 124.5, FACE))
        // Each pitch row on its own thread, merged after.
        val rows = generateSequence(p0) { it + coarse }.takeWhile { it <= p1 }.toList()
        val found = HashMap<Set<Int>, Coarse>()
        rows.parallelStream().map { p ->
            val mine = HashMap<Set<Int>, Coarse>()
            var y = y1
            while (y <= y0) {
                val v = volley(board, s, y.toFloat(), p.toFloat())
                val cov = v.covers
                if (cov.isNotEmpty()) mine.getOrPut(cov) { Coarse(cov, v.order) }.add(y, p, chance(board, v, cov), minChance)
                y += coarse
            }
            mine
        }.toList().forEach { m -> m.forEach { (k, c) -> found.getOrPut(k) { Coarse(k, c.order) }.merge(c) } }
        return found.values.filter { it.bestChance >= minChance }
    }

    fun fine(board: Board, s: Shooter, lattice: Lattice, minChance: Double, c: Coarse, coarse: Double = 0.2): Aim? {
        val iRange = lattice.i(c.minYaw - coarse)..lattice.i(c.maxYaw + coarse)
        // Each pitch row on its own thread.
        val pixels = (lattice.j(c.minPitch - coarse)..lattice.j(c.maxPitch + coarse)).toList().parallelStream().flatMap { j ->
            val row = ArrayList<Pixel>()
            for (i in iRange) {
                val v = volley(board, s, lattice.yaw(i).toFloat(), lattice.pitch(j).toFloat())
                // A spot that also covers more cells is only better: keep it, or the aim gets holes where a bigger one starts.
                if (!v.covers.containsAll(c.cells)) continue
                val ch = chance(board, v, c.cells)
                if (ch >= minChance) row += Pixel(i, j, ch)
            }
            row.stream()
        }.toList()
        if (pixels.isEmpty()) return null
        // The best spot: the surest, then the one with the most room around it (counts to the nearest spot that isn't
        // as sure), so being a count or two off still lands it.
        val top = pixels.maxOf { it.chance }
        val sure = pixels.filter { it.chance >= top - 1e-9 }
        val room = depth(sure.map { it.i to it.j })
        return Aim(c.cells, pixels, sure.maxBy { room[it.i to it.j] ?: 0 })
    }

    /** For each spot of [spots], how many steps (4 ways) it is from the nearest spot outside them. */
    private fun depth(spots: List<Pair<Int, Int>>): Map<Pair<Int, Int>, Int> {
        val inside = spots.toHashSet()
        val d = HashMap<Pair<Int, Int>, Int>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        fun near(p: Pair<Int, Int>) = listOf(p.first + 1 to p.second, p.first - 1 to p.second, p.first to p.second + 1, p.first to p.second - 1)
        for (p in spots) if (near(p).any { it !in inside }) { d[p] = 1; queue += p }
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for (q in near(p)) if (q in inside && q !in d) { d[q] = d[p]!! + 1; queue += q }
        }
        return d
    }

    /**
     * A coarse aim: its cells (and [order], the order its arrows tick in), the box of looks it was found at, its best
     * chance and how many coarse spots reach [minChance].
     */
    class Coarse(val cells: Set<Int>, val order: List<Int> = cells.toList()) {
        var minYaw = Double.MAX_VALUE; var maxYaw = -Double.MAX_VALUE
        var minPitch = Double.MAX_VALUE; var maxPitch = -Double.MAX_VALUE
        var bestChance = 0.0; var good = 0
        fun add(yaw: Double, pitch: Double, chance: Double, min: Double) {
            if (chance > bestChance) bestChance = chance
            if (chance < min) return
            good++
            minYaw = minOf(minYaw, yaw); maxYaw = maxOf(maxYaw, yaw); minPitch = minOf(minPitch, pitch); maxPitch = maxOf(maxPitch, pitch)
        }
        fun merge(o: Coarse) {
            bestChance = maxOf(bestChance, o.bestChance); good += o.good
            minYaw = minOf(minYaw, o.minYaw); maxYaw = maxOf(maxYaw, o.maxYaw); minPitch = minOf(minPitch, o.minPitch); maxPitch = maxOf(maxPitch, o.maxPitch)
        }
    }
}
