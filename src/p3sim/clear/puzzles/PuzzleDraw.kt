package com.engineerclient.p3sim.clear.puzzles

import kotlin.random.Random

/**
 * Which puzzles a dungeon gets, when the sim has to decide it (a generated map; a replayed layout already carries its puzzle
 * rooms, so v1 only uses this for the dev command and tests). Measured on 579 full maps: 2, 3, 4 or 5 puzzle rooms with
 * 28.7, 31.3, 25.2 and 14.9 per cent; the rooms are different designs (Higher and Lower Blaze are two designs, never both in
 * one map), so Higher Or Lower is about twice as likely as the others. Pure; the random source is a parameter.
 */
object PuzzleDraw {
    /** The measured counts out of 579 maps (the run-state pack carries the same under `puzzles.countOdds`). */
    val DEFAULT_COUNTS: Map<Int, Int> = mapOf(2 to 166, 3 to 181, 4 to 146, 5 to 86)

    /** One room design a puzzle can be built in. */
    class Design(val code: String, val room: String)

    /** The ten F7 designs (11 with the two Blaze rooms; Bomb Defuse never occurs). */
    val DEFAULT_DESIGNS: List<Design> = listOf(
        Design(PuzzleKinds.BOULDER, "Boulder"), Design(PuzzleKinds.BEAMS, "Creeper Beams"), Design(PuzzleKinds.TTT, "Tic Tac Toe"),
        Design(PuzzleKinds.BLAZE, "Higher Blaze"), Design(PuzzleKinds.BLAZE, "Lower Blaze"), Design(PuzzleKinds.WATER_BOARD, "Water Board"),
        Design(PuzzleKinds.WEIRDOS, "Three Weirdos"), Design(PuzzleKinds.TP_MAZE, "Teleport Maze"), Design(PuzzleKinds.QUIZ, "Quiz"),
        Design(PuzzleKinds.ICE_FILL, "Ice Fill"), Design(PuzzleKinds.ICE_PATH, "Ice Path"),
    )

    /** Draws the number of puzzle rooms by the weights in [counts]. */
    fun drawCount(rnd: Random, counts: Map<Int, Int> = DEFAULT_COUNTS): Int {
        val usable = counts.filter { it.value > 0 }.toSortedMap()
        if (usable.isEmpty()) return DEFAULT_COUNTS.keys.first()
        var roll = rnd.nextInt(usable.values.sum())
        for ((n, w) in usable) { if (roll < w) return n; roll -= w }
        return usable.lastKey()
    }

    /** Draws [n] distinct designs; the two Blaze rooms are never both taken. */
    fun drawDesigns(rnd: Random, n: Int, designs: List<Design> = DEFAULT_DESIGNS): List<Design> {
        val picked = ArrayList<Design>()
        for (d in designs.shuffled(rnd)) {
            if (picked.size >= n) break
            if (picked.any { it.code == d.code }) continue
            picked += d
        }
        return picked
    }

    /** The whole draw: a count, then that many designs. */
    fun draw(rnd: Random, counts: Map<Int, Int> = DEFAULT_COUNTS, designs: List<Design> = DEFAULT_DESIGNS): List<Design> =
        drawDesigns(rnd, drawCount(rnd, counts), designs)
}
