package com.engineerclient.waterboard

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** One lever flip, at the server tick its packet should come back with. [lever]: [Geometry.LEVERS] index. */
data class Flip(val tick: Int, val lever: Int)

/**
 * A plan from a board state: the flips, in tick order, and what simulating them gives. [solvedAt]: the tick every gate is open
 * ([WaterSim.NONE] if never within the horizon); [window]: per flip, how many ticks early and late it can be and still solve
 * as well (within [Planner.SLACK] ticks): what the player has to hit.
 */
class Plan(val flips: List<Flip>, val solvedAt: Int, val cost: Double, val window: List<IntRange> = emptyList()) {
    val solved get() = solvedAt != WaterSim.NONE
    override fun toString() = "Plan(${flips.joinToString { "${Geometry.LEVERS[it.lever]}@${it.tick}" }} -> ${if (solved) solvedAt else "unsolved"})"
}

/**
 * Finds lever flips that open every gate as soon as possible from any board state: the real one, whatever was flipped
 * before (early, late, wrong, extra), with water anywhere. Simulated annealing over timed flip lists, each candidate
 * simulated to the end on [WaterSim]; a person has to be able to do it, so flips are spaced by the walk between levers.
 *
 * [seeds]: plans to start from (the last plan, still mostly right after a small deviation; a known plan for a fresh board).
 */
class Planner(private val spacing: Spacing = Spacing()) {

    /** Ticks a person needs between flipping lever a and lever b (walking within reach, clicking). */
    class Spacing(val minGap: Int = 6, val reach: Double = 3.0, val blocksPerTick: Double = 0.28) {
        private val table = Array(7) { a -> IntArray(7) { b -> gap(a, b) } }
        private fun gap(a: Int, b: Int): Int {
            val pa = Geometry.LEVER_POS[a]; val pb = Geometry.LEVER_POS[b]
            val d = hypot((pa[0] - pb[0]).toDouble(), (pa[2] - pb[2]).toDouble())
            val walk = max(0.0, d - 2 * reach)
            return max(minGap, minGap + Math.ceil(walk / blocksPerTick).toInt())
        }
        fun between(a: Int, b: Int) = table[a][b]
        /** Ticks to get from (x, z) (room coordinates) to within reach of a lever and flip it. */
        fun from(x: Double, z: Double, lever: Int): Int {
            val p = Geometry.LEVER_POS[lever]
            val walk = max(0.0, hypot(p[0] + 0.5 - x, p[2] + 0.5 - z) - 4.0)
            return 2 + Math.ceil(walk / blocksPerTick).toInt()
        }
    }

    /**
     * Best plan found within [budgetMs] from [base] (flips can't be before [base].time + 1). [first]: the earliest tick each
     * lever can first be flipped (from where the player is), or null for no limit. [horizon]: ticks simulated at most.
     */
    fun plan(base: WaterSim, seeds: List<List<Flip>>, budgetMs: Long, rnd: Random = Random.Default, first: IntArray? = null,
             horizon: Int = 700, threads: Int = THREADS): Plan {
        val t0 = base.time + 1
        val levers = base.geo.slotLever.distinct().sorted().toIntArray()
        val starts = (seeds.map { normalize(it, t0) } + listOf(emptyList())).distinct()
        val budgetNs = budgetMs * 1_000_000
        val seedsRnd = LongArray(threads) { rnd.nextLong() }
        // independent annealers from the same starts, each its own scratch board; the best wins
        val runs = if (threads <= 1) listOf(anneal(Evaluator(base, t0, horizon, spacing, first), starts, budgetNs, Random(seedsRnd[0]), levers))
            else (0 until threads).map { k -> pool.submit<Pair<List<Flip>, Double>> { anneal(Evaluator(base, t0, horizon, spacing, first), starts, budgetNs, Random(seedsRnd[k]), levers) } }.map { it.get() }
        val eval = Evaluator(base, t0, horizon, spacing, first)
        val flips = polish(eval, runs.minByOrNull { it.second }!!.first)
        val solved = eval.solvedAt(flips)
        return Plan(flips, solved, eval.cost(flips), if (solved != WaterSim.NONE) windows(eval, flips, solved) else emptyList())
    }

    private fun anneal(eval: Evaluator, starts: List<List<Flip>>, budgetNs: Long, rnd: Random, levers: IntArray): Pair<List<Flip>, Double> {
        val begin = System.nanoTime()
        var best: Pair<List<Flip>, Double>? = null
        for (s in starts) { val c = eval.cost(s); if (best == null || c < best.second) best = s to c }
        var cur = best!!.first; var curCost = best.second
        var iter = 0
        // temperature in cost units (ticks): from a few water steps down to under a tick
        val temp0 = 60.0; val temp1 = 0.5
        while (true) {
            val el = System.nanoTime() - begin
            if (el >= budgetNs) break
            iter++
            val temp = temp0 * Math.pow(temp1 / temp0, el.toDouble() / budgetNs)
            val cand = mutate(cur, eval.t0, rnd, levers)
            val c = eval.cost(cand)
            if (c < curCost || rnd.nextDouble() < exp((curCost - c) / temp)) {
                cur = cand; curCost = c
                if (c < best!!.second) best = cand to c
            }
            // now and then go back to the best (the board punishes wandering far)
            if (iter % 400 == 0 && curCost > best!!.second + 30) { cur = best.first; curCost = best.second }
        }
        return best!!
    }

    /**
     * The found plan made as easy as it stays as fast: flips (and same-lever pairs) dropped while that costs nothing, then each
     * flip nudged a tick or two where that helps.
     */
    private fun polish(eval: Evaluator, start: List<Flip>): List<Flip> {
        var cur = start; var c = eval.cost(cur)
        var changed = true
        while (changed) {
            changed = false
            for (i in cur.indices.reversed()) {
                if (i >= cur.size) continue
                val t = cur.toMutableList().also { it.removeAt(i) }
                val tc = eval.cost(t)
                if (tc <= c) { cur = t; c = tc; changed = true }
            }
            loop@ for (i in cur.indices) for (j in i + 1 until cur.size) {
                if (cur[i].lever != cur[j].lever) continue
                val t = cur.filterIndexed { k, _ -> k != i && k != j }
                val tc = eval.cost(t)
                if (tc <= c) { cur = t; c = tc; changed = true; break@loop }
            }
            for (i in cur.indices) for (d in intArrayOf(-2, -1, 1, 2)) {
                val tick = cur[i].tick + d
                if (tick < eval.t0) continue
                val t = cur.toMutableList().also { it[i] = Flip(tick, cur[i].lever) }.sortedBy { it.tick }
                val tc = eval.cost(t)
                if (tc < c) { cur = t; c = tc; changed = true }
            }
        }
        return cur
    }

    /** Scores [flips] from [base] as the planner would (tests, and checking an old plan on a new state). */
    fun score(base: WaterSim, flips: List<Flip>, first: IntArray? = null, horizon: Int = 700): Plan {
        val t0 = base.time + 1
        val eval = Evaluator(base, t0, horizon, spacing, first)
        val f = normalize(flips, t0)
        val solved = eval.solvedAt(f)
        return Plan(f, solved, eval.cost(f), if (solved != WaterSim.NONE) windows(eval, f, solved) else emptyList())
    }

    private fun windows(eval: Evaluator, flips: List<Flip>, solved: Int): List<IntRange> = flips.indices.map { i ->
        fun ok(d: Int): Boolean {
            val t = flips[i].tick + d
            if (t < eval.t0) return false
            val moved = flips.toMutableList().also { it[i] = Flip(t, flips[i].lever) }.sortedBy { it.tick }
            val s = eval.solvedAt(moved)
            return s != WaterSim.NONE && s <= solved + SLACK && eval.wrongs(moved) == 0
        }
        var lo = 0; while (lo > -WINDOW_PROBE && ok(lo - 1)) lo--
        var hi = 0; while (hi < WINDOW_PROBE && ok(hi + 1)) hi++
        lo..hi
    }

    private fun normalize(f: List<Flip>, t0: Int): List<Flip> = f.map { if (it.tick < t0) Flip(t0, it.lever) else it }.sortedBy { it.tick }

    private fun mutate(p: List<Flip>, t0: Int, rnd: Random, levers: IntArray): List<Flip> {
        val f = p.toMutableList()
        val last = f.maxOfOrNull { it.tick } ?: t0
        fun lever() = if (rnd.nextInt(8) == 0) WaterSim.WATER_LEVER else levers[rnd.nextInt(levers.size)]
        when (if (f.isEmpty()) 0 else rnd.nextInt(12)) {
            0, 1 -> f += Flip(t0 + rnd.nextInt(max(80, last - t0 + 80)), lever())               // add a flip
            2 -> f.removeAt(rnd.nextInt(f.size))                                                 // drop one
            3 -> { val i = rnd.nextInt(f.size); f[i] = Flip(f[i].tick, lever()) }                 // another lever
            4 -> {                                                                                // a lever out and back
                val lv = lever(); val t = t0 + rnd.nextInt(max(80, last - t0 + 60))
                f += Flip(t, lv); f += Flip(t + 10 + rnd.nextInt(120), lv)
            }
            5, 6 -> {                                                                             // everything from one flip on, earlier or later
                val i = rnd.nextInt(f.size)
                val d = if (rnd.nextBoolean()) rnd.nextInt(-12, 13) else rnd.nextInt(-3, 4)
                val from = f.sortedBy { it.tick }[i].tick
                for (k in f.indices) if (f[k].tick >= from) f[k] = Flip(max(t0, f[k].tick + d), f[k].lever)
            }
            else -> {                                                                             // move one, mostly a little
                val i = rnd.nextInt(f.size)
                val d = if (rnd.nextInt(4) == 0) rnd.nextInt(-40, 41) else rnd.nextInt(-6, 7)
                f[i] = Flip(max(t0, f[i].tick + d), f[i].lever)
            }
        }
        return f.sortedBy { it.tick }
    }

    /** Simulates flip lists from one state; scratch reused. */
    private class Evaluator(val base: WaterSim, val t0: Int, val horizon: Int, val spacing: Spacing, val first: IntArray?) {
        private val sim = base.copy()
        private var lastWrongs = 0

        fun solvedAt(f: List<Flip>): Int { run(f); return sim.solvedAt }
        fun wrongs(f: List<Flip>): Int { run(f); return lastWrongs }

        private fun run(f: List<Flip>) {
            sim.setFrom(base)
            val wrong0 = base.wrongHits
            var i = 0
            val end = t0 + horizon
            while (sim.time < end) {
                var bits = 0
                while (i < f.size && f[i].tick == sim.time + 1) { bits = bits xor (1 shl f[i].lever); i++ }
                sim.step(bits)
                if (sim.solvedAt != WaterSim.NONE) break
                // nothing left to happen: stop early
                if (i >= f.size && !sim.moving()) break
            }
            lastWrongs = sim.wrongHits - wrong0
        }

        fun cost(f: List<Flip>): Double {
            run(f)
            var c: Double = if (sim.solvedAt != WaterSim.NONE) (sim.solvedAt - t0).toDouble()
                else 10_000.0 + 1_000.0 * Integer.bitCount(sim.closed)
            c += FLIP_COST * f.size + 15.0 * lastWrongs
            // a person must be able to do it
            for (k in f.indices) {
                if (k == 0) { first?.let { val need = t0 + it[f[0].lever]; if (f[0].tick < need) c += 20.0 * (need - f[0].tick) } ; continue }
                val need = spacing.between(f[k - 1].lever, f[k].lever)
                val gap = f[k].tick - f[k - 1].tick
                if (gap < need) c += 20.0 * (need - gap)
            }
            // flips after it's solved are pointless
            if (sim.solvedAt != WaterSim.NONE) c += 5.0 * f.count { it.tick > sim.solvedAt }
            return c
        }
    }

    companion object {
        /** A flip may be off by this much and still count (ticks later solved). */
        const val SLACK = 10
        const val WINDOW_PROBE = 20
        /** What a flip costs, in ticks of solve time: each is a walk, a click, a chance to get it wrong. */
        const val FLIP_COST = 12.0
        val THREADS = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors() - 2))
        private val pool = java.util.concurrent.Executors.newFixedThreadPool(THREADS) { r -> Thread(r, "ec-waterboard-planner").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }
}
