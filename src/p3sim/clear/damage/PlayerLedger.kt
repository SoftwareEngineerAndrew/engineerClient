package com.engineerclient.p3sim.clear.damage

import com.engineerclient.p3sim.run.PlayerRecord

/**
 * The party member's health as the dungeon keeps it: a percent of max HP (0 to 100), not hearts. The vanilla health bar a
 * player sees is `40 * percent / 100`, so a hit of 2 percent takes 0.8 of a vanilla hit point. Pure bookkeeping on a
 * [PlayerRecord]; the world side (the bar, the sounds, the ghost kit, the masks) is [DamageModule]'s.
 *
 * All times are the run clock (`Run.t`, server ticks). Before T0 is planned `Run.t` stands still at 0, which only matters for a
 * hit in the first countdown ticks, when nothing is awake yet.
 */
object PlayerLedger {
    /** Vanilla health of a full bar (Hypixel main: 40 with 16 absorption on top). */
    const val FULL_BAR = 40.0

    /** Ticks between two hits that can both land (10, measured: the histogram of hits peaks at 9 to 10 ticks). */
    const val IMMUNITY_TICKS = 10

    /** A ghost is revived this many ticks after dying (measured median 120, about 6 seconds). */
    const val REVIVE_TICKS = 120

    /** What "No deaths" leaves a player at: one vanilla hit point of 40. */
    const val NO_DEATH_FLOOR = 100.0 / FULL_BAR

    /** Health after a mask or Phoenix saves a life (measured: the bar is back to full within 1 to 2 ticks). */
    const val SAVED_PERCENT = 100.0

    /** Health of a revived ghost. UNMEASURED: assumed full. */
    const val REVIVED_PERCENT = 100.0

    /** What one hit did. */
    enum class Outcome {
        /** Inside the immunity window, a ghost, or a zero hit: nothing taken. */
        IGNORED,
        /** Took the damage and is alive. */
        HURT,
        /** "No deaths": the hit would have killed and was held at the floor instead. */
        HELD,
        /** The hit would take the last health. Nothing was applied yet: the caller tries the masks, then [die] or [saved]. */
        LETHAL,
    }

    class Hit(val outcome: Outcome, val applied: Double)

    /** The vanilla health bar for [percent] (0 to 100): a living player never shows 0. */
    fun vanilla(percent: Double): Double = (FULL_BAR * percent / 100.0).coerceIn(0.5, FULL_BAR)

    /** Whether a hit at [t] falls inside the window of the last one. */
    fun immune(rec: PlayerRecord, t: Int): Boolean = rec.lastHitT != Int.MIN_VALUE && (t.toLong() - rec.lastHitT) < IMMUNITY_TICKS

    /**
     * Applies a hit of [percent] of max HP at run tick [t]. Starts the immunity window for any hit that lands (also a lethal one:
     * the window is what the masks' own protection builds on). [noDeaths] holds the ledger at [NO_DEATH_FLOOR] instead of
     * letting the hit kill.
     */
    fun hit(rec: PlayerRecord, percent: Double, t: Int, noDeaths: Boolean, immunityTicks: Int = IMMUNITY_TICKS): Hit {
        if (rec.ghost || percent <= 0.0 || percent.isNaN()) return Hit(Outcome.IGNORED, 0.0)
        if (rec.lastHitT != Int.MIN_VALUE && (t.toLong() - rec.lastHitT) < immunityTicks) return Hit(Outcome.IGNORED, 0.0)
        val after = rec.hpPercent - percent
        if (after <= 0.0) {
            if (noDeaths) {
                val taken = (rec.hpPercent - NO_DEATH_FLOOR).coerceAtLeast(0.0)
                rec.hpPercent = minOf(rec.hpPercent, NO_DEATH_FLOOR)
                rec.lastHitT = t
                return Hit(Outcome.HELD, taken)
            }
            rec.lastHitT = t
            return Hit(Outcome.LETHAL, rec.hpPercent)
        }
        rec.hpPercent = after
        rec.lastHitT = t
        return Hit(Outcome.HURT, percent)
    }

    /** A mask, Phoenix or the Spirit Mask saved the life of a [Outcome.LETHAL] hit: back to [SAVED_PERCENT]. */
    fun saved(rec: PlayerRecord) { rec.hpPercent = SAVED_PERCENT }

    /** Dies: 0 health, a ghost, one more death, revived at [t] + [REVIVE_TICKS]. The caller counts the team death. */
    fun die(rec: PlayerRecord, t: Int) {
        rec.hpPercent = 0.0
        rec.ghost = true
        rec.deaths++
        rec.reviveAtT = t + REVIVE_TICKS
    }

    /** True when a ghost's time is up at [t]. */
    fun reviveDue(rec: PlayerRecord, t: Int): Boolean = rec.ghost && (rec.reviveAtT?.let { t >= it } ?: false)

    /** Revives: alive again at [REVIVED_PERCENT], no immunity (the next death tick can kill again, as recorded). */
    fun revive(rec: PlayerRecord) {
        rec.ghost = false
        rec.hpPercent = REVIVED_PERCENT
        rec.reviveAtT = null
    }
}
