package com.engineerclient.p3sim.blood

import com.engineerclient.p3sim.clear.blood.HeadFlight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeadFlightTest {
    @Test fun aLongFlightMovesAtAnEvenSpeed() {
        // 16 blocks along x in 80 ticks.
        val f = HeadFlight(0.0, 72.0, 0.0, 16.0, 72.0, 0.0, 80)
        assertEquals(0.2, f.speed, 1e-9)
        assertEquals(0.0, f.x(0), 1e-9)
        assertEquals(8.0, f.x(40), 1e-9)
        assertEquals(16.0, f.x(80), 1e-9)
        assertTrue(f.arrived(80)); assertFalse(f.arrived(79))
    }

    @Test fun aShorterFasterFlightIsTheSameLine() {
        val f = HeadFlight(1.0, 70.0, 2.0, 1.0 + 7.2, 70.0 + 9.6, 2.0, 40)
        assertEquals(0.3, f.speed, 1e-9)
        assertEquals(1.0 + 7.2 / 4, f.x(10), 1e-9)
        assertEquals(70.0 + 9.6 / 4, f.y(10), 1e-9)
    }

    @Test fun thePathIsAStraightLineAndClampsOutsideTheFlight() {
        val f = HeadFlight(0.0, 0.0, 0.0, 10.0, -20.0, 5.0, 10)
        assertEquals(f.x(3) / 10.0, f.y(3) / -20.0, 1e-9)
        assertEquals(f.z(3) / 5.0, f.x(3) / 10.0, 1e-9)
        assertEquals(10.0, f.x(99), 1e-9, "after arrival it stays at the end")
        assertEquals(0.0, f.x(-4), 1e-9)
    }

    @Test fun itSpinsAndTheYawWraps() {
        val f = HeadFlight(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 200, startYaw = 350f)
        assertEquals(350f, f.yaw(0))
        assertEquals((350 + 4 * 5) % 360f, f.yaw(5))
        assertTrue(f.yaw(150) in 0f..360f)
    }

    @Test fun positionSyncsComeEveryThreeTicksAndOnArrival() {
        val f = HeadFlight(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 10)
        assertEquals(listOf(3, 6, 9, 10), (1..10).filter { f.syncDue(it) })
    }

    @Test fun aZeroLengthFlightStillTakesATick() {
        val f = HeadFlight(1.0, 2.0, 3.0, 1.0, 2.0, 3.0, 0)
        assertEquals(1, f.ticks)
        assertTrue(f.arrived(1))
    }
}
