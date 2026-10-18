package com.engineerclient.p3sim.clear.damage

import com.engineerclient.p3sim.pack.mobs.HitQuantiles
import com.engineerclient.p3sim.pack.mobs.MeleeTable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * Rolls the size of a mob's melee hit in percent of max HP, from the mob pack's measured table (FORMAT-mobs 7).
 * F7's pooled table has a median of 2.06 percent, 90th percentile 10; a share of hits are exact values (10, 2 and 5 percent).
 */
object MeleeSampler {
    /** Used when the pack has no table: the F7 numbers measured (p50 2.06, p10 0.52, p90 10.0). */
    val FALLBACK = HitQuantiles(2271, 0.52, 2.06, 10.0)

    /** The hit size from the pooled [table] with [random]: an exact atom with its probability, else the quantile curve (linear in the log of the size). */
    fun sample(table: MeleeTable, random: Random): Double {
        val atomTotal = table.atoms.sumOf { it.p }
        val r = random.nextDouble()
        if (atomTotal > 0.0 && r < atomTotal) {
            var acc = 0.0
            for (a in table.atoms) { acc += a.p; if (r < acc) return a.value }
            return table.atoms.last().value
        }
        return fromQuantiles(table.quantiles, random.nextDouble() * 100.0)
    }

    /** The size at percentile [u] (0 to 100) of [q], interpolating linearly in the log of the size, clamped at the end keys. */
    fun fromQuantiles(q: java.util.SortedMap<Double, Double>, u: Double): Double {
        if (q.isEmpty()) return FALLBACK.p50
        val keys = q.keys.toList()
        if (u <= keys.first()) return q.getValue(keys.first())
        if (u >= keys.last()) return q.getValue(keys.last())
        val hi = keys.first { it >= u }
        val lo = keys.last { it <= u }
        if (hi == lo) return q.getValue(lo)
        val a = lg(q.getValue(lo)); val b = lg(q.getValue(hi))
        return exp(a + (b - a) * ((u - lo) / (hi - lo)))
    }

    /**
     * The size from one archetype's three published points: a log-normal that is split at the median, so that the 10th, 50th and
     * 90th percentiles all come out right (the normal quantile at 90 percent is 1.2816). Capped at 100.
     */
    fun fromArchetype(h: HitQuantiles, random: Random): Double {
        val mu = lg(h.p50)
        val z = gaussian(random)
        val sigma = if (z >= 0) (lg(h.p90) - mu) / 1.2816 else (mu - lg(h.p10)) / 1.2816
        return exp(mu + sigma.coerceAtLeast(0.0) * z).coerceIn(0.01, 100.0)
    }

    private fun lg(v: Double) = ln(if (v <= 0.0) 1e-3 else v)

    private fun gaussian(random: Random): Double {
        // Box-Muller; the first draw is kept away from 0 so the log is finite.
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return kotlin.math.sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}
