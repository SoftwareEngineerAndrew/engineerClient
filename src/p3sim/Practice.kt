package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import java.io.File
import java.util.Locale

/**
 * Practice mode (the menu's Practice tab): a short drill, timed, restarted as often as you like (the
 * Restart or Practice Restart keybind, a left click with the Infinileap or in its menu, or once it's
 * done, a left click with anything).
 *
 * Section practice (s1-s4): P3 at that section with everything but your role's part of it done (its
 * stations and gate; no bots), you on your early enter for it (S1: your spawn; S4: its start). Your
 * jobs there (as the plan times them, so an i4 done in S1 is S1's) and, if it's yours, the early enter
 * into the next section are the tasks, each timed from the start; the last one's time is the
 * practice's, large on screen (the Practice Time HUD) and in chat with your best.
 *
 * Custom practice: your own start position (its section: where it is), the jobs of that section you
 * pick (your role's to begin with) and checkpoints to reach in order (within 1 block across, the same
 * height), all timed the same way. Saved in config/engineerclient/p3sim-practice.json.
 *
 * Server thread, except what the HUDs and the menu read ([mode], [tasks], [ticks], [endTicks], the custom setup).
 */
object Practice {
    /** One thing to do: a station id, "gate N", an early enter ("ee" + its key) or a checkpoint ("cp" + its index). [at]: ticks from the start, -1 till done. */
    class Task(val id: String, val label: String) { @Volatile var at = -1 }

    /** The practice you're in ("S2", "Custom"), null: not in practice mode. */
    @Volatile var mode: String? = null
        private set
    /** The section practised (1-4). */
    @Volatile var section = 0
        private set
    @Volatile var tasks: List<Task> = emptyList()
        private set
    /** Ticks since the start, and the final time (-1 until every task is done). */
    @Volatile var ticks = 0
        private set
    @Volatile var endTicks = -1
        private set
    /** When it was done (System ms): a left click with anything restarts it 0.25 s after. */
    @Volatile var endMs = 0L
        private set

    val active get() = mode != null
    private val custom get() = mode == "Custom"

    private var ee: P3Plan.EarlyEnter? = null

    /** Starts section [s]'s practice (again). */
    fun start(s: Int) = launch("S$s", s)

    /** Starts the custom practice (again). */
    fun startCustom() {
        val s = customSection
        if (customStart == null || s !in 1..4) { Sim.note("Set the custom practice's §fStart Position§7 first (Practice tab)."); return }
        launch("Custom", s)
    }

    private fun launch(m: String, s: Int) {
        mode = m; section = s
        tasks = emptyList(); ticks = 0; endTicks = -1
        Fight.start(listOf(Fight.Start.S1, Fight.Start.S2, Fight.Start.S3, Fight.Start.S4)[s - 1], practice = true)
    }

    /** The same practice again (the Restart keybinds, the Infinileap, a left click once done). */
    fun restart() { if (custom) startCustom() else if (section in 1..4) start(section) }

    /** A left click with anything, 0.25 s after it's done, starts it again. Client thread. */
    fun clickRestarts() = active && endTicks >= 0 && System.currentTimeMillis() - endMs >= 250

    /** Out of practice mode (any other start, Stop). */
    fun exit() { mode = null; section = 0; tasks = emptyList(); endTicks = -1 }

    /** Your jobs that are section [s]'s in practice: done in it, as the plan times them, in that order. */
    fun jobs(s: Int): List<String> {
        val times = P3Plan.plan().times
        return P3Plan.allJobs().filter { P3Plan.isMine(it) && (times[it]?.first ?: sectionOf(it)) == s }
            .sortedBy { times[it]?.second ?: 99.0 }
    }

    /** What's left to do in this practice's section (the rest is done at the start). */
    fun practiceJobs(): List<String> = if (custom) P3Plan.jobsIn(section).filter { it in customJobs } else jobs(section)

    private fun sectionOf(job: String) = job.removePrefix("gate ").toIntOrNull() ?: job.removePrefix("S").substringBefore(' ').toIntOrNull() ?: 0

    /** Where the practice puts you: the custom start; your spawn (S1), the early enter into it, else its start. */
    fun startSpot(): Spots.Spot = customStart.takeIf { custom } ?: when (val s = section) {
        1 -> P3Plan.customSpot("spawn") ?: Spots.p3Start(1)
        else -> P3Plan.earlyEnters.firstOrNull { it.into == s }?.let { P3Plan.eeSpot(it) } ?: Spots.p3Start(s)
    }

    /** GoldorPhase's start, in practice: the tasks. */
    fun begin(s: Int) {
        val list = practiceJobs().map { Task(it, label(it)) }.toMutableList()
        ee = null
        if (custom) checkpoints.forEachIndexed { i, _ -> list += Task("cp $i", "Checkpoint ${i + 1}") }
        else {
            ee = P3Plan.ee(if (s == 4) 5 else s + 1)?.takeIf { it.byYou }
            ee?.let { list += Task("ee ${it.key}", it.label) }
        }
        tasks = list; ticks = 0; endTicks = -1
        if (list.isEmpty()) Sim.note("Practice §f$mode§7: nothing to do in it.")
        else Sim.note("Practice §f$mode§7: ${list.joinToString("§8, §7") { it.label }}.")
    }

    private fun label(job: String) = when {
        job.startsWith("gate ") -> "Gate"
        else -> job.substringAfter(' ').replaceFirstChar { it.uppercase() }
    }

    fun tick(phase: GoldorPhase) {
        if (endTicks >= 0 || tasks.isEmpty()) return
        ticks = phase.t
        val p = Sim.player
        for ((i, task) in tasks.withIndex()) {
            if (task.at >= 0) continue
            val done = when {
                task.id.startsWith("gate ") -> phase.gateIsDown(task.id.removePrefix("gate ").toInt())
                // The early enter: once everything else is done.
                task.id.startsWith("ee ") -> p != null && tasks.all { it === task || it.at >= 0 } &&
                    ee?.let { onSpot(P3Plan.eeSpot(it), 1.5, p.x, p.y, p.z) } == true
                // Checkpoints: in order.
                task.id.startsWith("cp ") -> p != null && tasks.subList(0, i).none { it.id.startsWith("cp ") && it.at < 0 } &&
                    checkpoints.getOrNull(task.id.removePrefix("cp ").toInt())?.let { onSpot(it, 1.0, p.x, p.y, p.z) } == true
                else -> phase.stations.firstOrNull { it.id == task.id }?.done == true
            }
            if (done) task.at = phase.t
        }
        if (tasks.all { it.at >= 0 }) finish()
    }

    /** On [s]: within [r] blocks of it across, and on its exact height (to the hundredth). */
    private fun onSpot(s: Spots.Spot, r: Double, x: Double, y: Double, z: Double): Boolean {
        val dx = x - s.x; val dz = z - s.z
        return dx * dx + dz * dz <= r * r && Math.floor(y * 100 + 1e-6).toLong() == Math.round(s.y * 100)
    }

    private fun finish() {
        val end = tasks.maxOf { it.at }
        ticks = end; endTicks = end; endMs = System.currentTimeMillis()
        Sim.note("Practice §f${mode}§7 ${secs(end)}s${Stats.best("Practice $mode", end)}§7: " +
            tasks.joinToString("§8, §7") { "${it.label} §f${secs(it.at)}" })
    }

    fun secs(ticks: Int) = String.format(Locale.ROOT, "%.2f", ticks / 20.0)

    // ------------------------------------------------------------------ the custom practice

    /** Where it starts (null: not set), its section, the jobs left to you there, and the checkpoints. */
    @Volatile var customStart: Spots.Spot? = null
        private set
    @Volatile var customSection = 0
        private set
    val customJobs = LinkedHashSet<String>()
    val checkpoints = ArrayList<Spots.Spot>()

    /** The start where you stand: its section is where it is, its jobs your role's there. False: not in a section. */
    fun setStart(spot: Spots.Spot): Boolean {
        val s = GoldorPhase.dtZone(net.minecraft.world.phys.Vec3(spot.x, spot.y, spot.z))
        if (s !in 1..4) return false
        customStart = spot; customSection = s
        customJobs.clear(); customJobs += P3Plan.jobsIn(s).filter { P3Plan.isMine(it) }
        save()
        return true
    }

    /** No custom practice: start, jobs and checkpoints gone. */
    fun clearCustom() { customStart = null; customSection = 0; customJobs.clear(); checkpoints.clear(); save() }

    fun toggleJob(job: String) { if (!customJobs.remove(job)) customJobs += job; save() }
    fun addCheckpoint(spot: Spots.Spot) { checkpoints += spot; save() }
    fun removeCheckpoint() { if (checkpoints.isNotEmpty()) checkpoints.removeAt(checkpoints.size - 1); save() }

    private class Saved(val start: List<Double>? = null, val section: Int? = null, val jobs: List<String>? = null, val checkpoints: List<List<Double>>? = null)

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file get() = File(Minecraft.getInstance().gameDirectory, "config/engineerclient/p3sim-practice.json")
    private fun list(p: Spots.Spot) = listOf(p.x, p.y, p.z, p.yaw.toDouble(), p.pitch.toDouble())
    private fun spot(name: String, v: List<Double>) = Spots.Spot(name, v[0], v[1], v[2], v[3].toFloat(), v[4].toFloat())

    fun load() {
        EngineerClient.safely("p3sim practice load") {
            val f = file
            if (!f.exists()) return@safely
            val s = gson.fromJson(f.readText(), Saved::class.java) ?: return@safely
            customStart = s.start?.takeIf { it.size >= 5 }?.let { spot("Start", it) }
            customSection = s.section ?: 0
            customJobs.clear(); s.jobs?.let { customJobs += it }
            checkpoints.clear(); s.checkpoints?.forEachIndexed { i, v -> if (v.size >= 5) checkpoints += spot("Checkpoint ${i + 1}", v) }
        }
    }

    private fun save() {
        EngineerClient.safely("p3sim practice save") {
            file.parentFile.mkdirs()
            file.writeText(gson.toJson(Saved(customStart?.let { list(it) }, customSection, customJobs.toList(), checkpoints.map { list(it) })))
        }
    }
}
