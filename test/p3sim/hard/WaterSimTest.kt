package com.engineerclient.p3sim.clear.puzzles.hard

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The 1.8 water rules on small synthetic boxes, one block thick (no recorded data). */
class WaterSimTest {
    private fun run(sim: WaterSim, ticks: Int) { repeat(ticks) { sim.tick() } }

    @Test fun aSourceOnAFloorSpreadsSevenBlocksOneStepEveryFiveTicks() {
        val sim = WaterSim(0, 0, 0, 20, 3, 1)
        for (x in 0 until 20) sim.setSolid(x, 0, 0, true)
        sim.placeSource(10, 1, 0)
        assertEquals(0, sim.water(10, 1, 0))
        run(sim, 5)
        assertEquals(1, sim.water(11, 1, 0), "first block of spread after 5 ticks")
        assertEquals(1, sim.water(9, 1, 0))
        run(sim, 5 * 6)
        for (k in 1..7) assertEquals(k, sim.water(10 + k, 1, 0), "level at $k blocks")
        assertEquals(-1, sim.water(18, 1, 0), "no water beyond 7 blocks")
    }

    @Test fun waterFallsAColumnAtOneBlockEveryFiveTicksAndSpreadsOnTheFloor() {
        val sim = WaterSim(0, 0, 0, 3, 8, 1)
        for (x in 0..2) sim.setSolid(x, 0, 0, true)
        for (y in 2..7) { sim.setSolid(0, y, 0, true); sim.setSolid(2, y, 0, true) }
        sim.placeSource(1, 7, 0)
        run(sim, 5)
        assertEquals(8, sim.water(1, 6, 0))
        run(sim, 5)
        assertEquals(8, sim.water(1, 5, 0))
        run(sim, 5 * 5)
        assertEquals(8, sim.water(1, 1, 0), "it reached the floor")
        run(sim, 5)
        assertEquals(1, sim.water(0, 1, 0), "falling water spreads out on the floor as level 1")
    }

    @Test fun waterThatFallsAfterSpreadingKeepsItsSpreadLevelPlusEight() {
        val sim = WaterSim(0, 0, 0, 8, 5, 1)
        for (x in 0..3) sim.setSolid(x, 2, 0, true)
        sim.setSolid(5, 0, 0, true)
        sim.placeSource(0, 3, 0)
        run(sim, 5 * 12)
        assertEquals(4, sim.water(4, 3, 0), "the spread reaches the edge of the floor at level 4")
        assertEquals(12, sim.water(4, 2, 0), "and falls from there as 4 + 8")
    }

    @Test fun aClosedGateHoldsWaterBackAndOpeningItLetsItThrough() {
        val sim = WaterSim(0, 0, 0, 12, 3, 1)
        for (x in 0 until 12) sim.setSolid(x, 0, 0, true)
        sim.setSolid(4, 1, 0, true)
        sim.placeSource(1, 1, 0)
        run(sim, 100)
        assertEquals(-1, sim.water(5, 1, 0), "the gate stops the flow")
        assertTrue(sim.water(3, 1, 0) > 0)
        sim.setSolid(4, 1, 0, false)
        run(sim, 10)
        assertTrue(sim.water(4, 1, 0) >= 0, "water enters the freed cell")
        assertTrue(sim.water(5, 1, 0) >= 0, "and carries on")
    }

    @Test fun waterPrefersTheSideThatLeadsToADrop() {
        val sim = WaterSim(0, 0, 0, 9, 3, 1)
        for (x in 0 until 9) if (x != 6) sim.setSolid(x, 0, 0, true)
        sim.placeSource(4, 1, 0)
        run(sim, 5)
        // The hole is two blocks to the east: water goes east only, not west.
        assertEquals(1, sim.water(5, 1, 0))
        assertEquals(-1, sim.water(3, 1, 0))
    }

    @Test fun snapshotAndRestoreContinueTheSameFlow() {
        fun build(): WaterSim {
            val s = WaterSim(0, 0, 0, 12, 3, 1)
            for (x in 0 until 12) s.setSolid(x, 0, 0, true)
            return s
        }
        val a = build(); a.placeSource(1, 1, 0); run(a, 17)
        val (cells, pend) = a.snapshot()
        val b = build(); b.restore(cells, pend)
        run(a, 40); run(b, 40)
        for (x in 0 until 12) assertEquals(a.water(x, 1, 0), b.water(x, 1, 0), "column $x")
    }
}
