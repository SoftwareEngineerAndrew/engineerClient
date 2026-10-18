package com.engineerclient.p3sim.puzzles

import com.engineerclient.p3sim.clear.puzzles.TttArt
import com.engineerclient.p3sim.clear.puzzles.TttLogic
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TttLogicTest {
    /** Plays one game: the room opens on [opening], the party then plays [moves] (a move on a taken cell ends the line of play); the room answers each. */
    private fun playOut(opening: Int, moves: List<Int>, seed: Int): TttLogic {
        val g = TttLogic(Random(seed))
        g.roomOpens(opening)
        for (m in moves) {
            if (g.result != TttLogic.Result.PLAYING || !g.press(m)) break
            if (g.result == TttLogic.Result.PLAYING) g.roomReplies()
        }
        return g
    }

    /** Every sequence of party moves after one opening, against room choices drawn by [seed]: tallies the results. */
    private fun explore(opening: Int, moves: List<Int>, seed: Int, seen: IntArray) {
        val g = playOut(opening, moves, seed)
        if (g.result != TttLogic.Result.PLAYING) { seen[g.result.ordinal]++; return }
        for (cell in 0..8) {
            if (g.mark(cell) != TttLogic.Mark.EMPTY) continue
            explore(opening, moves + cell, seed, seen)
        }
    }

    @Test fun theRoomNeverLosesWhateverItOpensWith() {
        val seen = IntArray(TttLogic.Result.entries.size)
        for (opening in 0..8) for (seed in 1..3) explore(opening, emptyList(), seed, seen)
        assertEquals(0, seen[TttLogic.Result.WON.ordinal])
        assertTrue(seen[TttLogic.Result.TIE.ordinal] > 0 && seen[TttLogic.Result.LOST.ordinal] > 0)
    }

    /** The party's side of a perfect game: every cell that keeps the tie. */
    private fun partyBoard(g: TttLogic) = Array(9) { g.mark(it) }

    @Test fun theRoomMovesFirstAndTheNinthMarkIsTheRoomsOnATie() {
        val g = TttLogic(Random(3))
        assertFalse(g.press(4), "the party cannot press before the room has opened")
        val first = g.roomOpens()
        assertTrue(first in 0..8)
        assertEquals(TttLogic.Mark.X, g.mark(first))
        assertEquals(-1, g.roomOpens(), "the room opens once")
        var last = first
        while (g.result == TttLogic.Result.PLAYING) {
            val cell = TttLogic.bestPartyMoves(partyBoard(g)).first()
            assertTrue(g.press(cell))
            if (g.result == TttLogic.Result.PLAYING) last = g.roomReplies()
        }
        assertEquals(TttLogic.Result.TIE, g.result)
        assertEquals(9, g.marks)
        assertEquals(5, (0..8).count { g.mark(it) == TttLogic.Mark.X }, "the room has five marks, the party four")
        assertEquals(TttLogic.Mark.X, g.mark(last), "the ninth mark is the room's")
    }

    @Test fun aPressOutOfTurnOrOnATakenCellIsRefused() {
        val g = TttLogic(Random(5))
        g.roomOpens(4)
        assertFalse(g.press(4), "the room's cell")
        assertTrue(g.press(0))
        assertFalse(g.press(1), "the room has not answered yet")
        val reply = g.roomReplies()
        assertTrue(reply >= 0)
        assertFalse(g.press(reply), "the room's answer")
        assertFalse(g.press(0), "the party's own mark")
        assertEquals(-1, g.roomReplies(), "the room answers once per press")
    }

    @Test fun theRoomsBestMovesCompleteALineWhenItCan() {
        val b = Array(9) { TttLogic.Mark.EMPTY }
        b[0] = TttLogic.Mark.X; b[1] = TttLogic.Mark.X; b[4] = TttLogic.Mark.O; b[8] = TttLogic.Mark.O
        assertEquals(listOf(2), TttLogic.bestMoves(b), "the only winning move")
        val c = Array(9) { TttLogic.Mark.EMPTY }
        c[4] = TttLogic.Mark.X
        c[0] = TttLogic.Mark.O
        assertTrue(TttLogic.bestMoves(c).size > 1, "a tie is reached by several cells")
    }

    @Test fun theOpeningFollowsTheMeasuredOdds() {
        val g = TttLogic(Random(11))
        val n = 20000
        val count = IntArray(9)
        repeat(n) { g.reset(); count[g.roomOpens()]++ }
        assertTrue(Math.abs(count[4].toDouble() / n - 0.39) < 0.015, "centre ${count[4].toDouble() / n}")
        for (corner in intArrayOf(0, 2, 6, 8)) assertTrue(Math.abs(count[corner].toDouble() / n - 0.1485) < 0.012, "corner $corner ${count[corner].toDouble() / n}")
        val edges = intArrayOf(1, 3, 5, 7).sumOf { count[it] }.toDouble() / n
        assertTrue(edges < 0.04, "edges $edges")
    }

    @Test fun theRoomPicksAmongEquallyGoodCellsAtRandom() {
        val firstReplies = (1..40).map { seed ->
            val g = TttLogic(Random(seed)); g.roomOpens(4); g.press(0); g.roomReplies()
        }.toSet()
        assertTrue(firstReplies.size > 1, "different seeds give different answers: $firstReplies")
        assertTrue(TttLogic.THINK_TICKS == 60 && TttLogic.OPENING_TICKS == 24)
    }

    @Test fun theMapsAreTheRoomsXAndThePartysO() {
        val x = TttArt.pixels(true); val o = TttArt.pixels(false)
        assertEquals(128 * 128, x.size)
        assertEquals(TttArt.BORDER, x[0]); assertEquals(TttArt.BORDER, x[8 * 128 + 64], "row 8 is border")
        assertEquals(TttArt.BACKGROUND, x[9 * 128 + 8], "the inner area starts at x 8, y 9")
        assertEquals(TttArt.STROKE, x[64 * 128 + 64], "the X crosses in the middle")
        assertEquals(TttArt.BACKGROUND, o[64 * 128 + 64], "the O is hollow in the middle")
        assertEquals(TttArt.STROKE, o[64 * 128 + 20], "and has a ring")
        assertEquals(setOf(TttArt.BORDER, TttArt.BACKGROUND, TttArt.STROKE), x.toSet())
        assertEquals(30876, TttArt.X_MAP_ID); assertEquals(30877, TttArt.O_MAP_ID)
    }
}
