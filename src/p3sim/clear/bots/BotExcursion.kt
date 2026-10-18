package com.engineerclient.p3sim.clear.bots

/**
 * A short trip off the plan: a bot walks to a key stand, waits there for the key to be taken, and walks back to where the plan has it by then.
 * It moves at walking pace the whole way, so nothing jumps. Pure.
 */
class Excursion(val stand: Pt, private val holdTicks: Int, private val speed: Double = BotTuning.DEFAULT_PLAN_BPT) {
    private enum class Phase { OUT, HOLD, BACK }

    private var phase = Phase.OUT
    private var held = 0

    /** Ends the wait now (the key has been taken); the bot starts back. */
    fun release() { if (phase != Phase.BACK) phase = Phase.BACK }

    /** The tick on which the bot is at the stand and waiting. */
    val waiting: Boolean get() = phase == Phase.HOLD

    /** Where the bot is after one more tick, from [cur]; [home] is where the plan has it now. Null when it is back and the plan takes over. */
    fun step(cur: Pt, home: Pt): Pt? = when (phase) {
        Phase.OUT -> {
            val next = toward(cur, stand)
            if (next.dist(stand) < 0.05) phase = Phase.HOLD
            next
        }
        Phase.HOLD -> {
            if (++held > holdTicks) phase = Phase.BACK
            cur
        }
        Phase.BACK -> if (cur.dist(home) <= speed) null else toward(cur, home)
    }

    private fun toward(a: Pt, b: Pt): Pt {
        val d = a.dist(b)
        return if (d <= speed) b else a.lerp(b, speed / d)
    }
}

/** Who goes to a key stand. Pure. */
object StandDuty {
    /** The seat in [positions] nearest to [at] within [within] blocks, or null. */
    fun nearest(positions: Map<Int, Pt>, at: Pt, within: Double): Int? =
        positions.entries.map { it.key to it.value.dist(at) }.filter { it.second <= within }.minByOrNull { it.second }?.first

    /** Whether somebody (a human) already stands at the stand, in reach of the doors module's pickup. */
    fun someoneThere(humans: List<Pt>, at: Pt, radius: Double = 2.5): Boolean = humans.any { it.dist(at) <= radius }
}
