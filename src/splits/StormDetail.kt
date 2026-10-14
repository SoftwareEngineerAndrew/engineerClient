package com.engineerclient.splits

import com.engineerclient.storm.StormCrush
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The Storm sub split's moments beyond its steps ([SubSplitTracker]'s Opening, Crush, Pin, Flight,
 * Crush, Kill, Animation), all in server ticks from his phase's start (his wither appears a tick
 * before "Pathetic Maxor, just like expected."), as Storm Phase and Timers count them:
 *
 *  - Giga Lightning striking: 33 ticks after "I'd be happy to show you what that's like!" (519-521
 *    into the phase in 662 F7 and 371 M7 recordings; 520 when the line wasn't seen), and his
 *    ENERGY / THUNDER line 29 ticks after it;
 *  - him leaving his spot after it, and reaching Yellow after he breaks free;
 *  - every pad read: a pillar stepping down (its layers going through moving pistons), whichever
 *    pillar it was;
 *  - each crush, by the pillar nearest him, and how many 20-tick checks after the first one he
 *    could have been crushed on it came: Purple's first is the first check from 109 ticks after his
 *    lightning line, Yellow's the first from 87 ticks after he breaks free (84 on M7);
 *  - with Show Crush Info, every crush check with him near a pillar: in or out of its zone, and by
 *    how much ([StormCrush]).
 *
 * Nothing here touches Minecraft; the Sub Splits module feeds it.
 */
class StormDetail {

    /** A moment, [tick] server ticks into the phase. */
    class Moment(val tick: Int, val text: String)

    private val moments = mutableListOf<Moment>()

    /** The server tick the phase started on, or null outside it. */
    var start: Int? = null; private set
    private var happy: Int? = null
    private var strikeShown = false
    private var lightning: Int? = null
    private var enraged: Int? = null
    private var leftSpot = false
    private var reachedYellow = false
    private var crushes = 0
    private var m7 = false
    private var ended = false
    private var dead = false

    /** Where the server last put Storm. */
    private var last: Triple<Double, Double, Double>? = null

    /** The server tick each pillar's latest step down started on. */
    private val stepStart = HashMap<StormCrush.Pillar, Int>()
    private val reads = HashMap<StormCrush.Pillar, Int>()

    fun reset() {
        moments.clear(); start = null; happy = null; strikeShown = false; lightning = null; enraged = null
        leftSpot = false; reachedYellow = false; crushes = 0; ended = false; dead = false; crushAt = null; last = null
        stepStart.clear(); reads.clear()
    }

    val active get() = start != null && !ended

    /** Crush checks matter: from his lightning line until he dies. */
    val checking get() = active && lightning != null && !dead

    /** Phase ticks at server tick [tick]. */
    fun phase(tick: Int): Int? = start?.let { tick - it }

    /** The moments so far, in the order they happened. */
    fun moments(): List<Moment> = moments.sortedBy { it.tick }

    fun onChat(msg: String, tick: Int, isM7: Boolean) {
        if (msg == STORM_START) { reset(); start = tick - 1; m7 = isM7; return }
        val t = phase(tick) ?: return
        if (ended) return
        when (msg) {
            HAPPY -> if (happy == null) happy = tick
            in LIGHTNING -> if (lightning == null) {
                lightning = t
                add(t, "§eLightning line")
            }
            in CRUSHED -> onCrush(t)
            ENRAGED -> if (enraged == null) { enraged = t; add(t, "§bBroke free §8(pinned " + secs(t - (crushAt ?: t)) + ")") }
            STORM_DEAD -> { dead = true; add(t, "§aDead") }
            GOLDOR_START -> ended = true
        }
    }

    private var crushAt: Int? = null

    private fun onCrush(t: Int) {
        crushes++
        crushAt = t
        val pillar = last?.let { (x, _, z) -> StormCrush.nearest(x, z) }
        // The check it came on: crush lines arrive on the check tick or a few after.
        val check = Math.floorDiv(t + CHECK_SLACK, StormCrush.CHECK_PERIOD) * StormCrush.CHECK_PERIOD
        val earliest = when (crushes) {
            1 -> lightning?.let { firstCheck(it + PURPLE_AFTER_LIGHTNING) }
            2 -> enraged?.let { firstCheck(it + if (m7) YELLOW_AFTER_ENRAGE_M7 else YELLOW_AFTER_ENRAGE) }
            else -> null
        }
        val timing = earliest?.let { e ->
            val late = (check - e) / StormCrush.CHECK_PERIOD
            when {
                late <= 0 -> " §a(first check)"
                late == 1 -> " §e(1 check late)"
                else -> " §c($late checks late)"
            }
        }.orEmpty()
        val by = pillar?.let { " by " + it.colour + it.name } ?: ""
        add(t, "§6Crushed$by §8t$check$timing")
    }

    /** Server tick [tick]: Giga Lightning once its tick is reached. */
    fun onServerTick(tick: Int) {
        val t = phase(tick) ?: return
        if (ended || strikeShown) return
        val strike = happy?.let { it - start!! + STRIKE_AFTER_HAPPY }
        val at = strike ?: LIGHTNING_STRIKE
        if (t >= at) {
            strikeShown = true
            add(at, "§eGiga Lightning" + if (strike == null) " §8(~t$LIGHTNING_STRIKE)" else "")
        }
    }

    /** Storm where the server last put him, on server tick [tick]. */
    fun onPosition(tick: Int, x: Double, y: Double, z: Double) {
        val t = phase(tick) ?: return
        last = Triple(x, y, z)
        if (ended) return
        if (!leftSpot && lightning != null && hypot(x - SPOT_X, z - SPOT_Z) > 0.5) {
            leftSpot = true
            add(t, "§bLeft his spot §8(" + secs(t - lightning!!) + " after the line)")
        }
        if (!reachedYellow && enraged != null && hypot(x - YELLOW_X, z - YELLOW_Z) <= YELLOW_REACHED) {
            reachedYellow = true
            add(t, "§eReached Yellow")
        }
    }

    /**
     * A moving piston in [pillar]'s column on server tick [tick]: a step down. One read moves its
     * layers over ~17 ticks, and reads are 20 apart, so updates within [STEP_SPAN] of a step's first
     * are that step.
     */
    fun onPillarStep(pillar: StormCrush.Pillar, tick: Int) {
        val t = phase(tick) ?: return
        if (ended) return
        val prev = stepStart[pillar]
        if (prev != null && tick - prev < STEP_SPAN) return
        stepStart[pillar] = tick
        val n = (reads[pillar] ?: 0) + 1
        reads[pillar] = n
        add(t, pillar.colour + pillar.name + " pad §7read " + n)
    }

    /** One crush check's verdict near a pillar (Show Crush Info), on server tick [tick]. */
    fun onCheck(tick: Int, v: StormCrush.Verdict, armed: Boolean) {
        val t = phase(tick) ?: return
        if (ended) return
        val side = (if (v.inset >= 0) "§a" else "§c") + fmt(abs(v.inset)) + if (v.inset >= 0) " in" else " out"
        val head = v.head?.let { (if (it >= 0) "§a" else "§c") + "head " + (if (it >= 0) "+" else "-") + fmt(abs(it)) } ?: "§7no pillar"
        add(t, "§8check " + v.pillar.colour + v.pillar.name + " " + side + " §7· " + head + if (armed) "" else " §8(not lowered)")
    }

    private fun add(t: Int, text: String) { moments += Moment(t, text) }

    private fun firstCheck(t: Int) = Math.floorDiv(t + StormCrush.CHECK_PERIOD - 1, StormCrush.CHECK_PERIOD) * StormCrush.CHECK_PERIOD

    companion object {
        const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
        const val HAPPY = "[BOSS] Storm: I'd be happy to show you what that's like!"
        const val ENRAGED = "⚠ Storm is enraged! ⚠"
        const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
        const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
        val LIGHTNING = setOf("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")
        val CRUSHED = setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!")

        /** Giga Lightning: 520 ticks into the phase, 33 after "I'd be happy to show you...". */
        const val LIGHTNING_STRIKE = 520
        const val STRIKE_AFTER_HAPPY = 33
        /** He is in Purple's zone for the first check from this long after his lightning line. */
        const val PURPLE_AFTER_LIGHTNING = 109
        /** ...and in Yellow's for the first check from this long after he breaks free. */
        const val YELLOW_AFTER_ENRAGE = 87
        const val YELLOW_AFTER_ENRAGE_M7 = 84
        /** Ticks a crush line can trail its check by. */
        const val CHECK_SLACK = 5
        /** A pillar's step down: its updates span ~17 ticks. */
        const val STEP_SPAN = 18

        /** His parking spot after the lightning, and Yellow's point he flies to. */
        const val SPOT_X = 102.375
        const val SPOT_Z = 52.375
        const val YELLOW_X = 46.0
        const val YELLOW_Z = 65.0
        const val YELLOW_REACHED = 2.4

        fun secs(ticks: Int): String = String.format(Locale.ROOT, "%.2fs", ticks / 20.0)
        private fun fmt(d: Double) = String.format(Locale.ROOT, "%.2f", d)
    }
}
