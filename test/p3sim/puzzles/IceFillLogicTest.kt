package com.engineerclient.p3sim.puzzles

import com.engineerclient.p3sim.clear.puzzles.IceFillLogic
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IceFillLogicTest {
    // ---- Ice Fill -----------------------------------------------------------------------------------------------

    @Test fun iceFillPicksTheFirstPatternWhoseMarkersFit() {
        // Floor 0, pattern 1 has markers (14,70,7) air and (14,70,9) solid; patterns before it must fail.
        val solid = setOf(Triple(14, 70, 9))
        val chosen = IceFillLogic.patterns { x, y, z -> Triple(x, y, z) !in solid }
        assertEquals(1, chosen[0])
    }

    @Test fun iceFillNoMatchIsMinusOne() {
        assertEquals(listOf(-1, -1, -1), IceFillLogic.patterns { _, _, _ -> true })
    }

    @Test fun iceFillFloorsFillInOrderAndTheThirdSolves() {
        val floors = listOf(
            setOf(IceFillLogic.Cell(1, 70, 1), IceFillLogic.Cell(2, 70, 1)),
            setOf(IceFillLogic.Cell(1, 71, 1)),
            setOf(IceFillLogic.Cell(5, 72, 5)),
        )
        val l = IceFillLogic(floors)
        assertEquals(IceFillLogic.Outcome.NOTHING, l.step(1, 71, 1).outcome, "floor 1 is not active yet")
        assertEquals(IceFillLogic.Outcome.COVERED, l.step(1, 70, 1).outcome)
        assertFalse(l.floorDone(0))
        val last = l.step(2, 70, 1)
        assertEquals(IceFillLogic.Outcome.COVERED, last.outcome); assertTrue(last.floorDone)
        assertEquals(1, l.activeFloor)
        assertEquals(IceFillLogic.Outcome.COVERED, l.step(1, 71, 1).outcome)
        assertEquals(IceFillLogic.Outcome.COVERED, l.step(5, 72, 5).outcome)
        assertTrue(l.solved)
        l.reset(); assertFalse(l.solved); assertEquals(0, l.activeFloor)
    }

    private fun square() = IceFillLogic(listOf(
        setOf(IceFillLogic.Cell(0, 70, 0), IceFillLogic.Cell(1, 70, 0), IceFillLogic.Cell(0, 70, 1), IceFillLogic.Cell(1, 70, 1)),
        setOf(IceFillLogic.Cell(9, 71, 9)), emptySet(),
    ))

    @Test fun steppingOnACoveredCellIsTheWrongBlock() {
        val l = square()
        assertEquals(IceFillLogic.Outcome.COVERED, l.step(0, 70, 0).outcome)
        assertEquals(IceFillLogic.Outcome.COVERED, l.step(1, 70, 0).outcome)
        assertEquals(IceFillLogic.Outcome.WRONG_BLOCK, l.step(0, 70, 0).outcome, "back onto the first cell")
        assertEquals(IceFillLogic.Outcome.WRONG_BLOCK, l.step(1, 70, 0).outcome, "and onto the one just stepped on")
        assertEquals(2, l.coveredCount(0), "a wrong step covers nothing by itself")
    }

    @Test fun aDiagonalOrALongerJumpIsNotAllowed() {
        val l = square()
        assertEquals(IceFillLogic.Outcome.COVERED, l.step(0, 70, 0).outcome)
        assertEquals(IceFillLogic.Outcome.DIAGONAL, l.step(1, 70, 1).outcome)
        // The far corner of a longer patch is a jump too.
        val line = IceFillLogic(listOf((0..4).map { IceFillLogic.Cell(it, 70, 0) }.toSet(), emptySet(), emptySet()))
        line.step(0, 70, 0)
        assertEquals(IceFillLogic.Outcome.DIAGONAL, line.step(2, 70, 0).outcome)
        assertEquals(IceFillLogic.Outcome.COVERED, line.step(1, 70, 0).outcome)
    }

    @Test fun theFirstStepOnAFloorCanBeAnyCellAndACollapseStartsTheFloorOver() {
        val l = square()
        assertEquals(IceFillLogic.Outcome.COVERED, l.step(1, 70, 1).outcome, "any cell may start")
        assertEquals(IceFillLogic.Outcome.DIAGONAL, l.step(0, 70, 0).outcome)
        l.collapse()
        assertEquals(0, l.coveredCount(0), "the whole floor is forgotten")
        assertNull(l.last)
        assertEquals(IceFillLogic.Outcome.COVERED, l.step(0, 70, 0).outcome, "after a collapse any cell may start again")
        assertEquals(IceFillLogic.Outcome.COVERED, l.step(0, 70, 1).outcome)
        assertEquals(IceFillLogic.Outcome.COVERED, l.step(1, 70, 1).outcome)
        val done = l.step(1, 70, 0)
        assertTrue(done.floorDone)
        assertEquals(1, l.activeFloor)
        assertNull(l.last, "the next floor has no previous cell")
    }

    @Test fun aCollapseLeavesDoneFloorsAlone() {
        val l = IceFillLogic(listOf(setOf(IceFillLogic.Cell(0, 70, 0)), setOf(IceFillLogic.Cell(0, 71, 0), IceFillLogic.Cell(1, 71, 0)), emptySet()))
        l.step(0, 70, 0)
        l.step(0, 71, 0)
        l.collapse()
        assertTrue(l.floorDone(0))
        assertEquals(1, l.activeFloor)
        assertEquals(0, l.coveredCount(1))
    }

    @Test fun aFloorWithNothingToCoverIsSkipped() {
        val l = IceFillLogic(listOf(setOf(IceFillLogic.Cell(1, 70, 1)), emptySet(), emptySet()))
        assertEquals(IceFillLogic.Outcome.COVERED, l.step(1, 70, 1).outcome)
        assertTrue(l.solved)
        assertFalse(IceFillLogic(listOf(emptySet(), emptySet(), emptySet())).solved)
    }

    @Test fun scanIceTakesTheCellsUnderObstaclesToo() {
        val ice = IceFillLogic.scanIce(0, 5, isIce = { x, y, _ -> y == 69 && x < 3 })
        val patch = IceFillLogic.scan(0, 5, isIce = { x, y, _ -> y == 69 && x < 3 }, isAir = { x, _, z -> !(x == 1 && z == 1) })
        assertEquals(15, ice.size)
        assertEquals(14, patch.size)
        assertTrue(IceFillLogic.Cell(1, 70, 1) in ice && IceFillLogic.Cell(1, 70, 1) !in patch)
    }

    @Test fun scanFindsIceWithAirOnTop() {
        val cells = IceFillLogic.scan(0, 5, isIce = { x, y, _ -> y == 69 && x < 3 }, isAir = { x, _, z -> !(x == 1 && z == 1) })
        assertEquals(14, cells.size, "3 columns of 5, minus the blocked cell")
        assertTrue(cells.all { it.y == 70 })
    }
}
