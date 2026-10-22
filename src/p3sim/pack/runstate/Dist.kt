package com.engineerclient.p3sim.pack.runstate

import com.engineerclient.p3sim.pack.*
import com.google.gson.JsonElement

/**
 * A percentile vector (FORMAT-runstate 2.2): `q[i]` is the value at percent `pct[i]`. Both the 7-point `Dist` (percents
 * [5,10,25,50,75,90,95], the pack's top-level `pct`) and the 5-point `QDist` (its own `pct`) read into this class.
 * Durations are server ticks with lag removed ("sim ticks").
 */
class Dist(
    val n: Int,
    val pct: DoubleArray,
    val q: DoubleArray,
    val min: Double?,
    val max: Double?,
) {
    init {
        require(pct.size == q.size && q.isNotEmpty()) { "a distribution needs as many values as percents" }
    }

    /** A distribution with fewer than 8 samples is advisory only: prefer the parent table (class -> type -> global). */
    val advisory: Boolean get() = n < 8

    /** The median (the value at 50 percent), interpolated if 50 is not one of the points. */
    val median: Double get() = at(50.0)

    /** The value at percent [p] (0..100) by the sampling rule of FORMAT-runstate 2.2. */
    fun at(p: Double): Double {
        val last = q.size - 1
        if (p <= pct[0]) {
            val lo = min ?: return q[0]
            if (pct[0] <= 0.0) return q[0]
            return lo + (q[0] - lo) * (p.coerceAtLeast(0.0) / pct[0])
        }
        if (p >= pct[last]) {
            val hi = max ?: return q[last]
            if (pct[last] >= 100.0) return q[last]
            return q[last] + (hi - q[last]) * ((p.coerceAtMost(100.0) - pct[last]) / (100.0 - pct[last]))
        }
        for (i in 1..last) if (p <= pct[i]) {
            val span = pct[i] - pct[i - 1]
            return if (span <= 0.0) q[i] else q[i - 1] + (q[i] - q[i - 1]) * ((p - pct[i - 1]) / span)
        }
        return q[last]
    }

    /** One sample: [u] uniform in [0,1] (the percent is `100 u`), piecewise linear over the points. The caller rounds to a tick. */
    fun sample(u: Double): Double = at(100.0 * u.coerceIn(0.0, 1.0))

    companion object {
        /** The default percents of a `Dist` when the object carries no `pct` of its own. */
        val DEFAULT_PCT = doubleArrayOf(5.0, 10.0, 25.0, 50.0, 75.0, 90.0, 95.0)

        /**
         * Reads `{n, q, [pct], [min], [max]}`. Null (not an exception) when [e] is null, not an object, has no `q`, or the
         * lengths disagree; the section that needs it then falls back to its parent table.
         */
        fun parse(e: JsonElement?, globalPct: DoubleArray = DEFAULT_PCT): Dist? {
            val o = e.asObj() ?: return null
            val q = o.arr("q").doubles()
            if (q.isEmpty()) return null
            val pct = o.arr("pct")?.doubles()?.takeIf { it.isNotEmpty() } ?: globalPct
            if (pct.size != q.size) return null
            return Dist(o.int("n") ?: 0, pct, q, o.dbl("min"), o.dbl("max"))
        }
    }
}
