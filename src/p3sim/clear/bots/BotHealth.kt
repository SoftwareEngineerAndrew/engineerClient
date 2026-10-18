package com.engineerclient.p3sim.clear.bots

import kotlin.random.Random

/**
 * What a teammate's health looks like from outside: how much it can hold, how often a mob lands a hit, and the run of hits that comes before a
 * death. Pure (no world), so the tests can check the numbers; [BotsModule] applies them through the damage service.
 */
object BotHealth {
    /** Hits a teammate takes while it works in a room with living mobs, a minute (MEASURED: teammates play the hurt animation 2.2 times a minute in the clear). */
    const val HITS_PER_MINUTE = 2.2

    /**
     * Size of one hit, percent of max health, as (share of hits, size) points. UNMEASURED: the recordings show the sidebar number falling, not the
     * hit; these are mid-sized hits that the health regeneration brings back in two to ten seconds.
     */
    val HIT_PERCENT: List<Pair<Double, Double>> = listOf(0.0 to 4.0, 0.5 to 9.0, 0.9 to 16.0, 1.0 to 24.0)

    /** An ordinary hit never takes a teammate below this (deaths are the scheduled ones, [TeamDeath]). */
    const val HP_FLOOR = 25.0

    /** A death is preceded by two to four hits within this many ticks, at least [RUN_UP_GAP] ticks apart (the damage ledger ignores a hit inside 10 ticks of the last). */
    const val RUN_UP_WINDOW = 40
    const val RUN_UP_GAP = 10

    /** Each run-up hit takes this share of the health the teammate has then. UNMEASURED. */
    val RUN_UP_SHARE = 0.25..0.45

    /**
     * The share of scheduled deaths that are a dropped connection ("disconnected and became a ghost", no mob and no run-up hits): 5 of the 29
     * recorded clear deaths.
     */
    const val DISCONNECT_SHARE = 0.17

    /** A scheduled death waits this long for a teammate that stands in a room with a living mob to take the blame; then it is "died". UNMEASURED. */
    const val VICTIM_PATIENCE = 400

    /**
     * The highest sidebar health value of a teammate over a run, by class, at the 10th, 50th and 90th percent of the recordings (it includes
     * absorption, so read it as the shape of what players see, not as the class's stat). Class to (p10, p50, p90).
     */
    private val MAX_HP = mapOf(
        "ARCHER" to Triple(21_000, 26_700, 35_800),
        "MAGE" to Triple(24_200, 37_200, 48_300),
        "HEALER" to Triple(16_700, 24_200, 47_800),
        "TANK" to Triple(22_200, 30_500, 45_600),
        "BERSERK" to Triple(15_600, 33_000, 45_900),
    )

    /** The health number a teammate of [cls] shows at full health, from the uniform draw [u]. */
    fun maxHp(cls: String, u: Double): Int {
        val (p10, p50, p90) = MAX_HP[cls.uppercase()] ?: MAX_HP.getValue("MAGE")
        return PlanMath.curve(listOf(0.0 to p10 * 0.9, 0.1 to p10.toDouble(), 0.5 to p50.toDouble(), 0.9 to p90.toDouble(), 1.0 to p90 * 1.1), u).toInt()
    }

    /** The size of an ordinary hit from the uniform draw [u]. */
    fun hitPercent(u: Double): Double = PlanMath.curve(HIT_PERCENT, u)
}

/** Decides each tick, for each teammate that is fighting, whether a mob lands a hit and how big it is. */
class HitClock(private val random: Random) {
    /** The size of the hit this tick, or null for none. */
    fun roll(): Double? = if (random.nextDouble() < BotHealth.HITS_PER_MINUTE / 1200.0) BotHealth.hitPercent(random.nextDouble()) else null
}
