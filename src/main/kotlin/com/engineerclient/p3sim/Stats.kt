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
        val run = Ghosts.run
        val (what, ref) = if (run != null) "the run" to run.sectionTimes()[s - 1] else "fast runs" to FAST[s]
        val best = best("S$s", ticks)
        if (P3Sim.showTimes) Sim.note("§fS$s§7 ${s(ticks)} §8($what ${s(ref)}, ${vs(ticks, ref)}§8)$best")
    }

    // ------------------------------------------------------------------ personal bests

    private val bestFile get() = java.io.File(net.minecraft.client.Minecraft.getInstance().gameDirectory, "config/engineerclient/p3sim-pb.properties")
    private val bests: java.util.Properties by lazy { java.util.Properties().also { p -> runCatching { bestFile.inputStream().use { p.load(it) } } } }

    /** Records [ticks] for [key] if it's a best; the chat suffix. */
    private fun best(key: String, ticks: Int): String {
        val old = bests.getProperty(key)?.toIntOrNull()
        if (old != null && ticks >= old) return " §8PB ${s(old)}"
        bests.setProperty(key, ticks.toString())
        runCatching { bestFile.parentFile.mkdirs(); bestFile.outputStream().use { bests.store(it, "P3 Sim personal bests, server ticks") } }
        return if (old == null) " §6PB" else " §6§lNEW PB §8(was ${s(old)})"
    }

    fun deathTick(n: Int) { deaths++ }

    fun p3(n: Int) {
        if (!P3Sim.showTimes) return
        val parts = (1..4).filter { sections[it] >= 0 }.joinToString(" §8|§7 ") { "S$it ${s(sections[it])}" }
        val run = Ghosts.run
        val (what, ref) = if (run != null) "the run" to run.core else "fast" to FAST_P3
        val total = if (from == 1) " §8|§f P3 ${s(n)} §8($what ${s(ref)}, ${vs(n, ref)}§8)${best("P3", n)}" else ""
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
