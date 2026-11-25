package com.engineerclient.mimic

import kotlin.math.pow

/**
 * Where the Mimic is: the dungeon's [n] locked chests are tried in a random order, each its chest with chance 1/2; if every one
 * fails it takes one of the [m] plain chests outside the trap room.
 */
object MimicOdds {
    /** A room or a split and its chest sites not yet ruled out (expected counts for the unknown part). */
    class Part(val key: Any, val locked: Double, val plain: Double)

    /** The chance on one locked site and on one plain site. */
    fun perSite(n: Double, m: Double): Pair<Double, Double> {
        if (n <= 0.0) return 0.0 to (if (m > 0.0) 1.0 / m else 0.0)
        val fail = 2.0.pow(-n)
        return (1 - fail) / n to (if (m > 0.0) fail / m else 0.0)
    }

    /** Each part's chance, given the Mimic is on none of the ruled-out sites. */
    fun odds(parts: List<Part>, n: Double, m: Double): Map<Any, Double> {
        val (wl, wp) = perSite(n, m)
        val mass = parts.associate { it.key to it.locked * wl + it.plain * wp }
        val total = mass.values.sum()
        if (total <= 0.0) return emptyMap()
        return mass.mapValues { it.value / total }
    }

    /** The chance the Mimic is on none of [ruledL] locked and [ruledP] plain sites. */
    fun notFound(n: Double, m: Double, ruledL: Double, ruledP: Double): Double {
        val (wl, wp) = perSite(n, m)
        return (1 - ruledL * wl - ruledP * wp).coerceIn(0.0, 1.0)
    }
}
