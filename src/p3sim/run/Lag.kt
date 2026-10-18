package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.runstate.LagTier
import com.google.gson.JsonObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/*
 * The lag layer's model (FORMAT-runstate 12, measured on Hypixel's main network): a server that LOSES ticks, as freeze then burst.
 * A stall of `g` ms skips `g / 50 - 1` server ticks; the server then runs a catch-up burst and recovers only part of what it
 * missed (a gap of 1 to 3 s misses 38.6 ticks and gets 9.9 back within 5 s), so the ticks that are never recovered are the net loss
 * the presets quote (ticks lost per minute: 14.5 / 30.3 / 56.6, an instance slot above 200 about 1.8 times the typical one).
 *
 * This file is pure (no Minecraft): [LagGenerator] decides WHEN a freeze starts and how long it is; `LagRuntime` makes the server thread
 * stall and forget the unrecovered ticks. Pack durations are lag-free "sim ticks": this layer stretches wall time and never `Run.t`.
 */

/** One freeze: the gap between two ticks, the ticks it missed, and how many of them the catch-up burst recovers (the rest are lost for good). */
data class LagEvent(val gapMs: Double, val missedTicks: Int, val recoveredTicks: Int) {
    val lostTicks: Int get() = missedTicks - recoveredTicks
}

/**
 * The numbers the model is built from. [DEFAULTS] are the measured ones (they are also what the run-state pack carries; a loaded pack
 * overrides them through [fromPack]).
 */
class LagParams(
    /** Freezes of at least `gapMs`, per hour, ascending by gap (`freezes.exceedPerHour`). */
    val exceedPerHour: List<Pair<Double, Double>>,
    /** `(missedTicks, recoveredTicks)` points of the catch-up curve, ascending; linear between them, flat after the last (`freezes.catchup.byGap`). */
    val recovery: List<Pair<Double, Double>>,
    /** Share of freeze onsets that start within 5 s of another (`freezes.clustering.within5sFrac`). */
    val within5sFrac: Double,
    /** Share a random (memoryless) process would put within 5 s (`randomExpected`); kept from the pack, the model works it out from its own rate. */
    val randomWithin5s: Double,
    /** The longest freeze the model draws (the last bin is open-ended in the data). */
    val maxGapMs: Double = 12_000.0,
) {
    /** Ticks recovered from a freeze that missed [missed]. */
    fun recovered(missed: Double): Double {
        if (missed <= 0) return 0.0
        val pts = recovery
        if (pts.isEmpty()) return 0.0
        if (missed <= pts.first().first) return pts.first().second * missed / pts.first().first.coerceAtLeast(1e-9)
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]; val (x1, y1) = pts[i]
            if (missed <= x1) return (y0 + (y1 - y0) * (missed - x0) / (x1 - x0).coerceAtLeast(1e-9)).coerceAtMost(missed)
        }
        return pts.last().second.coerceAtMost(missed)
    }

    companion object {
        /** The measured tables (hypixel-lag, main network, Oct 2026). */
        val DEFAULTS = LagParams(
            exceedPerHour = listOf(100.0 to 1230.25, 250.0 to 111.24, 500.0 to 36.09, 1000.0 to 23.1, 2000.0 to 16.33, 5000.0 to 0.72),
            // byGap: gaps of 0.25-0.5 s recover nothing, 0.5-1 s 6.4 of 12.2, 1-3 s 9.9 of 38.6, 3 s and more 8.6 of 78.2.
            recovery = listOf(5.6 to 0.0, 12.2 to 6.4, 38.6 to 9.9, 78.2 to 8.6),
            within5sFrac = 0.313, randomWithin5s = 0.167,
        )

        /** [DEFAULTS] with whatever the loaded run-state pack's `lag` section carries (its raw JSON; missing parts keep the defaults). */
        fun fromPack(lag: JsonObject?): LagParams {
            val f = lag?.get("freezes")?.takeIf { it.isJsonObject }?.asJsonObject ?: return DEFAULTS
            val exceed = f.get("exceedPerHour")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val g = o.num("gapMs") ?: return@mapNotNull null; val h = o.num("perHour") ?: return@mapNotNull null
                g to h
            }?.sortedBy { it.first }?.takeIf { it.size >= 2 } ?: DEFAULTS.exceedPerHour
            val by = f.get("catchup")?.takeIf { it.isJsonObject }?.asJsonObject?.get("byGap")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val m = o.num("missedTicks") ?: return@mapNotNull null; val r = o.num("recoveredWithin5s") ?: return@mapNotNull null
                m to r.coerceAtLeast(0.0)
            }?.sortedBy { it.first }?.takeIf { it.isNotEmpty() } ?: DEFAULTS.recovery
            val c = f.get("clustering")?.takeIf { it.isJsonObject }?.asJsonObject
            return LagParams(exceed, by, c?.num("within5sFrac") ?: DEFAULTS.within5sFrac, c?.num("randomExpected") ?: DEFAULTS.randomWithin5s)
        }

        private fun JsonObject.num(k: String): Double? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    }
}

/**
 * Draws freezes so that the net loss is [lossPerMinute] ticks per minute on average: the measured size mix (many short freezes,
 * a few long ones), their clustering, and the partial recovery. Call [nextTick] once per server tick that runs.
 *
 * How the loss is hit exactly: the size mix and the recovery curve are fixed by the data, so the average lost ticks per freeze is a
 * known number `e`; the freezes then arrive at the rate `lossPerMinute / e` per minute. (The data's own table, taken as it stands,
 * would give about [naturalLossPerMinute] a minute; a preset is that table run faster or slower, not a different shape.)
 * Clustering (a freeze is more likely to be followed by another within 5 s than chance says) applies to the freezes of
 * [CLUSTER_FROM_MS] or more, the ones whose chance rate the measured 16.7 % corresponds to; the many short ones arrive independently.
 * Gaps under 100 ms, the tick-to-tick jitter of a healthy server, are not simulated.
 */
class LagGenerator(val params: LagParams, val lossPerMinute: Double, private val random: Random = Random.Default) {
    private class Bin(val lo: Double, val hi: Double, val weight: Double)

    private val bins: List<Bin> = run {
        val e = params.exceedPerHour
        List(e.size) { i ->
            val hi = if (i + 1 < e.size) e[i + 1].first else params.maxGapMs
            Bin(e[i].first, hi, (e[i].second - (if (i + 1 < e.size) e[i + 1].second else 0.0)).coerceAtLeast(0.0))
        }
    }
    private val small = bins.filter { it.lo < CLUSTER_FROM_MS }
    private val big = bins.filter { it.lo >= CLUSTER_FROM_MS }
    private val totalPerHour = bins.sumOf { it.weight }

    /** The mean ticks lost per freeze, and the mean ticks missed (integrated over each bin's log-uniform gap). */
    private val meanLost: Double
    val meanMissed: Double
    init {
        var lost = 0.0; var missed = 0.0
        for (b in bins) {
            val n = 400
            var l = 0.0; var m = 0.0
            for (k in 0 until n) {
                val gap = exp(ln(b.lo) + (ln(b.hi) - ln(b.lo)) * (k + 0.5) / n)
                val miss = missedFor(gap)
                l += miss - Math.round(params.recovered(miss.toDouble())).coerceIn(0, miss.toLong()); m += miss
            }
            lost += b.weight * l / n; missed += b.weight * m / n
        }
        meanLost = if (totalPerHour > 0) lost / totalPerHour else 0.0
        meanMissed = if (totalPerHour > 0) missed / totalPerHour else 0.0
    }

    /** What the data's table would lose per minute with no scaling. */
    val naturalLossPerMinute: Double = totalPerHour * meanLost / 60.0

    /** Freezes per minute at this preset. */
    val freezesPerMinute: Double = if (meanLost > 0) lossPerMinute / meanLost else 0.0

    private val bigShare = if (totalPerHour > 0) big.sumOf { it.weight } / totalPerHour else 0.0

    /**
     * Chance that a clustered freeze is followed by another inside 5 s. Chosen so that, with the chance arrivals of this preset's own rate
     * added, [LagParams.within5sFrac] (31.3 %) of these freezes start within 5 s of another: `share = follow + (1 - follow) * r`, r being the
     * chance a Poisson process of the same rate puts one inside 5 s.
     */
    private val follow: Double = run {
        val perMinute = freezesPerMinute * bigShare
        val r = 1.0 - exp(-perMinute * 5.0 / 60.0)
        ((params.within5sFrac - r) / (1.0 - r)).coerceIn(0.0, 0.9)
    }
    /** Per server tick: a short freeze starts; a fresh (unchained) clustered freeze starts (the chained ones come on top, so the total rate is the table's). */
    private val pSmall: Double = freezesPerMinute * (1.0 - bigShare) / (60.0 * TICKS_PER_SECOND)
    private val pBig: Double = freezesPerMinute * bigShare * (1.0 - follow) / (60.0 * TICKS_PER_SECOND)

    private var chainIn = -1
    /** Server ticks that ran so far (for tests and reports). */
    var ticks = 0L
        private set
    var lost = 0L
        private set
    var events = 0
        private set

    /** One server tick is about to run: the freeze that starts now, or null. */
    fun nextTick(): LagEvent? {
        ticks++
        var ev: LagEvent? = null
        // A chained follow-up comes due, or a fresh clustered freeze starts by chance.
        if (chainIn > 0) chainIn--
        else if (chainIn == 0) { chainIn = -1; ev = draw(big) }
        if (ev == null && pBig > 0 && random.nextDouble() < pBig) ev = draw(big)
        if (ev != null) {
            // A follow-up inside 5 s of this one's start (the freeze itself uses up part of those 100 ticks of wall time).
            if (chainIn < 0 && random.nextDouble() < follow) chainIn = (1 + random.nextInt(5 * TICKS_PER_SECOND) - ev.missedTicks).coerceAtLeast(0)
            return count(ev)
        }
        if (pSmall > 0 && random.nextDouble() < pSmall) return count(draw(small))
        return null
    }

    private fun count(ev: LagEvent): LagEvent { events++; lost += ev.lostTicks; return ev }

    private fun draw(from: List<Bin>): LagEvent {
        val pool = from.ifEmpty { bins }
        var u = random.nextDouble() * pool.sumOf { it.weight }
        var bin = pool.last()
        for (b in pool) { if (u < b.weight) { bin = b; break }; u -= b.weight }
        val gap = exp(ln(bin.lo) + (ln(bin.hi) - ln(bin.lo)) * random.nextDouble())
        val missed = missedFor(gap)
        return LagEvent(gap, missed, Math.round(params.recovered(missed.toDouble())).toInt().coerceIn(0, missed))
    }

    companion object {
        const val TICK_MS = 50.0
        const val TICKS_PER_SECOND = 20
        /** Freezes from this long on arrive in clusters (the share the data's "within 5 s of another" is measured on). */
        const val CLUSTER_FROM_MS = 250.0

        /** The ticks a gap of [gapMs] between two ticks skips: at least one. */
        fun missedFor(gapMs: Double): Int = Math.round(gapMs / TICK_MS - 1).toInt().coerceAtLeast(1)
    }
}

/** The preset a lag layer runs: the loss per minute from the pack's tier table when it is loaded, else the preset's own measured default. */
object LagPresets {
    /** Ticks lost per minute for [preset] (F7 tier of the run-state pack when given). */
    fun lossPerMinute(preset: LagPreset, tiers: Map<String, LagTier>?): Double {
        if (preset == LagPreset.OFF) return 0.0
        val t = tiers?.get("F7") ?: return preset.lossPerMinute
        return when (preset) {
            LagPreset.GOOD -> t.good
            LagPreset.TYPICAL -> t.typical
            LagPreset.BAD -> t.bad
            LagPreset.HIGH_SLOT -> t.slotOver200 ?: preset.lossPerMinute
            LagPreset.OFF -> 0.0
        }
    }
}

/**
 * The catch-up burst's pacing, pure: after a freeze the server runs the recovered ticks faster than 20 a second, at [rate] times the tick
 * rate (UNMEASURED: the data has the recovered count, not the speed; default [DEFAULT_RATE]). [sleepMs] is how long to wait after a tick
 * that ended [sinceLastMs] after the previous one.
 */
object LagBurst {
    /** UNMEASURED: how many times the normal tick rate the catch-up runs at. A named constant and the "Lag Burst Rate" setting. */
    const val DEFAULT_RATE = 2.0

    fun sleepMs(sinceLastMs: Double, rate: Double): Double = (LagGenerator.TICK_MS / rate.coerceAtLeast(1.0) - sinceLastMs).coerceAtLeast(0.0)

    /**
     * How many ticks the burst runs to make up [recovered] ticks at [rate] times the tick rate: each one gains `1 - 1/rate` of a tick on the
     * normal pace, so 10 recovered ticks at 2x take 20 burst ticks (10 normal periods, 500 ms). At a rate of 1 or less there is no pacing
     * and the recovered ticks simply run one after another.
     */
    fun ticksFor(recovered: Int, rate: Double): Int = if (rate <= 1.0001) recovered else Math.ceil(recovered * rate / (rate - 1.0)).toInt()
}
