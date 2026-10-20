package com.engineerclient.p3sim.clear.damage

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.Fight
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimStand
import com.engineerclient.p3sim.Station
import kotlin.random.Random

/**
 * The damage numbers Hypixel floats over a hit mob: an invisible marker stand named `§7N` (a crit: `✧N✧` coloured per character in the cycle
 * f f e 6 c c, a `§r` between colour runs), standing still for 20 ticks and gone.
 *
 * Measured (ADR, 1,383 stands in 60 F7 clears before the boss): life p10/p50/p90 19/19/21 ticks, none moves; from the hit mob's body 0.39/0.80/1.89
 * blocks across and -0.32/+1.16/+2.03 up; 27 percent are crits; up to 7 in one tick. They are few: about 0.27 per mob death, more for a Mage
 * recording (6.4 a run) than an Archer's (1.4).
 *
 * Every hit by every player gets one (the user's word, 2026-10-19: the recordings' low count is their mods and settings hiding some). Both killing
 * and surviving hits did (608 against 679), from the recording player and from teammates (870 and 621 of 1,902).
 */
object DamageNumbers {
    const val LIFE_TICKS = 20
    const val CRIT_SHARE = 0.27
    /** Percent of hits that get a number: all of them. */
    const val DEFAULT_CHANCE = 100
    private val CRIT_CYCLE = charArrayOf('f', 'f', 'e', '6', 'c', 'c')

    /** "26,707,180" for 26707180.4. */
    fun digits(amount: Double): String = "%,d".format(java.util.Locale.ROOT, Math.round(amount).coerceAtLeast(1))

    /** The stand's name: `§7N`, or the crit's coloured `✧N✧` as Hypixel writes it. */
    fun name(amount: Double, crit: Boolean): String {
        if (!crit) return "§7" + digits(amount)
        val text = "✧" + digits(amount) + "✧"
        val sb = StringBuilder()
        var last = ' '
        for ((i, ch) in text.withIndex()) {
            val c = CRIT_CYCLE[i % CRIT_CYCLE.size]
            if (c != last) { if (i > 0) sb.append("§r"); sb.append('§').append(c); last = c }
            sb.append(ch)
        }
        return sb.toString()
    }

    /** Height above the mob's feet: the measured spread (p10 -0.32, p50 +1.16, p90 +2.03), drawn piecewise. */
    fun rise(u: Double): Double {
        val q = doubleArrayOf(0.0, 0.1, 0.5, 0.9, 1.0); val v = doubleArrayOf(-0.6, -0.32, 1.16, 2.03, 2.4)
        for (i in 1 until q.size) if (u <= q[i]) return v[i - 1] + (v[i] - v[i - 1]) * (u - q[i - 1]) / (q[i] - q[i - 1])
        return v.last()
    }

    /** Where the number stands: up to a block and a bit to either side of the mob (median 0.8 across), [rise] up. */
    fun place(x: Double, y: Double, z: Double, rnd: Random): DoubleArray {
        val a = rnd.nextDouble() * Math.PI * 2
        val r = 1.9 * Math.sqrt(rnd.nextDouble()) * 0.62
        return doubleArrayOf(x + Math.cos(a) * r, y + rise(rnd.nextDouble()), z + Math.sin(a) * r)
    }

    /** Floats a number over the mob body at [x], [y], [z] for [LIFE_TICKS]. Server thread. */
    fun show(x: Double, y: Double, z: Double, amount: Double, rnd: Random) {
        EngineerClient.safely("ads damage number") {
            val at = place(x, y, z, rnd)
            val s = SimStand(Sim.level)
            s.snapTo(at[0], at[1], at[2], 0f, 0f)
            s.isInvisible = true
            s.isPermanentlyInvulnerable = true
            s.isSilent = true
            s.setNoGravity(true)
            Station.setMarker(s)
            s.setCustomName(Sim.legacy(name(amount, rnd.nextDouble() < CRIT_SHARE)))
            s.isCustomNameVisible = true
            val e = Sim.spawn(s)
            Fight.later(LIFE_TICKS, "damage number gone") { e.discard() }
        }
    }
}
