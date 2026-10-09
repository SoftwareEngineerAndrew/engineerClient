package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.client.Minecraft
import java.io.File
import java.util.Locale

/**
 * Practice mode (the menu's Practice tab): a short drill, timed, restarted as often as you like (the
 * Restart or Practice Restart keybind, a left click with the Infinileap, or once it's
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

    /**
     * A set practice: [name], its [section], where you start, the jobs left to you (the rest of the section done; a
     * later section's device done early counts too), checkpoints to reach in order, and its start timer (null: the slider's).
     */
    class Setup(val name: String, val section: Int, val start: Spots.Spot, val jobs: List<String>, val checkpoints: List<Spots.Spot> = emptyList(), val delay: Double? = null)

    /** The preset or custom practice running (null: a role's section practice). */
    @Volatile private var setup: Setup? = null
    private val custom get() = setup != null
    private val checkpointsNow get() = setup?.checkpoints.orEmpty()

    private fun p(x: Double, y: Double, z: Double, yaw: Float, pitch: Float) = Spots.Spot("Start", x, y, z, yaw, pitch)

    /** The presets, by section (the menu's columns). */
    fun presets(): List<Setup> = listOf(
        Setup("4 3", 1, p(108.20, 120.00, 94.00, -90.41f, 2.79f), listOf("S1 T4", "S1 T3"), delay = 3.0),
        Setup("2 1", 1, p(108.20, 120.00, 94.00, -90.41f, 2.79f), listOf("S1 T2", "S1 T1"), delay = 3.0),
        Setup("ee2", 1, p(108.20, 120.00, 94.00, -90.41f, 2.79f), listOf("S1 west lever", "S1 east lever", "gate 1", "S2 Lights"),
            listOf(Spots.Spot("EE2", 60.53, 132.00, 138.98, 0f)), delay = 3.0),
        Setup("2 -> 1", 2, p(59.47, 120.00, 125.98, -179.50f, 23.01f), listOf("S2 T1"), listOf(Spots.Spot("Checkpoint", 69.70, 109.00, 122.43, 0f)), delay = 0.0),
        Setup("2 -> 3", 2, p(59.47, 120.00, 125.98, -179.50f, 23.01f), listOf("S2 T3"), delay = 0.0),
        // S3 (BL: both levers; no start timer of their own: the slider's).
        Setup("1 bl", 3, p(2.00, 109.00, 102.00, -175.24f, 5.83f), listOf("S3 T1", "S3 west lever", "S3 east lever")),
        Setup("4 bl", 3, p(2.00, 109.00, 102.00, -175.24f, 5.83f), listOf("S3 T4", "S3 west lever", "S3 east lever", "gate 3")),
        Setup("2 dev", 3, p(2.00, 109.00, 102.00, -175.24f, 5.83f), listOf("S3 T2", "S3 Arrows")),
        Setup("3 dev", 3, p(2.00, 109.00, 102.00, -175.24f, 5.83f), listOf("S3 T3", "S3 Arrows")),
        Setup("h -> 1", 3, p(18.70, 121.50, 91.30, 1.15f, -7.73f), listOf("S3 T1")),
        Setup("h -> 2", 3, p(18.70, 121.50, 91.30, 1.15f, -7.73f), listOf("S3 T2")),
        Setup("h -> i3 4", 3, p(18.70, 121.50, 91.30, 1.15f, -7.73f), listOf("S3 Arrows", "S3 T4")),
        Setup("h -> 3 bl", 3, p(18.70, 121.50, 91.30, 1.15f, -7.73f), listOf("S3 T3", "S3 west lever", "S3 east lever")),
        Setup("h -> i3 4 bl", 3, p(18.70, 121.50, 91.30, 1.15f, -7.73f), listOf("S3 Arrows", "S3 T4", "S3 west lever", "S3 east lever")),
        Setup("4 -> 4 BL", 3, p(0.97, 109.00, 77.89, 96.99f, 19.23f), listOf("S3 T4", "S3 west lever", "S3 east lever")),
        Setup("4 -> BL", 3, p(0.97, 109.00, 77.89, 96.99f, 19.23f), listOf("S3 west lever", "S3 east lever")),
    )

    /** Starts preset [name] (again). */
    fun startPreset(name: String) { presets().firstOrNull { it.name == name }?.let { launch(it) } }

    private var ee: P3Plan.EarlyEnter? = null

    /** Starts section [s]'s practice (again): your role's part of it. */
    fun start(s: Int) { setup = null; launch("S$s", s) }

    /** Starts the custom practice (again). */
    fun startCustom() {
        val s = customSection
        val at = customStart
        if (at == null || s !in 1..4) { Sim.note("Set the custom practice's §fStart Position§7 first (Practice tab)."); return }
        launch(Setup("Custom", s, at, P3Plan.jobsIn(s).filter { it in customJobs }, checkpoints.toList()))
    }

    private fun launch(set: Setup) { setup = set; launch(set.name, set.section) }

    private fun launch(m: String, s: Int) {
        mode = m; section = s
        tasks = emptyList(); ticks = 0; endTicks = -1
        Fight.start(listOf(Fight.Start.S1, Fight.Start.S2, Fight.Start.S3, Fight.Start.S4)[s - 1], practice = true)
    }

    /** The same practice again (the Restart keybinds, the Infinileap, a left click once done). */
    fun restart() {
        val set = setup
        when {
            set?.name == "Custom" -> startCustom()
            set != null -> launch(set)
            section in 1..4 -> start(section)
        }
    }

    /** A left click with anything, 0.25 s after it's done, starts it again. Client thread. */
    fun clickRestarts() = active && endTicks >= 0 && System.currentTimeMillis() - endMs >= 250

    /** Out of practice mode (any other start, Stop). */
    fun exit() { mode = null; section = 0; setup = null; tasks = emptyList(); endTicks = -1 }

    /** Your jobs that are section [s]'s in practice: done in it, as the plan times them, in that order. */
    fun jobs(s: Int): List<String> {
        val times = P3Plan.plan().times
        return P3Plan.allJobs().filter { P3Plan.isMine(it) && (times[it]?.first ?: sectionOf(it)) == s }
            .sortedBy { times[it]?.second ?: 99.0 }
    }

    /** What's left to do in this practice's section (the rest is done at the start). */
    fun practiceJobs(): List<String> = setup?.jobs ?: jobs(section)

    private fun sectionOf(job: String) = job.removePrefix("gate ").toIntOrNull() ?: job.removePrefix("S").substringBefore(' ').toIntOrNull() ?: 0

    /** Where the practice puts you: the custom start; your spawn (S1), the early enter into it, else its start. */
    fun startSpot(): Spots.Spot = setup?.start ?: when (val s = section) {
        1 -> P3Plan.customSpot("spawn") ?: Spots.p3Start(1)
        else -> P3Plan.earlyEnters.firstOrNull { it.into == s }?.let { P3Plan.eeSpot(it) } ?: Spots.p3Start(s)
    }

    /** The start timer (the menu's slider): seconds you stand placed before anything is up and the clock runs. */
    @Volatile var startDelay = 0.0
        private set
    val holdTicks get() = Math.round((setup?.delay ?: startDelay) * 20).toInt()
    fun setStartDelay(v: Double) { startDelay = v.coerceIn(0.0, 5.0); save() }

    /** The phase's tick its practice really started (after the start timer). */
    private var startT = 0

    /** A start timer of [n] ticks begins. */
    fun hold(n: Int) { tasks = emptyList(); ticks = -n; endTicks = -1 }
    fun holdTick(t: Int, n: Int) { ticks = t - n }

    /** GoldorPhase's start, in practice (at phase tick [t]): the tasks. */
    fun begin(s: Int, t: Int) {
        startT = t
        val list = practiceJobs().map { Task(it, label(it)) }.toMutableList()
        ee = null
        if (custom) checkpointsNow.forEachIndexed { i, c -> list += Task("cp $i", if (c.name.startsWith("Checkpoint") || c.name == "set") "Checkpoint ${i + 1}" else c.name) }
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
        val now = phase.t - startT
        ticks = now
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
                    checkpointsNow.getOrNull(task.id.removePrefix("cp ").toInt())?.let { onSpot(it, 1.0, p.x, p.y, p.z) } == true
                else -> phase.stations.firstOrNull { it.id == task.id }?.done == true
            }
            if (done) task.at = now
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

    /** Where a leap onto [c] lands in practice: where that class normally is in the section in progress (no bots there). */
    fun leapSpot(c: DungeonClass): Spots.Spot? {
        val s = ((Fight.phase as? GoldorPhase)?.section ?: section).coerceIn(1, 4)
        return leapSpots()[s - 1][c]
    }

    /** Each class's usual spot in each section (S1-S4), set in game with /pos (2026-10-09). */
    private fun leapSpots(): List<Map<DungeonClass, Spots.Spot>> = listOf(
        // S1
        mapOf(
            DungeonClass.ARCHER to Spots.Spot("Archer", 94.04, 112.00, 99.26, 60.01f, -3.13f),
            DungeonClass.BERSERK to Spots.Spot("Berserk", 93.56, 112.00, 93.29, 103.97f, 15.53f),
            DungeonClass.MAGE to Spots.Spot("Mage", 106.77, 122.00, 111.70, 8.30f, -19.24f),
            DungeonClass.TANK to Spots.Spot("Tank", 110.70, 113.00, 75.34, -178.85f, 22.77f),
            DungeonClass.HEALER to Spots.Spot("Healer", 108.30, 120.00, 94.01, -90.08f, -0.99f),
        ),
        // S2
        mapOf(
            DungeonClass.ARCHER to Spots.Spot("Archer", 39.12, 109.00, 139.87, -6.83f, 33.13f),
            DungeonClass.BERSERK to Spots.Spot("Berserk", 40.78, 109.00, 123.30, -176.88f, 26.63f),
            DungeonClass.MAGE to Spots.Spot("Mage", 59.46, 120.00, 125.78, -179.76f, 22.20f),
            DungeonClass.TANK to Spots.Spot("Tank", 68.46, 109.00, 125.21, -179.43f, 18.33f),
            DungeonClass.HEALER to Spots.Spot("Healer", 24.95, 131.06, 138.57, 90.40f, 18.91f),
        ),
        // S3
        mapOf(
            DungeonClass.ARCHER to Spots.Spot("Archer", 1.01, 109.00, 77.81, 95.00f, 19.81f),
            DungeonClass.BERSERK to Spots.Spot("Berserk", 15.67, 123.00, 93.65, -90.00f, 19.40f),
            DungeonClass.MAGE to Spots.Spot("Mage", 18.70, 121.50, 91.30, 0.58f, -4.61f),
            DungeonClass.TANK to Spots.Spot("Tank", 1.35, 109.00, 112.32, 87.20f, 18.41f),
            DungeonClass.HEALER to Spots.Spot("Healer", 0.87, 119.00, 93.41, 89.42f, 19.15f),
        ),
        // S4
        mapOf(
            DungeonClass.ARCHER to Spots.Spot("Archer", 44.45, 121.00, 31.81, -177.77f, 30.98f),
            DungeonClass.BERSERK to Spots.Spot("Berserk", 67.23, 109.00, 33.09, -178.02f, 18.65f),
            DungeonClass.MAGE to Spots.Spot("Mage", 54.53, 115.06, 50.51, -179.57f, 2.38f),
            DungeonClass.TANK to Spots.Spot("Tank", 41.49, 109.00, 33.04, 179.43f, 18.16f),
            DungeonClass.HEALER to Spots.Spot("Healer", 72.46, 115.00, 45.79, 0.18f, 27.20f),
        ),
    )

    // ------------------------------------------------------------------ the custom practice

    /** Where it starts (null: not set), its section, the jobs left to you there, and the checkpoints. */
    @Volatile var customStart: Spots.Spot? = null
        private set
    @Volatile var customSection = 0
        private set
    val customJobs = LinkedHashSet<String>()
    val checkpoints = ArrayList<Spots.Spot>()

    /** The section (1-4) [x], [y], [z] is in, else the nearest one (the middle, the core, a gap between two). */
    fun sectionAt(x: Double, y: Double, z: Double): Int {
        val v = net.minecraft.world.phys.Vec3(x, y, z)
        return GoldorPhase.dtZone(v).takeIf { it in 1..4 }
            ?: GoldorPhase.DT_ZONES.minBy { (_, box) -> box.distanceToSqr(v) }.first
    }

    /** Your role's jobs in section [s], for a custom practice there. */
    fun roleJobs(s: Int) = P3Plan.jobsIn(s).filter { P3Plan.isMine(it) }

    /** The jobs the custom practice's row shows for section [s]: the ones picked there, else your role's. */
    fun customJobsIn(s: Int): Set<String> = if (s == customSection) customJobs else roleJobs(s).toSet()

    /**
     * The start where you stand; its section is where it is (or the nearest). Jobs already picked for that
     * section stay; another section's start takes your role's jobs there. Returns the section.
     */
    fun setStart(spot: Spots.Spot): Int {
        val s = sectionAt(spot.x, spot.y, spot.z)
        if (s != customSection) { customSection = s; customJobs.clear(); customJobs += roleJobs(s) }
        customStart = spot
        save()
        return s
    }

    /** Picks or drops [job] of section [s] (moving the custom practice's jobs to [s], from your role's, first). */
    fun toggleJob(job: String, s: Int) {
        if (s != customSection) { customSection = s; customJobs.clear(); customJobs += roleJobs(s); if (customStart != null && sectionAt(customStart!!.x, customStart!!.y, customStart!!.z) != s) customStart = null }
        if (!customJobs.remove(job)) customJobs += job
        save()
    }

    /** No custom practice: start, jobs and checkpoints gone. */
    fun clearCustom() { customStart = null; customSection = 0; customJobs.clear(); checkpoints.clear(); save() }

    fun addCheckpoint(spot: Spots.Spot) { checkpoints += spot; save() }
    fun removeCheckpoint() { if (checkpoints.isNotEmpty()) checkpoints.removeAt(checkpoints.size - 1); save() }

    private class Saved(val start: List<Double>? = null, val section: Int? = null, val jobs: List<String>? = null, val checkpoints: List<List<Double>>? = null, val startDelay: Double? = null)

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
            startDelay = s.startDelay ?: 0.0
            checkpoints.clear(); s.checkpoints?.forEachIndexed { i, v -> if (v.size >= 5) checkpoints += spot("Checkpoint ${i + 1}", v) }
        }
    }

    private fun save() {
        EngineerClient.safely("p3sim practice save") {
            file.parentFile.mkdirs()
            file.writeText(gson.toJson(Saved(customStart?.let { list(it) }, customSection, customJobs.toList(), checkpoints.map { list(it) }, startDelay)))
        }
    }
}
