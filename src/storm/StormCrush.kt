package com.engineerclient.storm

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Storm's crush rule, as worked out from recorded F7 runs. Kept free of Minecraft so it tests
 * headlessly.
 *
 *  - Hypixel checks for a crush once every [CHECK_PERIOD] server ticks, counted from when Storm's
 *    phase starts (his wither appears one server tick before "Pathetic Maxor, just like
 *    expected."): every crush line in the recordings landed on that grid, give or take the one
 *    tick the recording can't resolve.
 *  - On a check, Storm is crushed by a pillar when his position (his feet) is inside that
 *    pillar's crush zone - a 6x6 square, [minX, minX + 6] x [minZ, minZ + 6], half a block toward
 *    -x/-z of the pillar's own 7x7 - and his head ([HEAD] above his feet) is at or above the
 *    pillar's lowest block, and that pillar stepped down within the last [ARMED_TICKS] ticks.
 *
 * The zone test is the same as asking whether a 1x1 column with its -x/-z corner on Storm's
 * position fits completely inside the pillar's 7x7 square (the rounded corners included): that
 * column, [HEAD] tall, is the hitbox the Storm Phase module draws.
 */
object StormCrush {

    /** Server ticks between crush checks. */
    const val CHECK_PERIOD = 20

    /**
     * How far above Storm's feet the check's head point sits. The recordings put it between 3.000
     * (crushed) and 3.41 (not crushed): since Hypixel's boss update (Oct 2026), pinned crushes often
     * have his feet at y 173.00-173.06 under a pillar bottom of 176, where a wither's eye height
     * (2.975) would have missed.
     */
    const val HEAD = 3.0

    /**
     * How long a pillar keeps crushing after its last step down: crushes came up to 60 ticks
     * after it, and none at 63 or later.
     */
    const val ARMED_TICKS = 60

    /** How far the crush zone reaches from its -x/-z corner, and the pillar's own width. */
    const val ZONE = 6
    const val PILLAR = 7

    /**
     * A crusher: its 7x7 square's -x/-z corner (the rounded pillar is 37 blocks inside it) and the
     * colour of the terracotta under it. Red never moved in any recorded F7 run; it is here so the
     * hitbox still shows beside it.
     */
    data class Pillar(val name: String, val colour: String, val minX: Int, val minZ: Int) {
        /** The column the pistons run down: every layer of the pillar moves together. */
        val columnX get() = minX + 3
        val columnZ get() = minZ + 3
    }

    val PILLARS = listOf(
        Pillar("Purple", "§5", 97, 62),
        Pillar("Yellow", "§e", 43, 62),
        Pillar("Green", "§a", 43, 38),
        Pillar("Red", "§c", 97, 38),
    )

    /** Where the pillars can hang: the bottom scan runs from [TOP] down to just above the floor. */
    const val TOP = 204
    const val FLOOR = 169

    /** How far inside the zone Storm's position is, sideways: negative is how far outside. */
    fun inset(p: Pillar, x: Double, z: Double): Double =
        min(min(x - p.minX, p.minX + ZONE - x), min(z - p.minZ, p.minZ + ZONE - z))

    /** Distance from Storm's position to the zone, sideways (0 inside). */
    fun distance(p: Pillar, x: Double, z: Double): Double {
        val dx = max(max(p.minX - x, 0.0), x - (p.minX + ZONE))
        val dz = max(max(p.minZ - z, 0.0), z - (p.minZ + ZONE))
        return hypot(dx, dz)
    }

    /** The pillar whose zone is nearest [x], [z]. */
    fun nearest(x: Double, z: Double): Pillar = PILLARS.minBy { distance(it, x, z) }

    /** How far Storm's head is above the pillar's lowest block (negative: below it). */
    fun headAbove(y: Double, bottom: Int): Double = y + HEAD - bottom

    /** Whether a pillar that last stepped down at [lastStep] still crushes at [now] (server ticks). */
    fun armed(lastStep: Int?, now: Int): Boolean = lastStep != null && now - lastStep in 0..ARMED_TICKS

    /**
     * One check, judged against one pillar: [inset] and [head] are the two margins (both >= 0
     * means Storm is where the check wants him).
     */
    data class Verdict(val pillar: Pillar, val inset: Double, val head: Double?) {
        val inside: Boolean get() = inset >= 0 && head != null && head >= 0
    }

    fun judge(p: Pillar, x: Double, y: Double, z: Double, bottom: Int?): Verdict =
        Verdict(p, inset(p, x, z), bottom?.let { headAbove(y, it) })

    /** Whether [ticks] since the phase started is a crush check. */
    fun isCheck(ticks: Int): Boolean = ticks > 0 && ticks % CHECK_PERIOD == 0
}
