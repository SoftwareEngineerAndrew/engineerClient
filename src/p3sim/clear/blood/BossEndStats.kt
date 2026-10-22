package com.engineerclient.p3sim.clear.blood

import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.Runs
import com.engineerclient.p3sim.run.api.EndScreen
import com.engineerclient.p3sim.run.api.Services

/**
 * The numbers the boss's end-of-run chat shows when the boss was reached through a full run: the REAL team score from the score
 * module, the time from Mort's first line to Necron's death, and the counts the run kept. In the boss practice (no run) there is
 * nothing here and the end chat keeps its typical-run numbers. Pure apart from reading the score service; the formatting is tested.
 */
data class BossEndNumbers(
    val score: Int,
    /** `S+`, `S`, `A`, `B`, `C`, `D`. */
    val letter: String,
    /** Run ticks from T0 to the end. */
    val ticks: Int,
    val secretsFound: Int,
    val secretsTotal: Int,
    val deaths: Int,
    val crypts: Int,
) {
    /** `Team Score: 305 (S+)` in the end chat's `§` layout. */
    fun scoreLine(): String = "Team Score: §r§a$score §r§f(§r${gradeColour(letter)}$letter§r§f)"

    /** `Defeated Maxor, Storm, Goldor, and Necron in 05m 12s`, or null if no time is known. */
    fun defeatedLine(): String? =
        if (ticks > 0) "§r§c☠ §r§eDefeated §r§cMaxor, Storm, Goldor, and Necron §r§ein §r§a" + timeText(ticks) else null

    companion object {
        /** The colour of the grade letter, the same table the end screen uses. */
        fun gradeColour(letter: String): String = com.engineerclient.p3sim.clear.score.EndScreenText.letterColour(letter)

        /** `05m 12s` as the end chat prints it. */
        fun timeText(ticks: Int): String = "%02dm %02ds".format(ticks / 1200, ticks / 20 % 60)

        fun from(e: EndScreen) = BossEndNumbers(e.score, e.letter, e.ticks, e.secretsFound, e.secretsTotal, e.deaths, e.crypts)

        /** The numbers of [run] as the score module has them now (no extra recompute), or null with no run. */
        fun of(run: Run?): BossEndNumbers? = run?.let { from(Services.score.endScreen(it)) }

        /** [of] the current run. The boss's end chat asks this. */
        fun ofCurrentRun(): BossEndNumbers? = of(Runs.current)
    }
}
