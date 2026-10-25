package com.devgineerclient.posepov

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rays stop at what hides what's behind it, and see through what doesn't. */
class RoomSightTest {
    private fun bit(b: ByteArray, i: Int) = ((b[i shr 3].toInt() shr (i and 7)) and 1) != 0

    @Test fun aSolidFloorHidesTheRoomBelow() {
        // A 64-wide, 96-tall room: a solid floor at y 47 splits it into a cellar and an upstairs.
        val g = PieceGrid(64, 64, 0, 96)
        val opaque = { _: Int, y: Int, _: Int -> y == 47 }
        val up = g.index(1, 4, 1)
        val seen = RoomSight.from(g, up, opaque, 1)
        assertTrue(bit(seen, g.index(3, 5, 3)), "the far corner upstairs")
        assertTrue(bit(seen, g.index(0, 2, 0)), "the floor's own pieces (widened)")
        assertFalse(bit(seen, g.index(0, 0, 0)), "the cellar")
        assertFalse(bit(seen, g.index(3, 0, 3)), "the cellar's far corner")
    }

    @Test fun openRoomsSeeEverything() {
        val g = PieceGrid(95, 31, 0, 64)
        val seen = RoomSight.from(g, g.index(0, 1, 0), { _, y, _ -> y == 0 }, 7)
        for (i in 0 until g.size) assertTrue(bit(seen, i), "piece $i")
    }
}
