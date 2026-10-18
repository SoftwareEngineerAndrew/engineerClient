package com.engineerclient.p3sim.run.api

import com.engineerclient.p3sim.run.BonusKind
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.SecretKind
import java.util.UUID

/** Implemented by [com.engineerclient.p3sim.clear.secrets.SecretsModule]. Server thread only. */
interface SecretService {
    /**
     * Credits a secret to the team (and [by]): the team and room counts go up, the action bar, tab and Odin's tracking
     * follow. The doors module turns the room GREEN when the room's count reaches its maximum. Returns false if it was
     * already counted.
     */
    fun found(run: Run, room: Int, kind: SecretKind, by: UUID?, x: Int, y: Int, z: Int): Boolean = false

    /** A teammate cleared [room]: counts every secret still missing for [by] (the room's own sites first, the rest as chest secrets). Returns how many were counted. */
    fun creditRoom(run: Run, room: Int, by: UUID?): Int = 0

    /** A crypt tomb at (x, y, z) was destroyed. */
    fun cryptDestroyed(run: Run, room: Int, by: UUID?, x: Int, y: Int, z: Int) {}

    /** A bonus mob (Mimic, Prince, bat) died and its point counts. */
    fun bonus(run: Run, kind: BonusKind, by: UUID?) {}

    /** The secret maximum the bars print for this room. */
    fun roomMax(run: Run, room: Int): Int = run.state.room(room)?.secretsMax ?: 0

    /**
     * Item hooks. The damage work item edits `SimItems.kt` (it owns that file) and calls these from the Dungeonbreaker and
     * Superboom handlers; the secrets module owns the rules behind them (crypt tombs and room boundaries are unbreakable for the
     * Dungeonbreaker, superboom destroys crypt and secret walls, 20 charges). The defaults change nothing.
     *
     * A reason (plain English, shown to the player) when the Dungeonbreaker may NOT break the block at (x, y, z), else null.
     */
    fun breakerRefusal(run: Run, x: Int, y: Int, z: Int): String? = null

    /** A Dungeonbreaker broke the block at (x, y, z) (after [breakerRefusal] said yes). */
    fun onBreakerBreak(run: Run, by: UUID?, x: Int, y: Int, z: Int) {}

    /** A Superboom went off at (x, y, z): destroys the walls it may (crypts count, secret walls open). Returns how many blocks it destroyed. */
    fun onSuperboom(run: Run, by: UUID?, x: Int, y: Int, z: Int): Int = 0
}

/** What the end screen shows (the class of Hypixel's, minus the loot). */
data class EndScreen(
    val score: Int,
    /** `S+`, `S`, `A`, `B`, `C`, `D`. */
    val letter: String,
    val clearedPct: Int,
    val secretsFound: Int,
    val secretsTotal: Int,
    val crypts: Int,
    val deaths: Int,
    /** Run ticks from T0 to the boss's death (or the stop). */
    val ticks: Int,
)

/** Implemented by [com.engineerclient.p3sim.clear.score.ScoreModule]. Server thread only. */
interface ScoreService {
    /** Recomputes the team score from the run state now (the module also does it every 200 ticks from T0+200). Posts `ScoreUpdated`. */
    fun recompute(run: Run): Int = run.state.score.score

    /** The end screen for the run as it stands. The score is the last published one (no extra recompute at the end). */
    fun endScreen(run: Run): EndScreen = EndScreen(run.state.score.score, "D", run.state.counters.clearedPct, run.state.secrets.teamFound, run.state.secrets.teamMax,
        run.state.secrets.crypts, run.state.deaths.teamDeaths, run.t)

    /** Puts the end screen up (chat lines and title) for everyone, as Hypixel shows it. The engine calls this when the run enters END. */
    fun showEndScreen(run: Run) {}
}
