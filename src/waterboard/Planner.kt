package com.engineerclient.waterboard

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
     * Ticks a person needs between flipping lever a and lever b (walking within reach, clicking). Measured on a recorded solve
     * (2026-10-13, 6436e9): 16 ticks between levers 11 blocks apart, 4 between two flips of one lever; the game's interaction
     * range is 4.5 blocks and a sprint 0.28 blocks a tick.
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
     */
    fun plan(base: WaterSim, seeds: List<List<Flip>>, budgetMs: Long, rnd: Random = Random.Default, first: IntArray? = null,
             horizon: Int = 700, threads: Int = THREADS, objective: Objective = Objective.FASTEST, trials: Int = LIVE_TRIALS): Plan {
        val t0 = base.time + 1
        val levers = base.geo.slotLever.distinct().sorted().toIntArray()
        val starts = (seeds.map { normalize(it, t0) } + listOf(emptyList())).distinct()
        val budgetNs = budgetMs * 1_000_000
        val seedsRnd = LongArray(threads) { rnd.nextLong() }
        // independent annealers from the same starts, each its own scratch board; the best wins
        fun ev() = Evaluator(base, t0, horizon, spacing, first, hold, objective, trials)
        val runs = if (threads <= 1) listOf(anneal(ev(), starts, budgetNs, Random(seedsRnd[0]), levers))
            else (0 until threads).map { k -> pool.submit<List<Pair<List<Flip>, Double>>> { anneal(ev(), starts, budgetNs, Random(seedsRnd[k]), levers) } }.map { it.get() }
        val eval = ev()
        val pick = if (!objective.trials) runs.flatten().minByOrNull { it.second }!!.first
            else {
                // the trials' seeds are the same for every candidate, so the search can fit them: choose among the best it found
                // on trials it never saw
                val check = Evaluator(base, t0, horizon, spacing, first, hold, objective, if (trials >= Planner.TABLE_TRIALS) TABLE_CHECK else LIVE_CHECK, seedBase = 0x0c4ec4L)
                (starts + runs.flatten().map { it.first }).distinct().minByOrNull { check.cost(it) }!!
            }
        val flips = polish(eval, pick)
        val solved = eval.solvedAt(flips)
        return Plan(flips, solved, eval.cost(flips), if (solved != WaterSim.NONE) windows(eval, flips, solved) else emptyList())
    }

    /** The best plans one annealer found ([KEEP] of them, distinct, best first). */
    private fun anneal(eval: Evaluator, starts: List<List<Flip>>, budgetNs: Long, rnd: Random, levers: IntArray): List<Pair<List<Flip>, Double>> {
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
            if (el >= budgetNs) break
            iter++
            val temp = temp0 * Math.pow(temp1 / temp0, el.toDouble() / budgetNs)
            val cand = mutate(cur, eval.t0, rnd, levers)
            val c = eval.cost(cand)
            if (c < curCost || rnd.nextDouble() < exp((curCost - c) / temp)) {
                cur = cand; curCost = c
                keep(cand, c)
                if (c < best!!.second) best = cand to c
            }
            // now and then go back to the best (the board punishes wandering far)
            if (iter % 400 == 0 && curCost > best!!.second + 30) { cur = best.first; curCost = best.second }
        }
        return top.entries.map { it.value to it.key }
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
    private class Evaluator(val base: WaterSim, val t0: Int, val horizon: Int, val spacing: Spacing, val first: IntArray?, val hold: Int,
                            val objective: Objective = Objective.FASTEST, val trials: Int = LIVE_TRIALS, seedBase: Long = 0x5eed_0000L) {
        private val sim = base.copy()
        private var lastWrongs = 0
        /** Common random numbers: the same trials for every candidate, so they are compared on the same luck. */
        private val seeds = LongArray(trials) { seedBase + 7919L * it }

        fun solvedAt(f: List<Flip>): Int { run(f); return sim.solvedAt }
        fun wrongs(f: List<Flip>): Int { run(f); return lastWrongs }

        private fun run(f: List<Flip>, rng: java.util.Random? = null) {
            sim.setFrom(base)
            sim.rng = rng; sim.randomRate = if (rng != null) RANDOM_RATE else 0.0
            // solved from here on: a solve before this state is history (a gate closed since, or the puzzle would be done)
            sim.hold = hold; sim.solvedAt = WaterSim.NONE
            val wrong0 = base.wrongHits
            var i = 0
            val end = t0 + horizon
            while (sim.time < end) {
                var bits = 0
                while (i < f.size && f[i].tick == sim.time + 1) { bits = bits xor (1 shl f[i].lever); i++ }
                sim.step(bits)
                if (sim.solvedAt != WaterSim.NONE) break
                // nothing left to happen: stop early (every gate open then stays open)
                if (i >= f.size && !sim.moving()) { if (sim.closed == 0) sim.solvedAt = sim.openSince; break }
            }
            sim.rng = null
            lastWrongs = sim.wrongHits - wrong0
        }

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

        /** Monte Carlo: the mean outcome over [trials] runs with random ticks and each click [JITTER] ticks either way. */
        private fun trialsMean(f: List<Flip>): Double {
            var sum = 0.0
            for (k in 0 until trials) {
                val r = java.util.Random(seeds[k])
                val moved = f.map { Flip(max(t0, it.tick + r.nextInt(2 * JITTER + 1) - JITTER), it.lever) }.sortedBy { it.tick }
                run(moved, r)
                sum += outcome() + 15.0 * lastWrongs
            }
            return sum / trials
        }

        fun cost(f: List<Flip>): Double {
            var c = when (objective) {
                Objective.FASTEST -> { run(f); outcome() + 15.0 * lastWrongs + FASTEST_FLIP_COST * f.size }
                Objective.EASIEST -> { run(f); outcome() + 15.0 * lastWrongs + EASY_FLIP_COST * f.size + effort(f) }
                Objective.SAFEST -> trialsMean(f) + SAFE_FLIP_COST * f.size
                Objective.BALANCED -> trialsMean(f) + 0.5 * (EASY_FLIP_COST * f.size + effort(f))
            }
            c += feasibility(f)
            // flips after it's solved are pointless (on the board as simulated: the trials left their last run in [sim])
            if (objective.trials) run(f)
            if (sim.solvedAt != WaterSim.NONE) c += 5.0 * f.count { it.tick > sim.solvedAt }
            return c
        }

        /** The plan run [n] times on random ticks and click error: how often and how soon it solves. */
        fun robustness(f: List<Flip>, n: Int, seed: Long): Robustness {
            val times = ArrayList<Int>(); var solved = 0
            for (k in 0 until n) {
                val r = java.util.Random(seed + k)
                val moved = f.map { Flip(max(t0, it.tick + r.nextInt(2 * JITTER + 1) - JITTER), it.lever) }.sortedBy { it.tick }
                run(moved, r)
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
        val THREADS = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors() - 2))
        private val pool = java.util.concurrent.Executors.newFixedThreadPool(THREADS) { r -> Thread(r, "ec-waterboard-planner").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }
}
