package com.devgineerclient.practice

import com.engineerclient.p3sim.Bows
import com.devgineerclient.practice.I4Geometry.Aim
import com.devgineerclient.practice.I4Geometry.Board
import com.devgineerclient.practice.I4Geometry.StanceGeometry
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * i4's board as you can know it, and where to aim: NOW and NEXT, picked from the aims of the current
 * [StanceGeometry] (the solver never works geometry out, it only picks). Client thread only; every call is cheap
 * (arrays of 9, at most 18 aims), so it runs again on every event.
 *
 * Time is the board's clock ([I4Aims]): the server's ticks (one unbundled ping each, on Hypixel and in P3 Sim alike),
 * so a lag spike or a freeze stretches nothing here; client ticks only while no ping comes.
 *
 * How the board works (Hypixel recordings, ~/Cluade/maxor-work/i4lag): the device counts a hit the tick the arrow
 * lands and draws the next target then, uniformly from the cells not yet counted; only its emerald waits, for the
 * attempt's 10-server-tick grid (1-10 ticks). A hit on that not-yet-shown target counts too. A hit on the shown one
 * goes blue 1 tick later. Strikes before the attempt's first emerald don't count. Arrows tick in spawn order.
 *
 * The policy (simulated against your recordings, ~/Cluade/maxor-work/i4sim: 4.1 s from the first emerald, against
 * 5.4 s today) keeps, per cell, the chance it is still uncounted ([u]) and the chance it is the target now ([pi]).
 * Every arrow on its way to the board counts as landed the moment it is seen ([strike]: its cell and impact tick,
 * from [I4Arrows]), so the aims move on while it flies; a strike on the shown cell that is still lit [ALLOW] ticks
 * after it should have turned it blue missed, and is taken back.
 * - Before the first emerald: the middle row.
 * - A shown target no arrow is on its way to: its best aim, one volley (with Terror, its row).
 * - Otherwise: the aim covering the most chance of being the target, +0.03 for the row of the last NOW and +0.03 for
 *   the middle row.
 * - With Terror (10 Hydra stacks: a whole row per volley), only the three row aims, unless every row has at most one
 *   cell that can still be uncounted.
 * NEXT is the same pick after NOW's volley lands as aimed (the shown target certainly, other cells at a row
 * volley's measured rates): where to go while the cooldown runs.
 */
class I4Solver {
    /** Per cell, as known from the board and the strikes that have landed: still uncounted, and the target now. */
    private val u = DoubleArray(9) { 1.0 }
    private val pi = DoubleArray(9)
    /** An emerald has shown this attempt: before it, strikes don't count. */
    var started = false; private set
    /** The lit cell (index), or -1. */
    var lit = -1; private set
    /** Your "completed a device!" came while at the board: nothing left to aim at until a target lights again. */
    var complete = false; private set
    private var completedAt = 0
    /** Cells gone emerald to blue this attempt (bits). */
    private var marked = 0
    /** The tick of the last emerald or blue. */
    private var lastBoard = Int.MIN_VALUE / 2
    /**
     * You stepped off the plate within the last few ticks ([I4Aims]): the device drops the attempt then (the lit cell
     * goes blue uncounted, as P3 Sim models it), so a blue no arrow of yours is on is that reset, not a count.
     */
    var offPlate = false

    /**
     * An arrow on its way to the board: the cell it lands on (-1: none) and the tick it lands ([impact]).
     * [verify]: the shown cell it is on (it then counts as hit until [deadline]; still lit then, it missed).
     */
    class Strike(val key: Long, var cell: Int, var impact: Int, val seq: Int) {
        var verify = -1
        var deadline = 0
    }
    private val pending = ArrayList<Strike>()
    private var seq = 0

    // What the board is with every pending strike landed (what the aims are picked from).
    private val vu = DoubleArray(9); private val vpi = DoubleArray(9)
    private var dirty = true
    private var lastNowRow = -1

    /** One attempt's counts, for its summary line. */
    class Counts { var lights = 0; var blues = 0; var strikes = 0; var rollbacks = 0 }
    /** This attempt's so far. */
    var counts = Counts(); private set
    /** The attempt a restart just ended (taken by the caller for its summary), or null. */
    var ended: Counts? = null

    fun reset() {
        u.fill(1.0); pi.fill(0.0); started = false; lit = -1; complete = false; marked = 0; lastBoard = Int.MIN_VALUE / 2
        pending.clear(); dirty = true; lastNowRow = -1; counts = Counts(); ended = null; planned = null
    }

    fun completed(now: Int) { complete = true; completedAt = now; dirty = true }

    /** Cell [i] lit (emerald) at [now]. */
    fun emerald(i: Int, now: Int) {
        if (complete && !((marked shr i) and 1 == 1 && now - completedAt > CATCH_UP)) {
            // The board catching up after your completion line (its last light or blue can come after it).
            lit = i; lastBoard = now
            return
        }
        val view = view()
        val restart = !started || complete || (marked shr i) and 1 == 1 ||
            (view.first[i] <= 0.05 && pending.none { it.cell == i }) ||
            (lit < 0 && now - lastBoard > DARK)
        if (restart) {
            if (started) { ended = counts; counts = Counts() }
            started = true; complete = false; u.fill(1.0); marked = 0
            // Before the attempt's first emerald nothing counted.
            pending.removeAll { it.impact < now }
        } else {
            // What landed before it showed is counted in what it shows (an arrow due a tick earlier on this cell is
            // taken as still on its way: predictions run a tick early now and then).
            commitWhere { it.impact < now && !(it.cell == i && it.impact >= now - 1) }
        }
        counts.lights++
        lit = i; lastBoard = now
        pi.fill(0.0); pi[i] = 1.0; u[i] = 1.0
        // An arrow already on its way to it: it hits the shown target.
        for (p in pending) if (p.cell == i) { p.verify = i; p.deadline = maxOf(p.deadline, deadline(maxOf(p.impact, now))) }
        dirty = true
    }

    /** Cell [i] went from emerald to blue (counted) at [now]. */
    fun blue(i: Int, now: Int) {
        counts.blues++
        if (lit == i) lit = -1
        lastBoard = now
        if (!started) return
        if (offPlate && !complete && pending.none { it.cell == i }) {
            // The attempt was dropped: the next emerald starts a new one.
            ended = counts; counts = Counts().also { it.blues = 1 }
            started = false; u.fill(1.0); pi.fill(0.0); marked = 0; pending.clear(); dirty = true
            return
        }
        marked = marked or (1 shl i)
        if (complete) return
        // The first strike on it is what turned it blue (the rest do nothing on a counted cell). Strikes that landed
        // before that one (earlier ticks, or earlier in its tick) landed while it was the target: counted as they were.
        val hit = pending.filter { it.cell == i }.minWithOrNull(ORDER)
        pending.removeAll { it.cell == i }
        if (hit != null) commitWhere { ORDER.compare(it, hit) < 0 } else commitWhere { it.verify < 0 && it.impact < now - 1 }
        u[i] = 0.0
        if (lit >= 0) {
            // The next target's emerald came first (same tick): it is the target, whatever the count drew.
            normalize(u); pi.fill(0.0); pi[lit] = 1.0
        } else redraw(u, pi)
        if (marked == 0x1ff) marked = 0
        dirty = true
    }

    /** At [now]: strikes that have landed are counted; one on a still-lit shown cell past its deadline missed. */
    fun tick(now: Int) {
        if (pending.isEmpty()) return
        val it = pending.iterator()
        var changed = false
        val done = ArrayList<Strike>(0)
        while (it.hasNext()) {
            val p = it.next()
            if (p.verify >= 0) {
                if (lit == p.verify && now > p.deadline) { it.remove(); counts.rollbacks++; changed = true }
                else if (lit != p.verify && now > p.impact + 1) { it.remove(); done += p; changed = true }
            } else if (now > p.impact + 2) { it.remove(); done += p; changed = true }
        }
        for (p in done) commit(p)
        if (changed) dirty = true
    }

    /**
     * An arrow ([key]) will land on [cell] (-1: none) at [impact]: counts as landed now. The same key again replaces
     * it (a better prediction from the arrow's own packets).
     */
    fun strike(key: Long, cell: Int, impact: Int) {
        val p = pending.firstOrNull { it.key == key } ?: Strike(key, cell, impact, seq++).also { pending += it; counts.strikes++ }
        p.cell = cell; p.impact = impact
        p.verify = if (started && !complete && lit >= 0 && cell == lit) lit else -1
        p.deadline = deadline(impact)
        pending.sortWith(ORDER)
        dirty = true
    }

    /** When a strike on the shown cell landing at [impact] must have turned it blue (1 tick later; [ALLOW] for packets bunching up). */
    private fun deadline(impact: Int) = impact + 1 + ALLOW

    private inline fun commitWhere(cond: (Strike) -> Boolean) {
        val due = pending.filter(cond)
        pending.removeAll(due.toSet())
        for (p in due) commit(p)
    }

    private fun commit(p: Strike) { if (started && p.cell >= 0) strike(u, pi, p.cell, 1.0) }

    /** u and pi with every pending strike landed, in the order they land. */
    private fun view(): Pair<DoubleArray, DoubleArray> {
        if (dirty) {
            u.copyInto(vu); pi.copyInto(vpi)
            if (started) for (p in pending) if (p.cell >= 0) strike(vu, vpi, p.cell, 1.0)
            dirty = false
        }
        return vu to vpi
    }

    /** NOW and NEXT. */
    class Pick(val now: Aim?, val next: Aim?)

    // The last plan and what it was made from: the same board, geometry and settings give the same plan.
    private var planned: Pick? = null; private var plannedGeo: StanceGeometry? = null; private var plannedNext = false
    private var plannedLit = -2; private var plannedStarted = false
    private val plannedU = DoubleArray(9); private val plannedPi = DoubleArray(9)

    /** NOW and NEXT from [geo]'s aims as the board is ([showNext]: NEXT too). */
    fun plan(geo: StanceGeometry?, showNext: Boolean): Pick {
        if (geo == null || complete) return Pick(null, null)
        val (vu, vpi) = view()
        val last = planned
        if (last != null && geo === plannedGeo && showNext == plannedNext && lit == plannedLit && started == plannedStarted &&
            vu.contentEquals(plannedU) && vpi.contentEquals(plannedPi)) return last
        val p = choose(geo, showNext, vu, vpi)
        planned = p; plannedGeo = geo; plannedNext = showNext; plannedLit = lit; plannedStarted = started
        vu.copyInto(plannedU); vpi.copyInto(plannedPi)
        return p
    }

    private fun choose(geo: StanceGeometry, showNext: Boolean, vu: DoubleArray, vpi: DoubleArray): Pick {
        val all = candidates(geo.aims, vu)
        if (all.isEmpty()) return Pick(null, null)
        if (!started) {
            val mid = all.maxWithOrNull(compareBy({ Math.round(1000 * it.bestChance * it.cells.count { c -> c / 3 == 1 }) }, { it.spots }))
            lastNowRow = mid?.row ?: -1
            return Pick(mid, null)
        }
        val shown = lit >= 0 && vpi[lit] > 0.5
        val now = if (shown) {
            val target = lit
            val rest = (0 until 9).filter { it != target && vu[it] > 0.5 }
            fun expected(cells: List<Int>): Double { var e = 0.0; for (k in cells.indices) if (rest.size - k > 0) e += 1.0 / (rest.size - k); return e }
            all.filter { it.has(target) }.maxWithOrNull(compareBy({ a ->
                Math.round(1000 * a.bestChance * (1 + expected(a.order.toList().dropWhile { c -> c != target }.drop(1).filter { c -> c in rest })))
            }, { it.spots }))
        } else guess(all, vpi, lastNowRow)
        if (now != null) lastNowRow = now.row
        if (now == null || !showNext) return Pick(now, null)
        // NEXT: NOW's volley lands as aimed. The shown target is hit; its other cells at a row volley's rates.
        val u2 = vu.copyOf(); val pi2 = vpi.copyOf()
        for (c in now.order) strike(u2, pi2, c, if (shown && c == lit) 1.0 else if (now.size == 3) ROW_Q[c % 3] else now.bestChance)
        val next = guess(candidates(geo.aims, u2), pi2, now.row)
        return Pick(now, next?.takeIf { it.mask != now.mask })
    }

    /**
     * The aims to choose from. With Hydra Strike (a whole row in one volley) only the three row aims, unless every
     * row has at most one cell that can still be uncounted: then any aim, to hit those cells directly.
     */
    private fun candidates(all: List<Aim>, u: DoubleArray): List<Aim> {
        val rows = all.filter { it.size == 3 && it.row >= 0 }
        if (rows.size < 3) return all
        val onePerRow = (0 until 3).all { r -> (0 until 3).count { c -> u[r * 3 + c] > 0.05 } <= 1 }
        return if (onePerRow) all else rows
    }

    /** The aim covering the most chance of being the target, +0.03 on row [row] and +0.03 on the middle row. */
    private fun guess(all: List<Aim>, pi: DoubleArray, row: Int): Aim? =
        all.maxWithOrNull(compareBy({ a ->
            val r = a.row
            Math.round(1000 * (a.bestChance * a.cells.sumOf { pi[it] } + (if (r >= 0 && r == row) 0.03 else 0.0) + (if (r == 1) 0.03 else 0.0)))
        }, { it.spots }))?.takeIf { a -> a.cells.sumOf { pi[it] } > 1e-6 }

    /** For the log: the board as known, with pending strikes. */
    fun describe(): String {
        val (vu, vpi) = view()
        return "lit=$lit pi=${vpi.joinToString(",") { "%.2f".format(it) }} u=${vu.joinToString(",") { "%.2f".format(it) }} pending=${pending.size}"
    }

    companion object {
        /** Per-cell strike rates of a row volley, R/C/L column (x 64/66/68; recordings), for volleys not yet in the air. */
        private val ROW_Q = doubleArrayOf(0.67, 0.73, 0.54)
        /**
         * After a count the next emerald is never more than one 10-tick grid period away; dark for longer (in server
         * ticks, so a freeze doesn't count), the attempt was reset.
         */
        private const val DARK = 20
        /** Ticks after the shown target should have gone blue before its strike is taken back. */
        private const val ALLOW = 4
        /** After your completion line the board's last light and blue can still come (recordings: within a tick or two). */
        private const val CATCH_UP = 20
        private val ORDER = compareBy<Strike>({ it.impact }, { it.seq })

        /** A strike on [c] that counts with [q] if [c] is the target: the target is then redrawn from the uncounted cells. */
        fun strike(u: DoubleArray, pi: DoubleArray, c: Int, q: Double) {
            val h = q * pi[c]
            if (h <= 0.0) return
            pi[c] -= h; u[c] -= h
            var rest = 0.0
            for (o in 0 until 9) if (o != c) rest += u[o]
            if (rest > 0) for (o in 0 until 9) if (o != c) pi[o] += h * u[o] / rest
            val sum = u.sum()
            if (sum in 1e-9..1.0) for (o in 0 until 9) u[o] /= sum
        }

        /** At least one cell is uncounted while the attempt runs: [u] scaled up to sum to 1 when it sums to less. */
        private fun normalize(u: DoubleArray) {
            val sum = u.sum()
            if (sum < 1.0 && sum > 1e-9) for (o in 0 until 9) u[o] /= sum
        }

        /** The target is redrawn uniformly from the uncounted cells. */
        fun redraw(u: DoubleArray, pi: DoubleArray) {
            normalize(u)
            val s2 = u.sum()
            for (o in 0 until 9) pi[o] = if (s2 > 0) u[o] / s2 else 0.0
        }
    }
}

/**
 * Arrows on their way to the board, from their packets (client thread): where each lands and when, as a strike for
 * [I4Solver] the moment it is known. Times are the board's clock (see [I4Solver]).
 *
 * Yours: the main arrow's spawn packet names you (its data is the shooter's entity id; Hypixel's other arrows of a
 * volley carry their own id, P3 Sim's none). From it alone the whole volley is rebuilt as the server flies it
 * ([com.engineerclient.p3sim.ShotPlan]): on Hypixel the spawn packet is the arrow after its first tick, at a position
 * floored to 1/32 (+1/64 puts it back in the middle) moving v0, and its next move is k*v0 (k = 1 + 0.01 x Hydra
 * stacks); Hydra Strike's two arrows make that move turned +-8 deg from the same point; the Terminator's side arrows
 * leave 0.5 under it at your look +-5.5 deg at that speed, with a gravity step given back. Each is flown tick by tick
 * onto the board's real boxes ([I4Geometry.landing]). In P3 Sim every spawn packet already carries the exact start
 * and next move.
 *
 * Then each arrow's own packets refine it (and are all there is for teammates' arrows, which count on the board
 * too): on Hypixel an arrow's first motion packet after its spawn is its velocity after its first move, m, so that
 * move was (m + g) / 0.99, from its spawn point; in P3 Sim the spawn packet is exact. Your volley's other arrows are
 * the ones that spawn at its point in its own tick (a tick later at most); Duplex's copies come 3-5 ticks later and
 * are arrows of their own.
 */
class I4Arrows(private val solver: I4Solver) {
    private class Tracked(val id: Int, val x: Double, val y: Double, val z: Double, val vx: Double, val vy: Double, val vz: Double, val tick: Int,
                          val volley: Volley?) {
        var known = false
    }

    /** One of your volleys: its slots (main, Hydra +x, Hydra -x, Terminator -x, +x) and where each crosses. */
    private class Volley(val mainId: Int, val x: Double, val y: Double, val z: Double, val tick: Int) {
        val keys = ArrayList<Long>(); val crossX = ArrayList<Double>(); val crossY = ArrayList<Double>(); val refined = ArrayList<Boolean>()
    }

    private val arrows = HashMap<Int, Tracked>()
    private val volleys = ArrayList<Volley>()

    /** What the last volley did, for the log (null when nothing new). */
    var lastVolley: String? = null

    fun clear() { arrows.clear(); volleys.clear() }

    /**
     * An arrow's spawn packet: entity [id] at [x], [y], [z] moving [vx], [vy], [vz], [data] its shooter's id (or its
     * own, or 0), at [now]. [me]: your entity id. [stacks]: your Hydra stacks; [bow]: the bow you shoot.
     */
    fun added(id: Int, x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double, data: Int, me: Int, now: Int,
              board: Board, bow: String, stacks: Int, sim: Boolean) {
        if (!watched(x, y, z)) return
        // Hypixel's positions are floored to 1/32.
        val q = if (sim) 0.0 else 1.0 / 64
        val px = x + q; val py = y + q; val pz = z + q
        arrows.entries.removeIf { now - it.value.tick > 40 }
        volleys.removeIf { now - it.tick > 40 }
        if (data == me) {
            val k = 1 + 0.01 * stacks
            // The move it makes next: P3 Sim's spawn packet has it; Hypixel's is the arrow after its first tick.
            val mx = if (sim) vx else k * vx; val my = if (sim) vy else k * vy; val mz = if (sim) vz else k * vz
            val v = Volley(id, px, py, pz, now)
            volleys += v
            arrows[id] = Tracked(id, px, py, pz, vx, vy, vz, now, v).also { it.known = true }
            val parts = ArrayList<String>()
            fun slot(name: String, fx: Double, fy: Double, fz: Double, ax: Double, ay: Double, az: Double) {
                val key = id.toLong() * 16 + v.keys.size
                val f = I4Geometry.fly(Vec3(fx, fy, fz), Vec3(fx, fy, fz), Vec3(ax, ay, az))
                val (cell, ticks) = land(board, f)
                v.keys += key; v.crossX += f?.crossing?.x ?: Double.NaN; v.crossY += f?.crossing?.y ?: Double.NaN; v.refined += false
                solver.strike(key, cell, now + ticks)
                parts += "$name:$cell@+$ticks"
            }
            slot("main", px, py, pz, mx, my, mz)
            if (stacks >= 10) for ((name, deg) in listOf("h+x" to -8.0, "h-x" to 8.0)) {
                val c = cos(Math.toRadians(deg)); val s = sin(Math.toRadians(deg))
                slot(name, px, py, pz, mx * c - mz * s, my, mx * s + mz * c)
            }
            if (bow == Bows.TERMINATOR) {
                // Your look, from this move (with its noise; the side arrows have none, a ~0.1 block difference
                // their own packets correct a tick later): u = (move/k + g) / 0.99.
                val ux = mx / k / 0.99; val uy = (my / k + 0.05) / 0.99; val uz = mz / k / 0.99
                val ul = sqrt(ux * ux + uy * uy + uz * uz); val ml = sqrt(mx * mx + my * my + mz * mz)
                for ((name, deg) in listOf("t-x" to 5.5, "t+x" to -5.5)) {
                    val c = cos(Math.toRadians(deg)); val s = sin(Math.toRadians(deg))
                    val dx = ux / ul * ml; val dy = uy / ul * ml; val dz = uz / ul * ml
                    slot(name, px, py - 0.5, pz, dx * c - dz * s, dy + 0.05, dx * s + dz * c)
                }
            }
            lastVolley = "id=$id t=$now ${parts.joinToString(" ")}"
            return
        }
        // One of your volley's other arrows: spawned with it (its tick, or the next), at its point or 0.5 under.
        val mine = volleys.lastOrNull { now - it.tick in 0..1 && id > it.mainId && Math.abs(px - it.x) < 0.3 && Math.abs(pz - it.z) < 0.3 && py - it.y in -0.8..0.3 }
        val t = Tracked(id, px, py, pz, vx, vy, vz, now, mine)
        arrows[id] = t
        if (sim && (vx != 0.0 || vy != 0.0 || vz != 0.0)) known(t, px, py, pz, vx, vy, vz, now, board)
    }

    /** An arrow's motion packet ([id], velocity [vx], [vy], [vz]); on Hypixel the first new one after its spawn is its velocity after its first move. */
    fun motion(id: Int, vx: Double, vy: Double, vz: Double, now: Int, board: Board, sim: Boolean) {
        val t = arrows[id] ?: return
        if (sim || t.known) {
            // Your main arrow: its first new motion gives its move exactly (k included).
            if (!sim && t.volley?.mainId == id && t.volley.refined.isNotEmpty() && !t.volley.refined[0] && !same(vx, vy, vz, t.vx, t.vy, t.vz))
                refine(t.volley, 0, t.x, t.y, t.z, (vx) / 0.99, (vy + 0.05) / 0.99, (vz) / 0.99, now, board)
            return
        }
        if (same(vx, vy, vz, t.vx, t.vy, t.vz) || (vx == 0.0 && vy == 0.0 && vz == 0.0)) return
        known(t, t.x, t.y, t.z, vx / 0.99, (vy + 0.05) / 0.99, vz / 0.99, now, board)
    }

    private fun same(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double) =
        Math.abs(ax - bx) < 1e-6 && Math.abs(ay - by) < 1e-6 && Math.abs(az - bz) < 1e-6

    /**
     * Arrow [t]'s move is now known ([mx], [my], [mz] from [x], [y], [z]): one of your volley's slots not yet
     * refined (never the main arrow's: that one is yours by its id), or an arrow of its own.
     */
    private fun known(t: Tracked, x: Double, y: Double, z: Double, mx: Double, my: Double, mz: Double, now: Int, board: Board) {
        t.known = true
        val f = I4Geometry.fly(Vec3(x, y, z), Vec3(x, y, z), Vec3(mx, my, mz))
        val v = t.volley
        if (v != null && f != null) {
            // The slot it is: the one predicted to cross nearest (a Terminator's side arrows come twice, identical).
            var best = -1; var dist = 0.6
            for (s in 1 until v.keys.size) {
                if (v.refined[s]) continue
                val d = Math.hypot(v.crossX[s] - f.crossing.x, v.crossY[s] - f.crossing.y)
                if (d < dist) { dist = d; best = s }
            }
            if (best >= 0) { refine(v, best, x, y, z, mx, my, mz, now, board); return }
        }
        val (cell, ticks) = land(board, f)
        // Its own key; ticks from its spawn.
        solver.strike(t.id.toLong() * 16 + 15, cell, t.tick + ticks)
    }

    private fun refine(v: Volley, s: Int, x: Double, y: Double, z: Double, mx: Double, my: Double, mz: Double, now: Int, board: Board) {
        val f = I4Geometry.fly(Vec3(x, y, z), Vec3(x, y, z), Vec3(mx, my, mz))
        val (cell, ticks) = land(board, f)
        v.refined[s] = true
        if (f != null) { v.crossX[s] = f.crossing.x; v.crossY[s] = f.crossing.y }
        solver.strike(v.keys[s], cell, v.tick + ticks)
    }

    /** Where flight [f] lands (a cell, or -1) and in how many moves. */
    private fun land(board: Board, f: I4Geometry.Flight?): Pair<Int, Int> {
        if (f == null) return -1 to 20
        val l = I4Geometry.landing(board, f.crossing)
        return l.cell to f.ticksTo(if (l.depth.isNaN()) 0.5 else l.depth)
    }

    companion object {
        /**
         * Arrows that can reach the board: spawned in front of it, from the plate's room or the floor below (teammates
         * shoot up from y 116-118), but not the room's dispensers behind the plate (block centres at z 30.1875 and
         * 31.1875: none of their hits on a lit cell ever counted).
         */
        fun watched(x: Double, y: Double, z: Double): Boolean =
            x in 50.0..80.0 && y in 110.0..142.0 && z in 28.0..49.5 && !(z < 34 && x - floor(x) == 0.5 && y - floor(y) == 0.5)
    }
}
