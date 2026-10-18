package com.engineerclient.p3sim.clear.bots

import kotlin.random.Random

/** What a bot is busy with, which decides what it holds and whether it swings. */
enum class BotActivity { STAND, WALK, FIGHT, SEARCH, PUZZLE;
    val working: Boolean get() = this == FIGHT || this == SEARCH || this == PUZZLE
}

/** What one tick of a bot's body shows besides where it is. [item] is the key of the new main-hand item, or null when it did not change. */
class BodyFrame(val sprint: Boolean, val crouch: Boolean, val bodyYaw: Float, val headYaw: Float, val swing: Boolean, val item: String?)

/**
 * The small life of a teammate's body that a statue lacks, from the recordings of real teammates in the clear (276 teammate-runs, 371 teammate-minutes):
 * an arm swing 18 times a minute while it works, the main hand changing about 12 times a minute (Aspect of the Void 33%, Hyperion 32%, Dungeonbreaker
 * 15%, Bat Wand 9%, Terminator 5%, Spirit Leap 4%, Superboom TNT 2%), sprinting in half the movement updates, crouching in a fifth of the standing
 * stretches of one to three seconds, and a head that keeps turning (24% of standing ticks and 40% of walking ticks change its yaw by more than a
 * degree) without ever straying far from the body (|head - body| p50 0.4, p95 4.2, p99 11.8 degrees). The context weights (which item goes with
 * which work) are UNMEASURED; the pooled shares are the measured ones. Pure; one instance per run, state kept per seat.
 */
class BotMotion(private val random: Random) {
    private class State(var held: String) {
        var nextItemT = Int.MIN_VALUE
        var standStretchEnd = Int.MIN_VALUE
        var crouchUntil = Int.MIN_VALUE
        var nextLookT = Int.MIN_VALUE
        var lookTarget = 0f
        var head = Float.NaN
        var body = Float.NaN
        var lead = 0f
        var swingPending = false
    }

    private val states = HashMap<Int, State>()

    /** Starts a seat holding [item] (its resting item); without this the first [step] starts it on a Hyperion. */
    fun start(seat: Int, item: String) { states[seat] = State(item) }

    /** A swing this tick regardless of the dice (a kill: the cast). */
    fun swingNow(seat: Int) { states.getOrPut(seat) { State(DEFAULT_ITEM) }.swingPending = true }

    /** The item the seat holds now. */
    fun held(seat: Int): String = states[seat]?.held ?: DEFAULT_ITEM

    /**
     * One tick for [seat] at run tick [t]: it moved [speed] blocks since the last tick, [heading] is the way it is going (the way it faced last when it
     * stands) and [activity] what the plan has it doing.
     */
    fun step(seat: Int, t: Int, speed: Double, heading: Float, activity: BotActivity): BodyFrame {
        val s = states.getOrPut(seat) { State(DEFAULT_ITEM) }
        val moving = speed > MOVING_ABOVE
        if (s.head.isNaN()) { s.head = heading; s.body = heading }

        // ---- the main hand ---------------------------------------------------------------------------------------
        var changed: String? = null
        if (s.nextItemT == Int.MIN_VALUE) s.nextItemT = t + gap()
        if (t >= s.nextItemT) {
            s.nextItemT = t + gap()
            val pick = pick(activity, random.nextDouble())
            if (pick != s.held) { s.held = pick; changed = pick }
        }

        // ---- swing, sprint, crouch ---------------------------------------------------------------------------------
        val swing = s.swingPending || (activity.working && random.nextDouble() < SWINGS_PER_MINUTE / 1200.0)
        s.swingPending = false
        val sprint = speed > SPRINT_ABOVE
        var crouch = false
        if (moving) { s.standStretchEnd = Int.MIN_VALUE; s.crouchUntil = Int.MIN_VALUE }
        else {
            if (t >= s.standStretchEnd) {
                s.standStretchEnd = t + random.nextInt(STRETCH_TICKS.first, STRETCH_TICKS.last + 1)
                s.crouchUntil = if (random.nextDouble() < CROUCH_SHARE) s.standStretchEnd else Int.MIN_VALUE
            }
            crouch = t < s.crouchUntil
        }

        // ---- head and body -----------------------------------------------------------------------------------------
        if (moving) {
            // Going somewhere: the body faces the way it goes and the head is mostly on it, with short glances a few degrees off (the head yaw
            // changes by more than a degree in about four ticks of ten, and is within half a degree of the body in half of them).
            s.body = heading
            s.lead *= LEAD_DECAY
            if (random.nextDouble() < GLANCE_CHANCE) s.lead = laplace(GLANCE_SCALE).coerceIn(-LEAD_MAX, LEAD_MAX)
            s.head = s.body + s.lead
            s.nextLookT = Int.MIN_VALUE
        } else {
            // Standing: now and then look at something else within 60 degrees; the head turns to it and the body follows a little behind.
            if (s.nextLookT == Int.MIN_VALUE) { s.lookTarget = s.body; s.nextLookT = t + random.nextInt(LOOK_GAP.first, LOOK_GAP.last + 1) }
            if (t >= s.nextLookT) {
                s.lookTarget = s.body + ((random.nextDouble() * 2.0 - 1.0) * LOOK_RANGE).toFloat()
                s.nextLookT = t + random.nextInt(LOOK_GAP.first, LOOK_GAP.last + 1)
            }
            s.head += (s.lookTarget - s.head).coerceIn(-TURN_PER_TICK, TURN_PER_TICK)
            s.body += (s.head - s.body) * 0.6f
            s.lead = (s.head - s.body).coerceIn(-LEAD_MAX, LEAD_MAX)
        }
        return BodyFrame(sprint, crouch, s.body, s.head, swing, changed)
    }

    private fun laplace(scale: Double): Float {
        val u = random.nextDouble() - 0.5
        return (-scale * Math.signum(u) * Math.log(1.0 - 2.0 * Math.abs(u))).toFloat()
    }

    private fun gap(): Int = maxOf(MIN_ITEM_GAP, (-Math.log(1.0 - random.nextDouble()) * MEAN_ITEM_GAP).toInt())

    companion object {
        const val DEFAULT_ITEM = "hyperion"

        /** Arm swings a minute while a bot works in a room (MEASURED: 18.0 a teammate-minute of the clear). */
        const val SWINGS_PER_MINUTE = 18.0

        /**
         * The mean ticks between draws of the main hand. 12 changes a minute (MEASURED: median 11.9) is a draw every 100 ticks, but a draw lands on the
         * item already in hand a quarter of the time (the shares below), so a draw every 76 ticks gives the 12 changes.
         */
        const val MEAN_ITEM_GAP = 76.0
        const val MIN_ITEM_GAP = 10

        /** Faster than this, blocks a tick, a bot is sprinting (MEASURED: sprinting in 51% of movement updates; the threshold is a walking speed's upper end). */
        const val SPRINT_ABOVE = 0.6

        /** A bot that moved less than this since the last tick is standing. */
        const val MOVING_ABOVE = 0.02

        /** A fifth of the standing stretches of one to three seconds are spent crouching (MEASURED: pose crouching in 20% of the standing stretches). */
        const val CROUCH_SHARE = 0.2
        val STRETCH_TICKS = 20..60

        /** How the head strays from the body while walking (MEASURED |head - body| p50 0.4, p75 1.4, p95 4.2 degrees; the glance shape is fitted to those and to 40% of walking ticks changing the head by more than a degree). */
        const val LEAD_MAX = 12f
        const val LEAD_DECAY = 0.45f
        const val GLANCE_CHANCE = 0.35
        const val GLANCE_SCALE = 2.0

        /** Standing, the head turns to a new target every 20 to 60 ticks, within 60 degrees, at up to 3 degrees a tick (gives 24% of standing ticks with a turn above 1 degree). UNMEASURED shapes. */
        val LOOK_GAP = 20..60
        const val LOOK_RANGE = 60.0
        const val TURN_PER_TICK = 3f

        /** The shares of the main hand over a clear (MEASURED), before the context is applied. */
        val ITEM_SHARES: List<Pair<String, Double>> = listOf(
            "aotv" to 0.33, "hyperion" to 0.32, "breaker" to 0.15, "batwand" to 0.09, "terminator" to 0.05, "leap" to 0.04, "superboom" to 0.02,
        )

        /** What the work makes more likely (UNMEASURED): a blink item while travelling, a sword and bow in a fight, the Dungeonbreaker and Bat Wand searching. */
        private fun boost(activity: BotActivity, item: String): Double = when (activity) {
            BotActivity.WALK -> when (item) { "aotv" -> 2.0; "hyperion" -> 1.3; "breaker" -> 0.6; else -> 1.0 }
            BotActivity.FIGHT -> when (item) { "hyperion" -> 2.0; "terminator" -> 2.0; "batwand" -> 1.5; "aotv" -> 0.5; "breaker" -> 0.5; else -> 1.0 }
            BotActivity.SEARCH -> when (item) { "breaker" -> 3.0; "batwand" -> 1.5; "hyperion" -> 0.5; "aotv" -> 0.7; else -> 1.0 }
            BotActivity.PUZZLE, BotActivity.STAND -> 1.0
        }

        fun pick(activity: BotActivity, u: Double): String {
            val weights = ITEM_SHARES.map { (k, w) -> k to w * boost(activity, k) }
            var x = u * weights.sumOf { it.second }
            for ((k, w) in weights) { if (x < w) return k; x -= w }
            return DEFAULT_ITEM
        }
    }
}
