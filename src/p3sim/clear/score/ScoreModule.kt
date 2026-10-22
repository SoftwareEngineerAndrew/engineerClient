package com.engineerclient.p3sim.clear.score

import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsModule
import com.engineerclient.p3sim.run.MobKilled
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunEvent
import com.engineerclient.p3sim.run.RunStage
import com.engineerclient.p3sim.run.ScoreUpdated
import com.engineerclient.p3sim.run.api.Audience
import com.engineerclient.p3sim.run.api.EndScreen
import com.engineerclient.p3sim.run.api.ScoreService
import com.engineerclient.p3sim.run.api.Services
import com.google.gson.JsonObject

/**
 * The team score (`Cleared: P% (S)` in the sidebar) and the end screen.
 *
 * The score is recomputed every 200 ticks from the run state, as Hypixel does (first value 220 ticks after Mort's first line, later
 * ones 200 apart; a stall that skips a step gives one recompute) and published to [com.engineerclient.p3sim.run.ScoreState]; the
 * sidebar and the end screen show that last published value, there is no extra recompute at the end. The formula is the run-state
 * pack's fit ([ScoreFormula.fitted]); without the pack, the published formula ([ScoreFormula.published]). The score keeps being
 * recomputed through the blood room and the boss, which is when the last room tiles and the Watcher's room count.
 *
 * The end screen is Hypixel's: the Team Score line with its letter, "Defeated ... in <time>", the EXTRA STATS header (Odin's
 * run-end trigger) and the stats block. Server thread only.
 */
object ScoreModule : AdsModule, ScoreService {
    override val id = "score"
    override val owner = "secrets"

    /** `periodTicks` and the clock origin when the pack has none: the measured 200 and 20. */
    const val DEFAULT_PERIOD = 200
    const val DEFAULT_ORIGIN = 20

    private var schedule = ScoreSchedule(DEFAULT_ORIGIN, DEFAULT_PERIOD)
    /** Enemies each human killed this run (bot kills are not theirs). */
    private val killed = HashMap<java.util.UUID, Int>()

    override fun onRunStart(run: Run) {
        val m = run.packs.runstate?.score
        schedule = ScoreSchedule(m?.clockOriginTicksAfterT0 ?: DEFAULT_ORIGIN, (m?.periodTicks ?: DEFAULT_PERIOD).coerceAtLeast(20))
        killed.clear()
        run.state.score.score = 0
        run.state.score.clearedPct = 0
        run.state.score.lastUpdateT = Int.MIN_VALUE
        run.state.score.history.clear()
    }

    override fun onTick(run: Run) {
        // From Mort's first line to the end of the run; the countdown has no score.
        if (run.stage == RunStage.COUNTDOWN || run.stage == RunStage.END) return
        if (schedule.poll(run.t)) recompute(run)
    }

    override fun onEvent(run: Run, event: RunEvent) {
        if (event is MobKilled) {
            val by = event.by ?: return
            if (run.state.players[by]?.isBot == false) killed.merge(by, 1, Int::plus)
        }
    }

    /** The numbers the score is made from, read off the run state now. */
    fun inputs(run: Run): ScoreInputs {
        val s = run.state
        val announced = maxOf(s.puzzles.size, run.plan.puzzleNames.size)
        val solved = s.puzzles.count { it.status == PuzzleStatus.SOLVED }
        return ScoreInputs(
            completedTiles = s.counters.completed, secretsFound = s.secrets.teamFound, secretsTotal = s.secrets.teamMax,
            crypts = s.secrets.crypts, mimic = s.secrets.mimicKilled, prince = s.secrets.princeKilled, bat = s.secrets.batKilled,
            deaths = s.deaths.teamDeaths, unsolvedPuzzles = (announced - solved).coerceAtLeast(0), elapsedSec = run.t / 20.0,
        )
    }

    fun breakdown(run: Run): ScoreBreakdown = ScoreFormula.evaluate(run.packs.runstate?.score, inputs(run))

    override fun recompute(run: Run): Int {
        val s = run.state.score
        val score = breakdown(run).total
        s.score = score
        s.clearedPct = run.state.counters.clearedPct
        s.lastUpdateT = run.t
        s.history += run.t to score
        run.bus.post(ScoreUpdated(run.t, score, s.clearedPct))
        return score
    }

    override fun endScreen(run: Run): EndScreen {
        val s = run.state
        return EndScreen(s.score.score, Grades.letter(s.score.score), s.score.clearedPct, s.secrets.teamFound, s.secrets.teamMax, s.secrets.crypts, s.deaths.teamDeaths, run.t)
    }

    override fun showEndScreen(run: Run) {
        val e = endScreen(run)
        val humans = run.state.players.values.filter { !it.isBot }
        if (humans.isEmpty()) { for (line in EndScreenText.lines(e)) Services.channels.chat(line); return }
        for (h in humans) for (line in EndScreenText.lines(e, me = playerNumbers(run, h))) Services.channels.chat(line, Audience.Only(h.uuid))
    }

    /** Model ranges for the numbers the sim cannot count yet (UNMEASURED; the recorded screens read Catacombs XP 46.5k to 50k, damage 345M to 1.46B, healing 0.8k to 4k and 50k to 150k for a Healer, Bits in 72 percent of runs). */
    const val CATA_XP_MIN = 46_500.0
    const val CATA_XP_MAX = 50_000.0
    const val DAMAGE_MIN = 600_000_000L
    const val DAMAGE_MAX = 900_000_000L

    /** One human's end-screen numbers: their kills, their own secrets and deaths, and the modelled experience, damage and healing. */
    fun playerNumbers(run: Run, rec: com.engineerclient.p3sim.run.PlayerRecord): EndScreenPlayer {
        val r = run.subRandom("endscreen-" + rec.uuid)
        val cls = EndScreenText.CLASSES.firstOrNull { it.equals(rec.dungeonClass, ignoreCase = true) } ?: "Mage"
        val damage = (rec.extras["damage.total"] as? Number)?.toLong() ?: r.nextLong(DAMAGE_MIN, DAMAGE_MAX)
        val healing = if (cls == "Healer") r.nextLong(50_000, 150_000) else r.nextLong(800, 4_000)
        val bits = if (r.nextDouble() < 0.72) r.nextInt(45, 80) else 0
        return EndScreenPlayer(cls, r.nextDouble(CATA_XP_MIN, CATA_XP_MAX), bits, damage, healing, killed[rec.uuid] ?: 0, rec.secretsFound)
    }

    override fun snapshot(run: Run): JsonObject? = JsonObject().also { o -> killed.forEach { (k, v) -> o.addProperty(k.toString(), v) } }

    override fun restore(run: Run, data: JsonObject) {
        killed.clear()
        data.entrySet().forEach { (k, v) -> runCatching { java.util.UUID.fromString(k) }.getOrNull()?.let { u -> killed[u] = v.asInt } }
        schedule.resetTo(run.t)
    }

    // ---- /ads dev -----------------------------------------------------------------------------------------------------------------

    override fun devCommands(): List<AdsCommand> = listOf(
        AdsCommand("scorenow", "/ads dev scorenow", "Prints the score parts for the run as it stands, the last published score and when the next step is due.", server = { ctx ->
            val run = ctx.run
            if (run == null) ctx.reply("No run is on.") else {
                val b = breakdown(run); val i = inputs(run)
                ctx.reply("Now: ${b.total} (${Grades.letter(b.total)}); published ${run.state.score.score}, next step at run tick ${schedule.nextT} (now ${run.t}).")
                ctx.reply("  rooms ${"%.1f".format(b.rooms)} (${i.completedTiles}/36 tiles), secrets ${"%.1f".format(b.secrets)} (${i.secretsFound}/${i.secretsTotal}), crypts ${"%.1f".format(b.crypts)} (${i.crypts}), bonus ${"%.1f".format(b.bonus)}, puzzles ${"%.1f".format(b.puzzles)} (${i.unsolvedPuzzles} unsolved), deaths ${"%.1f".format(b.deaths)} (${i.deaths}), speed ${"%.1f".format(b.speed)}")
            }
        }),
        AdsCommand("scorecalc", "/ads dev scorecalc <tiles> <found> <total> [crypts] [deaths] [unsolved] [mimic] [prince] [bat]", "Works out the score for numbers you type (no run needed): the letter and each part.", requiresSim = false, client = { ctx ->
            val n = ctx.args.map { it.toIntOrNull() }
            if (n.size < 3 || n.take(3).any { it == null }) ctx.reply("Say at least: scorecalc <completed tiles 0-36> <secrets found> <secrets total>.")
            else {
                fun flag(i: Int) = (n.getOrNull(i) ?: 0) != 0
                val inp = ScoreInputs(n[0]!!, n[1]!!, n[2]!!, n.getOrNull(3) ?: 0, flag(6), flag(7), flag(8), n.getOrNull(4) ?: 0, n.getOrNull(5) ?: 0, 0.0)
                val model = Packs.current?.runstate?.score
                val b = ScoreFormula.evaluate(model, inp)
                ctx.reply("${b.total} (${Grades.letter(b.total)}) using ${if (model != null && model.roomsTable.size >= 2) "the pack's fit" else "the published formula (no run-state pack loaded: run /ads dev packs first)"}: rooms ${"%.1f".format(b.rooms)}, secrets ${"%.1f".format(b.secrets)}, crypts ${"%.1f".format(b.crypts)}, bonus ${"%.1f".format(b.bonus)}, puzzles ${"%.1f".format(b.puzzles)}, deaths ${"%.1f".format(b.deaths)}.")
            }
        }),
        AdsCommand("scoreend", "/ads dev scoreend", "Prints the end screen for the run as it stands.", server = { ctx ->
            val run = ctx.run
            if (run == null) ctx.reply("No run is on.") else showEndScreen(run)
        }),
    )
}
