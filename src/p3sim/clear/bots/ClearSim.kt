package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.pack.runstate.Dist
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.plugin.PartyStrategy
import com.engineerclient.p3sim.run.plugin.Seat
import com.engineerclient.p3sim.run.plugin.BloodAssignment
import com.engineerclient.p3sim.run.plugin.StrategyContext
import kotlin.random.Random

/**
 * What the headless gate uses when it is given no strategy: it parks the last bot seat in the blood room, so the room schedule is checked
 * with one seat busy there. It names no class and is not offered in any list; a run never uses it.
 */
private object GateParking : PartyStrategy {
    override val id = "gate-parking"
    override val label = "Gate parking"
    override fun bloodAssignment(ctx: StrategyContext): BloodAssignment =
        ctx.seats.lastOrNull { !it.isHuman }?.let { BloodAssignment(it.index) } ?: BloodAssignment.NOBODY
}

/**
 * The headless gate for the ClearPlan: builds many plans (a different seed and layout each) with the whole party as bots, reads the
 * milestones each implies, and compares their percentiles with the recorded ones in the run-state pack. No Minecraft, no chat: it returns a
 * [Report] that the unit tests assert on and that `/ads dev botsim` prints for the real pack.
 *
 * What is compared (all in run ticks since T0, lag removed, the five percents 5, 25, 50, 75, 95):
 *  - blood door open, and the Watcher's "proven" line and the first boss line (the latter two come from the pack's milestone tables, so they
 *    check the milestone chain rather than the bots),
 *  - tiles completed (white or green) at Maxor, and the share of rooms green at Maxor (what the bots' room schedule produces).
 */
object ClearSim {
    /** The percents every comparison reports (the pack's QDist percents). */
    val PCT = doubleArrayOf(5.0, 25.0, 50.0, 75.0, 95.0)

    /** One simulated plan, reduced to the numbers that are compared. */
    class Sample(val bloodOpen: Double, val watcherDone: Double, val maxor: Double, val tilesDone: Double, val greenFrac: Double)

    /** One compared quantity. */
    class Row(val name: String, val recorded: DoubleArray?, val simulated: DoubleArray, val tolerance: Tolerance, val pass: Boolean, val note: String)

    /** How far a simulated percentile may be from the recorded one: a fraction of it, but at least [abs] (in the metric's unit). */
    class Tolerance(val rel50: Double, val rel25_75: Double, val rel5_95: Double, val abs: Double) {
        fun ok(rec: Double, sim: Double, index: Int): Boolean {
            val rel = when (index) { 2 -> rel50; 1, 3 -> rel25_75; else -> rel5_95 }
            return kotlin.math.abs(sim - rec) <= maxOf(rel * kotlin.math.abs(rec), abs)
        }
    }

    class Report(val runs: Int, val layouts: Int, val rows: List<Row>, val problems: List<String>) {
        val passed: Boolean get() = rows.all { it.pass || it.recorded == null }

        /** Plain lines for the chat or a log: one per metric with both rows of percentiles. */
        fun lines(): List<String> = buildList {
            add("Bot plan check: $runs plans over $layouts layouts. Percents 5, 25, 50, 75, 95.")
            for (r in rows) {
                val rec = r.recorded?.joinToString(", ") { fmt(it) } ?: "no recorded numbers"
                add("${if (r.pass || r.recorded == null) "OK  " else "OFF "}${r.name}: simulated ${r.simulated.joinToString(", ") { fmt(it) }}; recorded $rec${if (r.note.isNotEmpty()) " (${r.note})" else ""}")
            }
            for (p in problems) add(p)
            add(if (passed) "All within tolerance." else "Some numbers are outside tolerance.")
        }

        private fun fmt(v: Double) = if (kotlin.math.abs(v) < 2.0 && v != 0.0) "%.2f".format(v) else "%.0f".format(v)
    }

    fun seatsFor(classes: List<String>): List<Seat> = classes.mapIndexed { i, c -> Seat(i, c, isHuman = false) }

    /** The five classes in their usual order; the gate puts one bot on each. */
    val FIVE = listOf("HEALER", "MAGE", "BERSERK", "ARCHER", "TANK")

    /** Builds one plan for a dungeon with a whole party of bots. */
    fun planFor(dungeon: DungeonPlan, tables: BotTables, seed: Long, strategy: PartyStrategy, rawTables: com.engineerclient.p3sim.pack.runstate.RunStatePack? = null, classes: List<String> = FIVE, workScale: Double = BotTuning.ROOM_WORK_SCALE, areas: RoomAreas? = null): ClearPlan {
        val seats = seatsFor(classes)
        val rnd = Random(seed)
        val assignment = BotStrategies.assignment(strategy, BotStrategyContext(dungeon, seats, Random(rnd.nextLong()), rawTables, 0))
        return ClearPlanner(PlannerInput(dungeon, tables, seats, rnd, assignment, workScale = workScale, areas = areas)).build()
    }

    /** The numbers a plan implies at Maxor. */
    fun sample(dungeon: DungeonPlan, plan: ClearPlan, tables: BotTables, r: Random): Sample {
        val ms = plan.milestones
        val maxor = ms.maxorT
        var tiles = 0
        var green = 0
        val bloodGreen = r.nextDouble() < tables.bloodGreenFrac()
        for (room in dungeon.rooms) {
            when (room.type) {
                RoomType.ENTRANCE -> { green++ }   // the entrance is green from the start and does not count for tiles
                RoomType.FAIRY -> { green++; tiles += room.tileCount }
                RoomType.BLOOD -> if (bloodGreen) { green++; tiles += room.tileCount }
                else -> {
                    val rp = plan.rooms[room.index] ?: continue
                    val white = rp.whiteT ?: rp.greenT
                    if (white != null && white <= maxor) tiles += room.tileCount
                    val g = rp.greenT ?: if (room.secretsMax == 0) rp.whiteT else null
                    if (g != null && g <= maxor) green++
                }
            }
        }
        return Sample(ms.bloodOpenT.toDouble(), ms.watcherDoneT.toDouble(), maxor.toDouble(), tiles.toDouble(), green.toDouble() / dungeon.rooms.size.coerceAtLeast(1))
    }

    /**
     * Runs [runs] plans, cycling through [dungeons] with a fresh seed each, with the five classes as bots and [strategy] deciding the blood
     * assignment, and compares the percentiles with the recorded ones in [tables].
     */
    fun gate(dungeons: List<DungeonPlan>, tables: BotTables, runs: Int = 500, seed: Long = 7L, strategy: PartyStrategy = GateParking, workScale: Double = BotTuning.ROOM_WORK_SCALE, areas: RoomAreas? = null): Report {
        require(dungeons.isNotEmpty()) { "the gate needs at least one dungeon" }
        val samples = ArrayList<Sample>(runs)
        val problems = ArrayList<String>()
        val r = Random(seed)
        for (i in 0 until runs) {
            val d = dungeons[i % dungeons.size]
            val rawSeed = r.nextLong()
            try {
                val plan = planFor(d, tables, rawSeed, strategy, null, FIVE, workScale, areas)
                samples += sample(d, plan, tables, Random(rawSeed xor 0x5DEECE66DL))
            } catch (e: Exception) {
                if (problems.size < 3) problems += "A plan failed for ${d.layoutId}: ${e.message}"
            }
        }
        val rows = ArrayList<Row>()
        fun row(name: String, rec: Dist?, get: (Sample) -> Double, tol: Tolerance, note: String = "") {
            val sim = pctOf(samples.map(get).toDoubleArray())
            val recorded = rec?.let { d -> DoubleArray(PCT.size) { d.at(PCT[it]) } }
            val pass = recorded == null || sim.indices.all { tol.ok(recorded[it], sim[it], it) }
            rows += Row(name, recorded, sim, tol, pass, note)
        }
        val ticks = Tolerance(0.12, 0.2, 0.35, 15.0)
        row("blood door open", tables.recordedBloodOpen(), { it.bloodOpen }, ticks)
        row("Watcher done", tables.recordedWatcherDone(), { it.watcherDone }, ticks, "from the pack's milestone tables")
        row("Maxor entry", tables.recordedMaxorEntry(), { it.maxor }, ticks, "from the pack's milestone tables")
        row("tiles done at Maxor", tables.recordedTilesDone(), { it.tilesDone }, Tolerance(0.04, 0.06, 0.1, 1.5), "of 35")
        row("rooms green at Maxor", tables.recordedGreenFrac(), { it.greenFrac }, Tolerance(0.08, 0.1, 0.15, 0.06), "share of all rooms")
        return Report(samples.size, dungeons.size, rows, problems)
    }

    /** The 5, 25, 50, 75, 95 percentiles of [xs] (linear between the nearest ranks). */
    fun pctOf(xs: DoubleArray): DoubleArray {
        if (xs.isEmpty()) return DoubleArray(PCT.size)
        val s = xs.sortedArray()
        return DoubleArray(PCT.size) { i ->
            val pos = PCT[i] / 100.0 * (s.size - 1)
            val lo = pos.toInt(); val hi = minOf(lo + 1, s.size - 1)
            s[lo] + (s[hi] - s[lo]) * (pos - lo)
        }
    }
}
