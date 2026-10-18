package com.engineerclient.p3sim.clear.score

import com.engineerclient.p3sim.pack.runstate.ScoreModel

/**
 * What the team score is computed from, read from the run state at one score step. Pure data.
 */
data class ScoreInputs(
    /** `Completed Rooms` in tile units (0..36). */
    val completedTiles: Int,
    val secretsFound: Int,
    val secretsTotal: Int,
    /** Crypts counted (uncapped; the score uses at most five). */
    val crypts: Int,
    val mimic: Boolean,
    val prince: Boolean,
    val bat: Boolean,
    /** Team deaths. */
    val deaths: Int,
    /** Puzzles announced by the tab minus solved ones (an undiscovered puzzle counts as unsolved). */
    val unsolvedPuzzles: Int,
    /** Seconds since T0. */
    val elapsedSec: Double,
)

/** Each part of one score, for the dev command and the tests. [total] is what the sidebar shows. */
data class ScoreBreakdown(
    val rooms: Double, val secrets: Double, val crypts: Double, val bonus: Double, val puzzles: Double, val deaths: Double, val speed: Double,
    val constant: Double, val total: Int,
)

/**
 * The team score, as `Cleared: P% (S)` prints it (FORMAT-runstate 7). Two evaluators:
 *  - [fitted]: the pack's own fit (a rooms table by completed tiles plus a secrets, crypts, bonus, puzzle, death and speed term),
 *    exactly the `score.evaluation` formula the extractor validated (end-of-run residual: 96 percent within 3 points);
 *  - [published]: the openly known structure of the formula, used only when the run-state pack is missing or its score section is
 *    malformed. It reads about two points low and is smoother in the middle of a run than the real, convex curve.
 * Pure.
 */
object ScoreFormula {
    /** Points a puzzle left unsolved costs when the pack did not fit it (`UNMEASURED_PUZZLE_PENALTY`, the published value). */
    const val UNMEASURED_PUZZLE_PENALTY = 10.0

    /** `Secrets Found: P%` as the tab prints it, one decimal. */
    fun secretsPct(found: Int, total: Int): Double = if (total <= 0) 0.0 else Math.round(1000.0 * found / total) / 10.0

    /**
     * The death penalty: `max(0, 3d - 2)`: 1, 4, 7, 10. Fitted on the score shifts of 118, 64 and 11 steps with one, two and three deaths
     * (mean -1.4, -4.3, -7.8 against the rooms table; the pack notes -0.9, -3.8, -7.0, -10.0). The earlier `2d - 1` was one short at
     * the second death and three short at the fourth.
     */
    fun deathPenalty(deaths: Int): Int = maxOf(0, 3 * deaths - 2)

    /** `table(c)`, linear between the entries, clamped to the table. */
    fun interp(table: DoubleArray, c: Double): Double {
        if (table.isEmpty()) return 0.0
        if (c <= 0) return table[0]
        if (c >= table.size - 1) return table.last()
        val i = Math.floor(c).toInt()
        return table[i] + (table[i + 1] - table[i]) * (c - i)
    }

    /** The speed term from `[seconds, points]` pairs, piecewise linear; 0 before the first pair and flat after the last. */
    fun speed(points: List<Pair<Double, Double>>, sec: Double): Double {
        if (points.isEmpty() || sec <= points.first().first) return 0.0
        for (i in 1 until points.size) {
            val (s0, p0) = points[i - 1]; val (s1, p1) = points[i]
            if (sec <= s1) return if (s1 <= s0) p1 else p0 + (p1 - p0) * (sec - s0) / (s1 - s0)
        }
        return points.last().second
    }

    /** The pack's fitted score. The pack stores the puzzle penalty NEGATIVE; when it is null [UNMEASURED_PUZZLE_PENALTY] is subtracted. */
    fun fitted(m: ScoreModel, i: ScoreInputs): ScoreBreakdown {
        val pct = secretsPct(i.secretsFound, i.secretsTotal)
        val secrets = m.secretWeight * Math.floor(m.maxSecretPoints * pct / 100.0)
        val rooms = interp(m.roomsTable, i.completedTiles.toDouble())
        val crypts = m.cryptWeight * minOf(m.cryptCap, i.crypts)
        val bonus = m.mimicWeight * (if (i.mimic) 1 else 0) + m.princeWeight * (if (i.prince) 1 else 0) + m.batWeight * (if (i.bat) 1 else 0)
        val puzzles = (m.puzzlePenaltyPerUnsolved ?: -UNMEASURED_PUZZLE_PENALTY) * i.unsolvedPuzzles
        val deaths = -deathPenalty(i.deaths).toDouble()
        val speed = speed(m.speedPointsSec, i.elapsedSec)
        val sum = rooms + secrets + crypts + bonus + puzzles + deaths + speed + m.constant
        return ScoreBreakdown(rooms, secrets, crypts, bonus, puzzles, deaths, speed, m.constant, maxOf(0, Math.floor(sum).toInt()))
    }

    /** The openly known formula: `floor(40 sec%) + floor(60 c/36) + clamp(20 + floor(80 c/36) - 10 unsolved - deathPenalty, 20, 100) + min(5, crypts) + 2 mimic + prince + bat + 100`. */
    fun published(i: ScoreInputs): ScoreBreakdown {
        val pct = secretsPct(i.secretsFound, i.secretsTotal)
        val secrets = Math.floor(40.0 * pct / 100.0)
        val c = i.completedTiles
        val speedPart = Math.floor(60.0 * c / 36.0)
        val skill = (20 + Math.floor(80.0 * c / 36.0) - UNMEASURED_PUZZLE_PENALTY * i.unsolvedPuzzles - deathPenalty(i.deaths)).coerceIn(20.0, 100.0)
        val crypts = minOf(5, i.crypts).toDouble()
        val bonus = (if (i.mimic) 2.0 else 0.0) + (if (i.prince) 1.0 else 0.0) + (if (i.bat) 1.0 else 0.0)
        val total = secrets + speedPart + skill + crypts + bonus + 100.0
        return ScoreBreakdown(speedPart + skill + 100.0, secrets, crypts, bonus, 0.0, 0.0, 0.0, 0.0, maxOf(0, Math.floor(total).toInt()))
    }

    /** The score for [i]: the pack's fit when it has a usable rooms table, else the published formula. */
    fun evaluate(m: ScoreModel?, i: ScoreInputs): ScoreBreakdown =
        if (m != null && m.roomsTable.size >= 2) fitted(m, i) else published(i)
}

/** The letter on the end screen. Hypixel: S+ from 300 (measured: every recorded S+ is 300 or more, every S 273 to 299). */
object Grades {
    /** `S+ >= 300`, `S >= 270`, `A >= 230`, `B >= 160`, `C >= 100`, else `D`. The S+/S edge is measured; A to C are the published thresholds (UNMEASURED). */
    fun letter(score: Int): String = when {
        score >= 300 -> "S+"
        score >= 270 -> "S"
        score >= 230 -> "A"
        score >= 160 -> "B"
        score >= 100 -> "C"
        else -> "D"
    }
}

/**
 * When the score is recomputed: at `T0 + origin + period * k` for k >= 1 (the pack: origin 20, period 200, first value at T0 + 220).
 * A stall that skips a step gives one recompute, not a burst; the grid carries on from the step after.
 */
class ScoreSchedule(private val origin: Int, private val period: Int) {
    private var nextK = 1

    /** The run clock of the next step. */
    val nextT: Int get() = origin + period * nextK

    /** Whether a step is due at run clock [t]; if so it is taken (the grid moves past [t]). */
    fun poll(t: Int): Boolean {
        if (t < nextT) return false
        nextK = Math.floorDiv(t - origin, period) + 1
        return true
    }

    /** Puts the grid back after a savepoint restore: the next step is the first one after [t]. */
    fun resetTo(t: Int) { nextK = if (t < origin + period) 1 else Math.floorDiv(t - origin, period) + 1 }
}
