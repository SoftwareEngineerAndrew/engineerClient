package com.engineerclient.p3sim.puzzles

import com.engineerclient.p3sim.clear.puzzles.BeamsLogic
import com.engineerclient.p3sim.clear.puzzles.BoulderLogic
import com.engineerclient.p3sim.clear.puzzles.OdinTables
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PuzzleLogicTest {
    // ---- the tables ---------------------------------------------------------------------------------------------

    @Test fun odinTablesParse() {
        assertEquals(34, OdinTables.quiz.size)
        assertEquals(13, OdinTables.beams.size)
        assertEquals(8, OdinTables.boulder.size)
        assertTrue(OdinTables.boulder.keys.all { it.length == 42 })
        assertEquals(listOf(4, 6, 5), OdinTables.iceFloors.map { it.size })
        assertTrue(OdinTables.iceFloors.flatten().all { it.easy.isNotEmpty() && it.hard.isNotEmpty() })
    }

    // ---- Creeper Beams ------------------------------------------------------------------------------------------

    private fun c(x: Int, y: Int = 70, z: Int = 5) = BeamsLogic.Cell(x, y, z)

    @Test fun beamsPairsNeedBothLanterns() {
        val lanterns = setOf(BeamsLogic.Cell(15, 74, 15), BeamsLogic.Cell(15, 84, 13), BeamsLogic.Cell(15, 78, 3))
        val pairs = BeamsLogic.pairsIn { it in lanterns }
        assertEquals(1, pairs.size)
        assertEquals(setOf(BeamsLogic.Cell(15, 74, 15), BeamsLogic.Cell(15, 84, 13)), setOf(pairs[0].first, pairs[0].second))
    }

    @Test fun theTablesRepeatedPairCountsOnce() {
        val all = BeamsLogic.pairsIn { true }
        assertEquals(11, all.size, "13 rows: one is listed twice and one is the reverse of another")
    }

    @Test fun aHitFlipsTheWholePairAtOnceAndFivePairsSolveIt() {
        val l = BeamsLogic(listOf(c(1) to c(2), c(3) to c(4)))
        assertEquals(listOf(c(1), c(2)), l.hit(c(1)).toPrismarine)
        assertEquals(1, l.solvedPairs); assertFalse(l.solved)
        assertTrue(l.hit(c(2)).toPrismarine.isEmpty(), "a finished pair is not flipped twice")
        assertEquals(listOf(c(4), c(3)), l.hit(c(4)).toPrismarine)
        assertTrue(l.solved)
    }

    @Test fun anArrowNearEitherEndCountsAndAFarOneDoesNot() {
        val l = BeamsLogic(listOf(c(10, 70, 10) to c(30, 70, 10)))
        assertEquals(c(10, 70, 10), l.lanternNear(12.0, 70.5, 10.5))
        assertEquals(c(30, 70, 10), l.lanternNear(28.0, 71.0, 10.0))
        assertEquals(null, l.lanternNear(20.0, 70.5, 10.5), "the middle of the beam is not an end")
        l.hit(c(10, 70, 10))
        assertEquals(null, l.lanternNear(12.0, 70.5, 10.5), "a finished pair no longer takes hits")
    }

    @Test fun aHitOnAStrangerDoesNothing() {
        val l = BeamsLogic(listOf(c(1) to c(2)))
        assertTrue(l.hit(c(9)).toPrismarine.isEmpty())
    }

    // ---- Boulder ------------------------------------------------------------------------------------------------

    @Test fun boulderKeyReadsTheGridInOdinsOrder() {
        // First character is (24, 24), the next (21, 24): z outer from 24 down, x inner from 24 down.
        val key = BoulderLogic.key { x, z -> !(x == 21 && z == 24) }
        assertEquals(42, key.length)
        assertEquals('0', key[0]); assertEquals('1', key[1])
        assertEquals(41, key.count { it == '0' })
    }

    @Test fun boulderClicksAnyOrderThenSolved() {
        val (key, clicks) = OdinTables.boulder.entries.first { it.value.size >= 2 }
        val l = BoulderLogic(BoulderLogic.solutionFor(key)!!)
        assertFalse(l.solved)
        assertFalse(l.click(0, 0)); assertEquals(1, l.misclicks)
        l.click(clicks[1][2], clicks[1][3])
        assertFalse(l.solved)
        l.click(clicks[0][2], clicks[0][3])
        for (c in clicks.drop(2)) l.click(c[2], c[3])
        assertTrue(l.solved)
        l.reset(); assertFalse(l.solved); assertEquals(clicks.size, l.left)
    }

    @Test fun anUnknownBoulderPatternIsNull() {
        assertNull(BoulderLogic.solutionFor("1".repeat(42)))
        assertFalse(BoulderLogic(emptyList()).solved)
    }
}
