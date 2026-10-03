package com.engineerclient.p3sim

/**
 * Your run's times, said in chat as they happen (Section Times): each section with your personal
 * best (per bot-time setting), your own completions, death ticks taken, and a summary at the core.
 */
object Stats {
    private var from = 1
    private val sections = IntArray(5) { -1 }
    private var mine = 0
    private var deaths = 0

    fun reset(from: Int) { this.from = from; sections.fill(-1); mine = 0; deaths = 0 }

    private fun s(ticks: Int) = "%.2fs".format(ticks / 20.0)

    fun done(st: Station, n: Int) {
        mine++
    }

    fun section(s: Int, ticks: Int, n: Int) {
        if (s !in 1..4 || sections[s] >= 0) return
        sections[s] = ticks
        val best = best("S$s", ticks)
        if (P3Sim.showTimes) Sim.note("§fS$s§7 ${s(ticks)}$best")
    }

    // ------------------------------------------------------------------ personal bests

    private val bestFile get() = java.io.File(net.minecraft.client.Minecraft.getInstance().gameDirectory, "config/engineerclient/p3sim-pb.properties")
    private val bests: java.util.Properties by lazy { java.util.Properties().also { p -> runCatching { bestFile.inputStream().use { p.load(it) } } } }

    /** Records [ticks] for [key] if it's a best; the chat suffix. */
    private fun best(what: String, ticks: Int): String {
        val key = "$what @ bots ${P3Plan.botMin}-${P3Plan.botMax}s"
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
        val total = if (from == 1) " §8|§f P3 ${s(n)}${best("P3", n)}" else ""
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
