package com.engineerclient.p3sim.clear.damage

/**
 * The Wither Shield a Hyperion cast gives in the clear, as pure bookkeeping on the run clock. Measured on Hypixel: the gold
 * absorption bar goes from 0 to 16 on the cast (a cast that hits nothing included), stays for 100 ticks (93 to 119 seen), and no
 * new shield starts while one is up. While it is up a hit leaves health alone and only lowers the bar.
 *
 * How much a shield soaks is NOT measured; [initialPool] is a named setting in percent of max health.
 */
class WitherShield(private val initialPool: Double) {
    private var until = Int.MIN_VALUE
    private var pool = 0.0

    /** True while the shield is up at run tick [t]. */
    fun active(t: Int): Boolean = t < until && pool > 0.0

    /** A cast at [t]: starts a shield unless one is already up. Returns true when a new one started. */
    fun start(t: Int): Boolean {
        if (t < until) return false
        until = t + DURATION_TICKS
        pool = initialPool
        return true
    }

    /**
     * Takes [percent] of max health from the shield at [t]; returns what is left over for health (all of it when no shield is up).
     */
    fun absorb(t: Int, percent: Double): Double {
        if (!active(t)) return percent
        val soaked = minOf(pool, percent)
        pool -= soaked
        return percent - soaked
    }

    /** The vanilla absorption amount to show at [t]: 16 for a fresh shield, less as it soaks, 0 when it is down. */
    fun absorption(t: Int): Double = if (active(t) && initialPool > 0.0) FULL_ABSORPTION * pool / initialPool else 0.0

    /** Health plus the shield's pool in percent of max, for a gold over-max health readout (0 when no shield). */
    fun poolPercent(t: Int): Double = if (active(t)) pool else 0.0

    companion object {
        const val DURATION_TICKS = 100
        const val FULL_ABSORPTION = 16.0
        /** Pool of a fresh shield in percent of max health. UNMEASURED: starts at the Wish ratio (0.93 of max health). */
        const val DEFAULT_POOL_PERCENT = 93.0
    }
}
