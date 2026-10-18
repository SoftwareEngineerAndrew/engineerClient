package com.engineerclient.p3sim.clear.puzzles.hard

import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.rooms.Placement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FramesTest {
    private fun place(rot: Rot) = Placement.of("1x1", 31, 31, rot, listOf(Tile(2, 3)))

    @Test fun aCanonicalStepGoesTheSameWayAsItsCells() {
        for (rot in Rot.entries) {
            val p = place(rot)
            val a = Frames.toWorld(p, 10.5, 12.5)
            val east = Frames.toWorld(p, 11.5, 12.5)
            val south = Frames.toWorld(p, 10.5, 13.5)
            val w0 = p.toWorld(10, 0, 12); val we = p.toWorld(11, 0, 12); val ws = p.toWorld(10, 0, 13)
            assertEquals((we.x - w0.x).toDouble(), east.first - a.first, 1e-9, "$rot east x")
            assertEquals((we.z - w0.z).toDouble(), east.second - a.second, 1e-9, "$rot east z")
            assertEquals((ws.x - w0.x).toDouble(), south.first - a.first, 1e-9, "$rot south x")
            assertEquals((ws.z - w0.z).toDouble(), south.second - a.second, 1e-9, "$rot south z")
        }
    }

    @Test fun worldAndCanonicalPositionsRoundTrip() {
        for (rot in Rot.entries) {
            val p = place(rot)
            for ((cx, cz) in listOf(8.5 to 9.5, 12.3 to 8.9, 20.75 to 24.2)) {
                val (wx, wz) = Frames.toWorld(p, cx, cz)
                val back = assertNotNull(Frames.toCanonical(p, wx, wz))
                assertEquals(cx, back.first, 1e-9, "$rot x")
                assertEquals(cz, back.second, 1e-9, "$rot z")
            }
        }
    }

    @Test fun aSouthFacingLookTurnsWithTheRoom() {
        // West placement: canonical south (+z) lands on world -x, a look to the west is yaw 90.
        val p = place(Rot.WEST)
        val a = Frames.toWorld(p, 10.5, 12.5); val s = Frames.toWorld(p, 10.5, 13.5)
        assertEquals(-1.0, s.first - a.first, 1e-9)
        assertEquals(90f, Frames.yawToWorld(p, 0f))
    }
}
