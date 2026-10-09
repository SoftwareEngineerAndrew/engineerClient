package com.engineerclient.p3sim

import java.util.Locale

/**
 * Practice mode (the menu's Practice tab): a short drill, timed, restarted as often as you like (the
 * Restart or Practice Restart keybind, or a left click with the Infinileap or in its menu).
 *
 * Section practice (s1-s4): P3 at that section with everything but your role's part of it done (its
 * stations and gate; no bots), you on your early enter for it (S1: your spawn; S4: its start). Your
 * jobs there (as the plan times them, so an i4 done in S1 is S1's) and, if it's yours, the early enter
 * into the next section are the tasks, each timed from the start; the last one's time is the
 * practice's, large on screen (the Practice Time HUD) and in chat with your best.
 *
 * Server thread, except what the HUDs read ([mode], [tasks], [ticks], [endTicks]).
 */
object Practice {
    /** One thing to do: a station id, "gate N", or an early enter ("ee" + its key). [at]: ticks from the start, -1 till done. */
    class Task(val id: String, val label: String) { @Volatile var at = -1 }

    /** The practice you're in ("S2", or a drill's name), null: not in practice mode. */
    @Volatile var mode: String? = null
        private set
    /** The section practised (1-4), 0 for a drill. */
    @Volatile var section = 0
        private set
    @Volatile var tasks: List<Task> = emptyList()
        private set
    /** Ticks since the start, and the final time (-1 until every task is done). */
    @Volatile var ticks = 0
        private set
    @Volatile var endTicks = -1
        private set

    val active get() = mode != null

    private var ee: P3Plan.EarlyEnter? = null

    /** Starts section [s]'s practice (again). */
    fun start(s: Int) {
        mode = "S$s"; section = s
        tasks = emptyList(); ticks = 0; endTicks = -1
        Fight.start(listOf(Fight.Start.S1, Fight.Start.S2, Fight.Start.S3, Fight.Start.S4)[s - 1], practice = true)
    }

    /** A drill that isn't made yet: practice mode, nothing to do. */
    fun drill(name: String) {
        Fight.end()
        mode = name; section = 0
        tasks = emptyList(); ticks = 0; endTicks = -1
        Sim.note("Practice §f$name§7 isn't set up yet.")
    }

    /** The same practice again (the Restart keybinds, the Infinileap). */
    fun restart() {
        val s = section
        if (s in 1..4) start(s) else mode?.let { Sim.note("Practice §f$it§7 isn't set up yet.") }
    }

    /** Out of practice mode (any other start, Stop). */
    fun exit() { mode = null; section = 0; tasks = emptyList(); endTicks = -1 }

    /** Your jobs that are section [s]'s in practice: done in it, as the plan times them, in that order. */
    fun jobs(s: Int): List<String> {
        val times = P3Plan.plan().times
        return P3Plan.allJobs().filter { P3Plan.isMine(it) && (times[it]?.first ?: sectionOf(it)) == s }
            .sortedBy { times[it]?.second ?: 99.0 }
    }

    private fun sectionOf(job: String) = job.removePrefix("gate ").toIntOrNull() ?: job.removePrefix("S").substringBefore(' ').toIntOrNull() ?: 0

    /** Where section [s]'s practice puts you: your spawn (S1), the early enter into it, else its start. */
    fun spot(s: Int): Spots.Spot = when (s) {
        1 -> P3Plan.customSpot("spawn") ?: Spots.p3Start(1)
        else -> P3Plan.earlyEnters.firstOrNull { it.into == s }?.let { P3Plan.eeSpot(it) } ?: Spots.p3Start(s)
    }

    /** GoldorPhase's start, in section practice: the tasks. */
    fun begin(s: Int) {
        val list = jobs(s).map { Task(it, label(it)) }.toMutableList()
        ee = P3Plan.ee(if (s == 4) 5 else s + 1)?.takeIf { it.byYou }
        ee?.let { list += Task("ee ${it.key}", it.label) }
        tasks = list; ticks = 0; endTicks = -1
        if (list.isEmpty()) Sim.note("Practice §fS$s§7: nothing of yours (${Roles.label(P3Sim.myClass)}, ${P3Plan.skillName()}) in it.")
        else Sim.note("Practice §fS$s§7: ${list.joinToString("§8, §7") { it.label }}.")
    }

    private fun label(job: String) = when {
        job.startsWith("gate ") -> "Gate"
        else -> job.substringAfter(' ').replaceFirstChar { it.uppercase() }
    }

    fun tick(phase: GoldorPhase) {
        if (endTicks >= 0 || tasks.isEmpty()) return
        ticks = phase.t
        val p = Sim.player
        for (task in tasks) {
            if (task.at >= 0) continue
            val done = when {
                task.id.startsWith("gate ") -> phase.gateIsDown(task.id.removePrefix("gate ").toInt())
                task.id.startsWith("ee ") -> p != null && tasks.all { it === task || it.at >= 0 } && onSpot(p.x, p.y, p.z)
                else -> phase.stations.firstOrNull { it.id == task.id }?.done == true
            }
            if (done) task.at = phase.t
        }
        if (tasks.all { it.at >= 0 }) finish()
    }

    /** On your early enter: within 1.5 blocks of its spot (across) and on its exact height. */
    private fun onSpot(x: Double, y: Double, z: Double): Boolean {
        val s = ee?.let { P3Plan.eeSpot(it) } ?: return false
        val dx = x - s.x; val dz = z - s.z
        return dx * dx + dz * dz <= 1.5 * 1.5 && Math.floor(y * 100 + 1e-6).toLong() == Math.round(s.y * 100)
    }

    private fun finish() {
        val end = tasks.maxOf { it.at }
        ticks = end; endTicks = end
        Sim.note("Practice §f${mode}§7 ${secs(end)}s${Stats.best("Practice $mode", end)}§7: " +
            tasks.joinToString("§8, §7") { "${it.label} §f${secs(it.at)}" })
    }

    fun secs(ticks: Int) = String.format(Locale.ROOT, "%.2f", ticks / 20.0)
}
