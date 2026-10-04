package com.engineerclient.splits

/**
 * When to hit Necron, as a line under his sub splits: the three hits that move his fight on, each
 * counted down, called while its window is open, and marked on time or late.
 *
 * His health stops at 80%, 25% and 5% (docs/mechanics/necron.md, the boss bar in the recorded
 * runs), and he only moves on once pushed past each:
 *
 *  1. 20% (to 80%) once his scripted sidestep is over (7 ticks after he leaves mid): he is put back
 *     on mid, and ARGH! comes on the first tick of the 20-tick grid (n = 5 mod 20) at least 141
 *     after that - so back by 184 keeps the 325 slot.
 *  2. 55% (to 25%) once the floor lifts on that grid tick: he fires volley 2 when he gets there,
 *     and leaves mid 60 ticks later.
 *  3. 20% (to 5%) once he leaves mid: he is put back on mid, and ARGH! 2 is again the first grid
 *     tick at least 141 after. The fastest fight has it 220 after the first.
 *
 * Every other hit does nothing for the time. [n] counts server ticks from his first line. Nothing
 * here touches Minecraft; the module feeds his position, his boss bar and the clock in.
 */
class NecronHitCue {

    private var start: Int? = null
    /** Tick he left mid, was put back, the 2nd time left and put back; 25% first seen on the bar. */
    private var left1: Int? = null
    private var back1: Int? = null
    private var left2: Int? = null
    private var back2: Int? = null
    private var at25: Int? = null
    private var ended = false

    fun reset() { start = null; left1 = null; back1 = null; left2 = null; back2 = null; at25 = null; ended = false }

    /** His first line: the fight's n = 0. */
    fun onStart(tick: Int) { reset(); start = tick }

    /** "All this, for nothing...": the cue is done with. */
    fun onEnd() { if (start != null) ended = true }

    /** A chat line: his first starts the fight, "All this, for nothing..." ends it. */
    fun onChat(msg: String, tick: Int) {
        if (msg in START_LINES) onStart(tick) else if (msg == END_LINE) onEnd()
    }

    /** How far the server has him from mid. He leaves it twice and is put back exactly. */
    fun onPosition(tick: Int, fromMid: Double) {
        if (start == null || ended) return
        when {
            left1 == null -> if (fromMid > OFF_MID) left1 = tick
            back1 == null -> if (fromMid < ON_MID) back1 = tick
            // The second trip only starts once the floor has lifted (he stays on mid till volley 2 is done).
            left2 == null -> if (fromMid > OFF_MID && tick >= n0() + floorLift()) left2 = tick
            back2 == null -> if (fromMid < ON_MID) back2 = tick
        }
    }

    /** His boss bar's progress (his health): 25% after he's back on mid is hit 2 done. */
    fun onBar(tick: Int, progress: Float) {
        if (start == null || ended || back1 == null || at25 != null) return
        if (progress <= 0.255f) at25 = tick
    }

    val active get() = start != null && !ended

    private fun n0() = start ?: 0
    private fun rel(t: Int?) = t?.let { it - n0() }

    /** The grid tick the floor lifts on (ARGH! 1's slot): the first at least 141 after he's back, 325 at the earliest. */
    private fun floorLift(): Int = maxOf(FIRST_SLOT, grid((rel(back1) ?: B1_DEADLINE) + LOCK))

    /** The line for now, or null outside his fight. Colour codes included. */
    fun line(tick: Int): String? {
        if (!active) return null
        val n = tick - n0()
        val l1 = rel(left1); val b1 = rel(back1); val l2 = rel(left2); val b2 = rel(back2); val v = rel(at25)

        // Hit 1: from the end of the sidestep to his teleport back.
        if (b1 == null) {
            val open = (l1 ?: L1) + SIDESTEP
            if (l1 == null || n < open) return wait(1, 20, open - n)
            return now(20, B1_DEADLINE - n, grid(n + LOCK) - FIRST_SLOT)
        }
        val g1 = floorLift()
        val lost1 = g1 - FIRST_SLOT
        val target2 = g1 + ARGH_GAP        // ARGH! 2's slot if hits 2 and 3 are quick

        // Hit 2: from the floor lifting until the bar shows 25% (or he leaves mid, should no bar come).
        if (v == null && l2 == null) {
            if (n < g1) return done(1, lost1) + " §8· " + wait(2, 55, g1 - n)
            return now(55, g1 + HIT2_TARGET - n, 0)
        }

        // Hit 3: from his leaving mid to his teleport back.
        if (b2 == null) {
            if (l2 == null) {
                // He leaves 60 ticks after volley 2, which starts right as he reaches 25%.
                val eta = (v ?: n) + VOLLEY_TO_LEAVE - n
                return "§aHit 2 done §8· " + wait(3, 20, eta.coerceAtLeast(0), "when he leaves mid")
            }
            // A slow hit 2 may have cost the fastest slot already: then the next one he can still make.
            val slot = maxOf(target2, grid(l2 + 1 + LOCK))
            return now(20, slot - LOCK - n, grid(n + LOCK) - slot)
        }

        // All three in: what the fight comes to.
        val end = grid(b2 + LOCK) + END_AFTER
        val lost = end - FASTEST
        return "§aAll hits in §8· §7stop hitting §8· §7Necron " +
            (if (lost <= 0) "§a" else "§c") + SplitFormat.seconds(end * 50L) + (if (lost > 0) " §c(+" + SplitFormat.seconds(lost * 50L) + ")" else "")
    }

    private fun wait(hit: Int, pct: Int, ticks: Int, what: String = "in") =
        "§7Hit $hit §e$pct% §7$what " + (if (what == "in") "§f" else "§8~") + SplitFormat.seconds(ticks.coerceAtLeast(0) * 50L)

    /** A window open: how long is left, or how much it costs already. */
    private fun now(pct: Int, left: Int, lost: Int) =
        if (left >= 0 && lost <= 0) "§6§lHIT NOW §e$pct% §7· §f" + SplitFormat.seconds(left * 50L) + " §7left"
        else "§c§lHIT NOW §e$pct% §7· §clate" + (if (lost > 0) " +" + SplitFormat.seconds(lost * 50L) else "")

    private fun done(hit: Int, lost: Int) = if (lost <= 0) "§aHit $hit on time" else "§cHit $hit late +" + SplitFormat.seconds(lost * 50L)

    companion object {
        /** He leaves mid at 159, his sidestep can't be hit for 7. */
        const val L1 = 159
        const val SIDESTEP = 7
        /** The ARGH grid: n = 5 mod 20; the first slot, 325; ARGH! at least 141 after he's back. */
        const val FIRST_SLOT = 325
        const val LOCK = 141
        const val B1_DEADLINE = FIRST_SLOT - LOCK
        /** Hit 2 done within this of the floor lifting leaves hit 3 its window. */
        const val HIT2_TARGET = 15
        const val VOLLEY_TO_LEAVE = 60
        /** The fastest fight: ARGH! 2 220 after ARGH! 1's slot, "All this, for nothing..." 62 after it, at 607. */
        const val ARGH_GAP = 220
        const val END_AFTER = 62
        const val FASTEST = FIRST_SLOT + ARGH_GAP + END_AFTER
        val START_LINES = setOf(
            "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
            "[BOSS] Necron: You went further than any human before, congratulations.",
        )
        const val END_LINE = "[BOSS] Necron: All this, for nothing..."
        const val OFF_MID = 0.5
        const val ON_MID = 0.05

        /** The first grid tick (5 mod 20) at or after [n]. */
        fun grid(n: Int): Int = if (n <= 5) 5 else 5 + ((n - 5 + 19) / 20) * 20
    }
}
