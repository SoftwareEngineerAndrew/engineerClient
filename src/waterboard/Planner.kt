package com.engineerclient.waterboard

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/** One lever flip, at the server tick its packet should come back with. [lever]: [Geometry.LEVERS] index. */
data class Flip(val tick: Int, val lever: Int)

/**
 * A plan from a board state: the flips, in tick order, and what simulating them gives. [solvedAt]: the tick every gate is open,
 * to stay open [Planner.HOLD] ticks ([WaterSim.NONE] if never within the horizon); [window]: per flip, how many ticks early
 * and late it can be and still solve as well (within [Planner.SLACK] ticks): what the player has to hit.
 */
class Plan(val flips: List<Flip>, val solvedAt: Int, val cost: Double, val window: List<IntRange> = emptyList()) {
    val solved get() = solvedAt != WaterSim.NONE
    override fun toString() = "Plan(${flips.joinToString { "${Geometry.LEVERS[it.lever]}@${it.tick}" }} -> ${if (solved) solvedAt else "unsolved"})"
}

/**
 * What a plan is chosen for. Every objective has to be doable by a person (flips spaced by the walk between levers); they
 * differ in what they trade for what.
 *  - [FASTEST]: the soonest solve on the board as simulated, nothing else.
 *  - [SAFEST]: the soonest solve that still solves when the server's random ticks move water early and clicks land a tick or
 *    two off: scored as the mean over Monte Carlo trials, a trial that fails costing as much as a slow solve.
 *  - [EASIEST]: few clicks, little walking, room between them; solve time traded in.
 *  - [BALANCED]: [SAFEST]'s trials with half of [EASIEST]'s ease.
 */
enum class Objective(private val label: String) {
    FASTEST("Fastest"), SAFEST("Safest"), EASIEST("Easiest"), BALANCED("Balanced");
    override fun toString() = label
    val trials: Boolean get() = this == SAFEST || this == BALANCED
}

/**
 * Finds lever flips that open every gate as soon as possible from any board state: the real one, whatever was flipped
 * before (early, late, wrong, extra), with water anywhere. Simulated annealing over timed flip lists, each candidate
 * simulated to the end on [WaterSim]; a person has to be able to do it, so flips are spaced by the walk between levers.
 *
 * [seeds]: plans to start from (the last plan, still mostly right after a small deviation; a known plan for a fresh board).
 * [hold]: ticks every gate has to stay open at once for it to count ([WaterSim.hold]).
 */
class Planner(private val spacing: Spacing = Spacing(), private val hold: Int = HOLD) {

    /**
     * Ticks a person needs between flipping lever a and lever b (walking within reach, clicking): about 16 ticks between
     * levers 11 blocks apart, 4 between two flips of one lever; the game's interaction range is 4.5 blocks and a sprint 0.28
     * blocks a tick.
     */
    class Spacing(val minGap: Int = 4, val reach: Double = 4.5, val blocksPerTick: Double = 0.28) {
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
     * [iterations]: candidates each annealer tries at most; the search is done at that many or when the time is up.
     */
    fun plan(base: WaterSim, seeds: List<List<Flip>>, budgetMs: Long, rnd: Random = Random.Default, first: IntArray? = null,
             horizon: Int = 700, threads: Int = THREADS, objective: Objective = Objective.FASTEST, trials: Int = LIVE_TRIALS,
             iterations: Int = Int.MAX_VALUE): Plan {
        val t0 = base.time + 1
        val levers = base.geo.slotLever.distinct().sorted().toIntArray()
        val starts = (seeds.map { normalize(it, t0) } + listOf(emptyList())).distinct()
        val budgetNs = budgetMs * 1_000_000
        val seedsRnd = LongArray(threads) { rnd.nextLong() }
        // independent annealers from the same starts, each its own scratch board; the best wins
        fun ev() = Evaluator(base, t0, horizon, spacing, first, hold, objective, trials)
        val runs = if (threads <= 1) listOf(anneal(ev(), starts, budgetNs, iterations, Xorwow(seedsRnd[0]), levers))
            else (0 until threads).map { k -> pool.submit<List<Pair<List<Flip>, Double>>> { anneal(ev(), starts, budgetNs, iterations, Xorwow(seedsRnd[k]), levers) } }.map { it.get() }
        val eval = ev()
        val pick = if (!objective.trials) runs.flatten().minByOrNull { it.second }!!.first
            else {
                // the trials' seeds are the same for every candidate, so the search can fit them: choose among the best it found
                // on trials it never saw
                fun check() = Evaluator(base, t0, horizon, spacing, first, hold, objective, if (trials >= Planner.TABLE_TRIALS) TABLE_CHECK else LIVE_CHECK, seedBase = 0x0c4ec4L)
                val cands = (starts + runs.flatten().map { it.first }).distinct()
                // each worker's cheapest of its share (the first of equals), costs that can't beat it cut short
                fun cheapest(k: Int, step: Int): Pair<Int, Double> {
                    val c = check()
                    var at = -1; var low = Double.POSITIVE_INFINITY
                    for (i in k until cands.size step step) { val v = c.cost(cands[i], low); if (v < low) { low = v; at = i } }
                    return at to low
                }
                val found = if (threads <= 1) listOf(cheapest(0, 1)) else (0 until threads).map { k -> pool.submit<Pair<Int, Double>> { cheapest(k, threads) } }.map { it.get() }
                cands[found.filter { it.first >= 0 }.minWith(compareBy({ it.second }, { it.first })).first]
            }
        val flips = polish(eval, pick)
        val solved = eval.solvedAt(flips)
        return Plan(flips, solved, eval.cost(flips), if (solved != WaterSim.NONE) windows(eval, flips, solved) else emptyList())
    }

    /** The best plans one annealer found ([KEEP] of them, distinct, best first). */
    private fun anneal(eval: Evaluator, starts: List<List<Flip>>, budgetNs: Long, iterations: Int, rnd: Xorwow, levers: IntArray): List<Pair<List<Flip>, Double>> {
        val begin = System.nanoTime()
        var best: Pair<List<Flip>, Double>? = null
        val top = java.util.TreeMap<Double, List<Flip>>()
        fun keep(f: List<Flip>, c: Double) {
            if (top.size >= KEEP && c >= top.lastKey()) return
            if (top.containsValue(f)) return
            top[c] = f
            while (top.size > KEEP) top.pollLastEntry()
        }
        for (s in starts) { val c = eval.cost(s); keep(s, c); if (best == null || c < best.second) best = s to c }
        var cur = best!!.first; var curCost = best.second
        var iter = 0
        // temperature in cost units (ticks): from a few water steps down to under a tick
        val temp0 = 60.0; val temp1 = 0.5
        while (true) {
            val el = System.nanoTime() - begin
            if (el >= budgetNs || iter >= iterations) break
            iter++
            val temp = temp0 * Math.pow(temp1 / temp0, max(el.toDouble() / budgetNs, iter.toDouble() / iterations))
            val cand = mutate(cur, eval.t0, rnd, levers)
            // the draw a worse candidate is accepted on, taken first: it says how bad one may be, and a candidate is only
            // simulated until it is known to be worse than that. A better one is taken without it, so it is put back.
            rnd.mark()
            val u = rnd.nextDouble()
            val c = eval.cost(cand, refusedFrom(curCost, u, temp))
            if (c < curCost) rnd.reset()
            if (c < curCost || u < exp((curCost - c) / temp)) {
                cur = cand; curCost = c
                keep(cand, c)
                if (c < best!!.second) best = cand to c
            }
            // now and then go back to the best (the board punishes wandering far)
            if (iter % 400 == 0 && curCost > best!!.second + 30) { cur = best.first; curCost = best.second }
        }
        return top.entries.map { it.value to it.key }
    }

    /** A cost every candidate at or above which is turned down, for the acceptance draw [u]. */
    private fun refusedFrom(curCost: Double, u: Double, temp: Double): Double {
        fun refused(c: Double) = c >= curCost && !(u < exp((curCost - c) / temp))
        var b = curCost - temp * ln(u)
        if (b.isNaN() || b == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY
        b += abs(b) * 1e-9 + 1e-6
        var n = 0
        while (!refused(b)) { if (++n > 8) return Double.POSITIVE_INFINITY; b += max(1.0, abs(b) * 1e-6) }
        return b
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
                val tc = eval.cost(t, Math.nextUp(c))
                if (tc <= c) { cur = t; c = tc; changed = true }
            }
            loop@ for (i in cur.indices) for (j in i + 1 until cur.size) {
                if (cur[i].lever != cur[j].lever) continue
                val t = cur.filterIndexed { k, _ -> k != i && k != j }
                val tc = eval.cost(t, Math.nextUp(c))
                if (tc <= c) { cur = t; c = tc; changed = true; break@loop }
            }
            for (i in cur.indices) for (d in intArrayOf(-2, -1, 1, 2)) {
                val tick = cur[i].tick + d
                if (tick < eval.t0) continue
                val t = cur.toMutableList().also { it[i] = Flip(tick, cur[i].lever) }.sortedBy { it.tick }
                val tc = eval.cost(t, c)
                if (tc < c) { cur = t; c = tc; changed = true }
            }
        }
        return cur
    }

    /** Scores [flips] from [base] as the planner would (tests, and checking an old plan on a new state). */
    fun score(base: WaterSim, flips: List<Flip>, first: IntArray? = null, horizon: Int = 700,
              objective: Objective = Objective.FASTEST, trials: Int = LIVE_TRIALS): Plan {
        val t0 = base.time + 1
        val eval = Evaluator(base, t0, horizon, spacing, first, hold, objective, trials)
        val f = normalize(flips, t0)
        val solved = eval.solvedAt(f)
        return Plan(f, solved, eval.cost(f), if (solved != WaterSim.NONE) windows(eval, f, solved) else emptyList())
    }

    private fun windows(eval: Evaluator, flips: List<Flip>, solved: Int): List<IntRange> = flips.indices.map { i ->
        fun ok(d: Int): Boolean {
            val t = flips[i].tick + d
            if (t < eval.t0) return false
            val moved = flips.toMutableList().also { it[i] = Flip(t, flips[i].lever) }.sortedBy { it.tick }
            return eval.solvesCleanlyBy(moved, solved + SLACK)
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
    private class Evaluator(val base: WaterSim, val t0: Int, val horizon: Int, val spacing: Spacing, val first: IntArray?, val hold: Int,
                            val objective: Objective = Objective.FASTEST, val trials: Int = LIVE_TRIALS, seedBase: Long = 0x5eed_0000L) {
        private val sim = base.copy()
        /** Gate toggles on already open gates in the last run. */
        var wrongs = 0; private set
        /** Common random numbers: the same trials for every candidate, so they are compared on the same luck. */
        private val seeds = LongArray(trials) { seedBase + 7919L * it }
        private val rng = Lcg()
        /** The flips being run, in tick order. */
        private var ticks = IntArray(16)
        private var levers = IntArray(16)
        private var n = 0

        /** A run is cut short once it can't solve by [deadline], has toggled an open gate ([clean]) or can't cost under [bound]. */
        private var deadline = Int.MAX_VALUE
        private var clean = false
        private var bound = Double.POSITIVE_INFINITY
        private var cut = false
        /** The trials' outcomes so far, and the candidate's costs that don't come from simulating it. */
        private var partial = 0.0
        private var flipCost = 0.0
        private var effortCost = 0.0
        private var feasCost = 0.0

        fun solvedAt(f: List<Flip>): Int { run(f); return sim.solvedAt }

        /** [f] solves at [by] or sooner without closing a gate that was open. */
        fun solvesCleanlyBy(f: List<Flip>, by: Int): Boolean {
            deadline = by; clean = true
            run(f)
            deadline = Int.MAX_VALUE; clean = false
            return !cut && sim.solvedAt != WaterSim.NONE && sim.solvedAt <= by && wrongs == 0
        }

        private fun run(f: List<Flip>) {
            n = 0
            for (fl in f) add(fl.tick, fl.lever)
            run(null)
        }

        /** [f] with each click [JITTER] ticks either way, drawn from [rng]. */
        private fun jittered(f: List<Flip>) {
            n = 0
            for (fl in f) add(max(t0, fl.tick + rng.nextInt(2 * JITTER + 1) - JITTER), fl.lever)
        }

        /** Keeps tick order, a flip after those already on its tick. */
        private fun add(tick: Int, lever: Int) {
            if (n == ticks.size) { ticks = ticks.copyOf(2 * n); levers = levers.copyOf(2 * n) }
            var j = n++
            while (j > 0 && ticks[j - 1] > tick) { ticks[j] = ticks[j - 1]; levers[j] = levers[j - 1]; j-- }
            ticks[j] = tick; levers[j] = lever
        }

        private fun run(rng: Lcg?) {
            sim.setFrom(base)
            sim.rng = rng; sim.randomRate = if (rng != null) RANDOM_RATE else 0.0
            // solved from here on: a solve before this state is history (a gate closed since, or the puzzle would be done)
            sim.hold = hold; sim.solvedAt = WaterSim.NONE
            cut = false
            val cutting = clean || deadline != Int.MAX_VALUE || bound != Double.POSITIVE_INFINITY
            val wrong0 = base.wrongHits
            var i = 0
            val end = t0 + horizon
            while (sim.time < end) {
                var bits = 0
                while (i < n && ticks[i] == sim.time + 1) { bits = bits xor (1 shl levers[i]); i++ }
                sim.step(bits)
                if (sim.solvedAt != WaterSim.NONE) break
                // nothing left to happen: stop early (every gate open then stays open)
                if (i >= n && !sim.moving()) { if (sim.closed == 0) sim.solvedAt = sim.openSince; break }
                if (!cutting) continue
                // the soonest it can still solve: the open run it is in, or the next tick
                val soonest = if (sim.closed == 0) sim.openSince else sim.time + 1
                val w = sim.wrongHits - wrong0
                if (soonest > deadline || (clean && w > 0) ||
                    (bound != Double.POSITIVE_INFINITY && total(over(max(0, soonest - t0) + 15.0 * w)) >= bound)) { cut = true; break }
            }
            sim.rng = null
            wrongs = sim.wrongHits - wrong0
        }

        /** The simulated part of the cost when the run in hand comes to [x]: [x], or the trials' mean with it in. */
        private fun over(x: Double): Double = if (objective.trials) (partial + x) / trials else x

        /** The cost before the flips after the solve are counted, [x] its simulated part. */
        private fun total(x: Double): Double = when (objective) {
            Objective.FASTEST, Objective.SAFEST -> x + flipCost
            Objective.EASIEST -> x + flipCost + effortCost
            Objective.BALANCED -> x + 0.5 * (flipCost + effortCost)
        } + feasCost

        /** Ticks to solve, or what failing costs (a fail is far worse than any solve; gates left closed make it worse). */
        private fun outcome(): Double =
            if (sim.solvedAt != WaterSim.NONE) max(0, sim.solvedAt - t0).toDouble()
            else 10_000.0 + 1_000.0 * Integer.bitCount(sim.closed)

        /** What a person can't do: flips closer than the walk between their levers (and the first before you can be there). */
        private fun feasibility(f: List<Flip>): Double {
            var c = 0.0
            for (k in f.indices) {
                if (k == 0) { first?.let { val need = t0 + it[f[0].lever]; if (f[0].tick < need) c += 20.0 * (need - f[0].tick) }; continue }
                val need = spacing.between(f[k - 1].lever, f[k].lever)
                val gap = f[k].tick - f[k - 1].tick
                if (gap < need) c += 20.0 * (need - gap)
            }
            return c
        }

        /** How hard it is to do: walking, and flips with little room after the walk to the next lever. */
        private fun effort(f: List<Flip>): Double {
            var c = 0.0
            for (k in 1 until f.size) {
                val need = spacing.between(f[k - 1].lever, f[k].lever)
                c += 0.5 * (need - spacing.minGap)
                val gap = f[k].tick - f[k - 1].tick
                if (gap < need + EASE_ROOM) c += 2.0 * (need + EASE_ROOM - gap)
            }
            return c
        }

        /**
         * Monte Carlo: the mean outcome over [trials] runs with random ticks and each click [JITTER] ticks either way. Cut short
         * ([cut]) once the trials so far put the cost at [bound] or over.
         */
        private fun trialsMean(f: List<Flip>): Double {
            partial = 0.0
            for (k in 0 until trials) {
                rng.seed(seeds[k])
                jittered(f)
                run(rng)
                if (cut) return 0.0
                partial += outcome() + 15.0 * wrongs
                if (total(partial / trials) >= bound) { cut = true; return 0.0 }
            }
            return partial / trials
        }

        /** [f]'s cost; when that is [refuse] or more, maybe only that it is (infinity), from as little simulating as shows it. */
        fun cost(f: List<Flip>, refuse: Double = Double.POSITIVE_INFINITY): Double {
            flipCost = f.size * when (objective) {
                Objective.FASTEST -> FASTEST_FLIP_COST; Objective.SAFEST -> SAFE_FLIP_COST; else -> EASY_FLIP_COST
            }
            effortCost = if (objective == Objective.EASIEST || objective == Objective.BALANCED) effort(f) else 0.0
            feasCost = feasibility(f)
            bound = refuse
            val x = if (objective.trials) trialsMean(f) else { run(f); outcome() + 15.0 * wrongs }
            bound = Double.POSITIVE_INFINITY
            if (cut) return Double.POSITIVE_INFINITY
            var c = total(x)
            if (c >= refuse) return Double.POSITIVE_INFINITY
            // flips after it's solved are pointless (on the board as simulated: the trials left their last run in [sim])
            if (objective.trials) run(f)
            if (sim.solvedAt != WaterSim.NONE) c += 5.0 * f.count { it.tick > sim.solvedAt }
            return c
        }

        /** The plan run [n] times on random ticks and click error: how often and how soon it solves. */
        fun robustness(f: List<Flip>, n: Int, seed: Long): Robustness {
            val times = ArrayList<Int>(); var solved = 0
            for (k in 0 until n) {
                rng.seed(seed + k)
                jittered(f)
                run(rng)
                if (sim.solvedAt != WaterSim.NONE) { solved++; times += sim.solvedAt - t0 }
            }
            times.sort()
            return Robustness(solved.toDouble() / n, if (times.isEmpty()) -1 else times[times.size / 2], if (times.isEmpty()) -1 else times[(times.size * 9) / 10])
        }
    }

    /** [objective]'s cost of [flips] on trials the search never sees (comparing plans found by different searches). */
    fun checkCost(base: WaterSim, flips: List<Flip>, objective: Objective, trials: Int = TABLE_CHECK, horizon: Int = 700): Double {
        val t0 = base.time + 1
        return Evaluator(base, t0, horizon, spacing, null, hold, objective, if (objective.trials) trials else 1, seedBase = 0x0c4ec4L).cost(normalize(flips, t0))
    }

    /** How a plan holds up: share of trials that solve, and the median and 90th-percentile solve tick of those that do. */
    data class Robustness(val solveRate: Double, val median: Int, val p90: Int)

    /** [flips] from [base], [n] Monte Carlo trials (random ticks, click error). */
    fun robustness(base: WaterSim, flips: List<Flip>, n: Int = 1000, seed: Long = 1L, horizon: Int = 700): Robustness {
        val t0 = base.time + 1
        return Evaluator(base, t0, horizon, spacing, null, hold).robustness(normalize(flips, t0), n, seed)
    }

    companion object {
        /** A flip may be off by this much and still count (ticks later solved). */
        const val SLACK = 10
        const val WINDOW_PROBE = 20
        /** Every gate open at least this long (ticks: 500 ms, more when the server lags) for the chest. */
        const val HOLD = 10
        /** What a flip costs, in ticks of solve time, by objective: each is a walk, a click, a chance to get it wrong. */
        const val FASTEST_FLIP_COST = 2.0
        const val SAFE_FLIP_COST = 6.0
        const val EASY_FLIP_COST = 40.0
        /** Ticks of room after the walk to the next lever that [Objective.EASIEST] wants. */
        const val EASE_ROOM = 10
        /** Random ticks per flowing cell per tick (measured on Hypixel, WaterSim.randomTick) and click error, ticks either way. */
        const val RANDOM_RATE = 1.0 / 350
        const val JITTER = 2
        /** Monte Carlo trials per candidate: live (re-planning mid-solve) and offline (the plan table). */
        const val LIVE_TRIALS = 6
        const val TABLE_TRIALS = 24
        /** Trials the best candidates are checked on before one is chosen (never the search's own). */
        const val LIVE_CHECK = 40
        const val TABLE_CHECK = 400
        /** Candidates each annealer keeps for that check. */
        const val KEEP = 16
        /** Candidates an annealer gets through for each millisecond a search is given, on one run and on trials. */
        const val RUN_RATE = 60
        const val TRIAL_RATE = 6
        /** The candidates a search of [budgetMs] is worth: a search that has tried them is done before its time is up. */
        fun iterations(budgetMs: Long, objective: Objective): Int = (budgetMs * if (objective.trials) TRIAL_RATE else RUN_RATE).toInt()
        val THREADS = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors() - 2))
        private val pool = java.util.concurrent.Executors.newFixedThreadPool(THREADS) { r -> Thread(r, "ec-waterboard-planner").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }
}

/** kotlin.random.Random(seed)'s generator, number for number, that can go back to a [mark]. */
class Xorwow(seed: Long) : Random() {
    private var x = seed.toInt()
    private var y = (seed shr 32).toInt()
    private var z = 0
    private var w = 0
    private var v = x.inv()
    private var addend = (x shl 10) xor (y ushr 4)
    private val saved = IntArray(6)

    init { repeat(64) { nextInt() } }

    override fun nextInt(): Int {
        var t = x
        t = t xor (t ushr 2)
        x = y; y = z; z = w
        val v0 = v
        w = v0
        t = (t xor (t shl 1)) xor v0 xor (v0 shl 4)
        v = t
        addend += 362437
        return t + addend
    }

    override fun nextBits(bitCount: Int): Int = nextInt().ushr(32 - bitCount) and (-bitCount).shr(31)

    fun mark() { saved[0] = x; saved[1] = y; saved[2] = z; saved[3] = w; saved[4] = v; saved[5] = addend }
    fun reset() { x = saved[0]; y = saved[1]; z = saved[2]; w = saved[3]; v = saved[4]; addend = saved[5] }
}
