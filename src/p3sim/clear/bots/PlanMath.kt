package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.pack.runstate.Dist
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/** The small pure maths of the ClearPlan: correlated draws and distribution helpers. */
object PlanMath {
    /** The standard normal CDF (Abramowitz and Stegun 26.2.17, error under 1e-7). */
    fun phi(z: Double): Double {
        val t = 1.0 / (1.0 + 0.2316419 * abs(z))
        val d = 0.3989423 * exp(-z * z / 2.0)
        val p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))))
        return if (z >= 0) 1.0 - p else p
    }

    /** One standard normal draw (Box-Muller). */
    fun gauss(r: Random): Double = sqrt(-2.0 * kotlin.math.ln(1.0 - r.nextDouble())) * kotlin.math.cos(2.0 * Math.PI * r.nextDouble())

    /** The percent (0..100) at which [d] reaches [value]: the inverse of [Dist.at], clamped to 0..100. */
    fun percentOf(d: Dist, value: Double): Double {
        if (value <= d.q[0]) {
            val lo = d.min ?: return d.pct[0]
            if (d.q[0] <= lo) return d.pct[0]
            return (d.pct[0] * ((value - lo) / (d.q[0] - lo))).coerceIn(0.0, d.pct[0])
        }
        val last = d.q.size - 1
        if (value >= d.q[last]) {
            val hi = d.max ?: return d.pct[last]
            if (hi <= d.q[last]) return d.pct[last]
            return (d.pct[last] + (100.0 - d.pct[last]) * ((value - d.q[last]) / (hi - d.q[last]))).coerceIn(d.pct[last], 100.0)
        }
        for (i in 1..last) if (value <= d.q[i]) {
            val span = d.q[i] - d.q[i - 1]
            return if (span <= 0.0) d.pct[i] else d.pct[i - 1] + (d.pct[i] - d.pct[i - 1]) * ((value - d.q[i - 1]) / span)
        }
        return d.pct[last]
    }

    /** A draw from [d] at uniform [u] given that the value is at least [floor] (the draw is conditioned, not clamped, so the shape above the floor stays right). */
    fun sampleAtLeast(d: Dist, u: Double, floor: Double): Double {
        val p0 = percentOf(d, floor)
        if (p0 >= 99.999) return maxOf(floor, d.max ?: d.q.last())
        return maxOf(floor, d.at(p0 + (100.0 - p0) * u.coerceIn(0.0, 1.0)))
    }

    /** The value at share [u] (0..1) of a curve given as ascending (share, value) points, straight between points and flat outside them. */
    fun curve(points: List<Pair<Double, Double>>, u: Double): Double {
        if (points.isEmpty()) return 0.0
        if (u <= points.first().first) return points.first().second
        for (i in 1 until points.size) {
            val (p1, v1) = points[i]
            if (u <= p1) {
                val (p0, v0) = points[i - 1]
                return if (p1 <= p0) v1 else v0 + (v1 - v0) * (u - p0) / (p1 - p0)
            }
        }
        return points.last().second
    }

    /** A distribution that is just a shape around a median: the built-in default when a table is missing. [shape] are the multipliers at 5,10,25,50,75,90,95 percent. */
    fun around(median: Double, shape: DoubleArray = DEFAULT_SHAPE): Dist =
        Dist(0, Dist.DEFAULT_PCT, DoubleArray(shape.size) { median * shape[it] }, null, null)

    /** A right-skewed shape typical of the measured room and door times (median 1). */
    val DEFAULT_SHAPE = doubleArrayOf(0.15, 0.3, 0.6, 1.0, 1.6, 2.4, 3.1)

    /**
     * The "pace of this party" draw: one common normal [z0] per plan, mixed with an independent one per quantity by [rho], so every
     * quantity keeps its own exact marginal distribution while a fast party is fast everywhere. Deterministic from the random.
     */
    class Pace(private val r: Random, private val rho: Double) {
        private val z0 = gauss(r)

        /** The next uniform in (0,1), correlated with the others by [rho]. */
        fun u(): Double = phi(rho * z0 + sqrt(1.0 - rho * rho) * gauss(r))
    }
}
