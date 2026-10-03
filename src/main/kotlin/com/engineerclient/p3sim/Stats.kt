package com.engineerclient.p3sim

/**
 * Your run's times, said in chat as they happen (Section Times): each section with the fast
 * runs' median beside it, your own completions, death ticks taken, and a summary at the core.
 */
object Stats {
    /** The fast Better PF runs' medians (terminal-roles.md), ticks. */
    private val FAST = intArrayOf(0, 252, 185, 191, 150)
    private const val FAST_P3 = 797

    private var from = 1
    private val sections = IntArray(5) { -1 }
    private var mine = 0
    private var deaths = 0

    fun reset(from: Int) { this.from = from; sections.fill(-1); mine = 0; deaths = 0 }

    private fun s(ticks: Int) = "%.2fs".format(ticks / 20.0)

    private fun vs(t: Int, ref: Int): String {
        val d = t - ref
        return if (d <= 0) "§a-${s(-d)}" else "§c+${s(d)}"
    }

    fun done(st: Station, n: Int) {
        mine++
    }

    fun section(s: Int, ticks: Int, n: Int) {
        if (s !in 1..4 || sections[s] >= 0) return
        sections[s] = ticks
        if (P3Sim.showTimes) Sim.note("§fS$s§7 ${s(ticks)} §8(fast runs ${s(FAST[s])}, ${vs(ticks, FAST[s])}§8)")
    }

    fun deathTick(n: Int) { deaths++ }

    fun p3(n: Int) {
        if (!P3Sim.showTimes) return
        val parts = (1..4).filter { sections[it] >= 0 }.joinToString(" §8|§7 ") { "S$it ${s(sections[it])}" }
        val total = if (from == 1) " §8|§f P3 ${s(n)} §8(fast ${s(FAST_P3)}, ${vs(n, FAST_P3)}§8)" else ""
        Sim.note("$parts$total")
        Sim.note("You did §f$mine§7 of the jobs; death ticks taken: §f$deaths")
    }

    fun goldorDone(n: Int) {}

    /** The server tick a full run (from P1) started, or -1. */
    var runStart = -1

    fun runTicks() = if (runStart >= 0) Fight.serverTick - runStart else 0

    private var lightnings = 0
    fun lightning() { lightnings++ }
}
