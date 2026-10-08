package com.devgineerclient.splits

/**
 * When to hit Necron, as a line under his sub splits: the two hits that move his fight on, each
 * counted down, called while its window is open, and marked on time or late.
 *
 * His fight since Hypixel's boss update (Oct 2026), n counting server ticks from his first line:
 *
 *  1. 20% once his scripted sidestep is over: he leaves mid at 81-84 and can't be hit for 7;
 *     hit, he is put back on mid (typically 93-125 on F7, as late as ~158 on M7).
 *  2. The kill, once the floor lifts: ARGH! is said at 269-273 however late he was back (so the
 *     slot is 265 on the 20-tick grid, n = 5 mod 20), and he dies on a later tick of the same
 *     grid - 365 at the earliest: the burst of TNT he dies in comes 4-12 after it (usually
 *     369-377); a kill one grid tick late bursts around 391, and a slower one sees him leave mid
 *     again (around 492).
 *
 * His death is the TNT burst ([onDeath]); "All this, for nothing..." also ends the cue should it
 * come. Every other hit does nothing for the time. Nothing here touches Minecraft; the module
 * feeds his position, the TNT and the clock in.
 */
class NecronHitCue {

    private var start: Int? = null
    /** Tick he left mid, was put back, and ARGH! was said. */
    private var left1: Int? = null
    private var back1: Int? = null
    private var argh: Int? = null
    private var ended = false

    fun reset() { start = null; left1 = null; back1 = null; argh = null; ended = false }

    /** His first line: the fight's n = 0. */
    fun onStart(tick: Int) { reset(); start = tick }

    /** His death (the TNT burst, or "All this, for nothing..." should it come): the cue is done with. */
    fun onDeath() { if (start != null) ended = true }

    /** A chat line: his first starts the fight, his first ARGH! is the floor lifting. */
    fun onChat(msg: String, tick: Int) {
        when {
            msg in START_LINES -> onStart(tick)
            msg == ARGH -> if (start != null && !ended && argh == null) argh = tick
            msg == END_LINE -> onDeath()
        }
    }

    /** How far the server has him from mid: his sidestep off it, and his teleport back. */
    fun onPosition(tick: Int, fromMid: Double) {
        if (start == null || ended) return
        when {
            left1 == null -> if (fromMid > OFF_MID) left1 = tick
            back1 == null -> if (fromMid < ON_MID) back1 = tick
        }
    }

    val active get() = start != null && !ended

    private fun n0() = start ?: 0
    private fun rel(t: Int?) = t?.let { it - n0() }

    /** The grid tick the floor lifted on: ARGH!'s, which is said 4-8 after it; 265 until it comes. */
    private fun floorLift(): Int = rel(argh)?.let { maxOf(FIRST_SLOT, grid(it - ARGH_DELAY)) } ?: FIRST_SLOT

    /** The line for now, or null outside his fight. Colour codes included. */
    fun line(tick: Int): String? {
        if (!active) return null
        val n = tick - n0()
        val l1 = rel(left1); val b1 = rel(back1)

        // Hit 1: from the end of the sidestep to his teleport back.
        if (b1 == null) {
            val open = (l1 ?: L1) + SIDESTEP
            if (l1 == null || n < open) return wait(1, 20, open - n)
            return now("§e20%", B1_DEADLINE - n, 0)
        }
        val lift = floorLift()
        val death = lift + KILL

        // The kill: from the floor lifting to the first grid tick he can die on.
        if (n < lift && argh == null) return done(b1) + " §8· " + wait(2, null, lift - n)
        return now("§ckill", death - n, grid(n) - death)
    }

    private fun wait(hit: Int, pct: Int?, ticks: Int) =
        "§7" + (if (pct == null) "Kill" else "Hit $hit §e$pct%") + " §7in §f" + SplitFormat.seconds(ticks.coerceAtLeast(0) * 50L)

    /** A window open: how long is left, or how much it costs already. */
    private fun now(what: String, left: Int, lost: Int) =
        if (left >= 0 && lost <= 0) "§6§lHIT NOW $what §7· §f" + SplitFormat.seconds(left * 50L) + " §7left"
        else "§c§lHIT NOW $what §7· §clate" + (if (lost > 0) " +" + SplitFormat.seconds(lost * 50L) else "")

    /** Hit 1: on time if he was back by the latest tick known to still make the first slot. */
    private fun done(b1: Int) = if (b1 <= B1_DEADLINE) "§aHit 1 on time" else "§eHit 1 in, late"

    companion object {
        /** He leaves mid at 81-84 (82 typical), his sidestep can't be hit for 7. */
        const val L1 = 82
        const val SIDESTEP = 7
        /** The grid: n = 5 mod 20. The floor lifts on 265 (ARGH! said 269-273, so 4-8 after its slot). */
        const val FIRST_SLOT = 265
        const val ARGH_DELAY = 8
        /**
         * The latest he is known to be back and still have ARGH! on 265: ~158 (M7; ~125 on F7). Later
         * than that wasn't seen, so it is when hit 1 stops being called on time, not a known cost.
         */
        const val B1_DEADLINE = 158
        /** He dies on the grid 100 after the floor lifts at the earliest: 365, the burst at 369-377. */
        const val KILL = 100
        val START_LINES = setOf(
            "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
            "[BOSS] Necron: You went further than any human before, congratulations.",
        )
        const val ARGH = "[BOSS] Necron: ARGH!"
        /** His death line from before the boss update; still handled should it come. */
        const val END_LINE = "[BOSS] Necron: All this, for nothing..."
        const val OFF_MID = 0.5
        const val ON_MID = 0.05

        /** The first grid tick (5 mod 20) at or after [n]. */
        fun grid(n: Int): Int = if (n <= 5) 5 else 5 + ((n - 5 + 19) / 20) * 20
    }
}
