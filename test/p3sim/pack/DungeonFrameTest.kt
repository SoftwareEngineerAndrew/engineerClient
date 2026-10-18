package com.engineerclient.p3sim.pack

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DungeonFrameTest {
    @Test fun tileEdges() {
        assertEquals(-200, DungeonFrame.tileMin(0))
        assertEquals(-185, DungeonFrame.tileCentre(0))
        assertEquals(-25, DungeonFrame.tileCentre(5))
        assertEquals(-10, DungeonFrame.DUNGEON_MAX)
        assertEquals(36, DungeonFrame.TILE_COUNT)
    }

    @Test fun doorSpotsMatchTheSpecExamples() {
        // FORMAT-layouts worked example: slot E [4,2] -> (-41,-121); slot S [5,3] -> (-25,-73)
        assertEquals(-41 to -121, DungeonFrame.doorSpot('E', 4, 2))
        assertEquals(-25 to -73, DungeonFrame.doorSpot('S', 5, 3))
    }

    @Test fun tileOfKnowsTheGapColumn() {
        assertEquals(Tile(0, 0), DungeonFrame.tileOf(-200.0, -200.0))
        assertEquals(Tile(0, 0), DungeonFrame.tileOf(-170.0, -185.0))   // last block of tile 0 is -170
        assertNull(DungeonFrame.tileOf(-169.0, -185.0))                  // the door gap column x = -169
        assertEquals(Tile(1, 0), DungeonFrame.tileOf(-168.0, -185.0))
        assertNull(DungeonFrame.tileOf(-9.0, -9.0))                      // outside the dungeon box
        assertNull(DungeonFrame.tileOf(-201.0, -100.0))
    }

    @Test fun tileIndexClips() {
        assertEquals(0, DungeonFrame.tileIndex(-500.0))
        assertEquals(5, DungeonFrame.tileIndex(100.0))
        assertEquals(0, DungeonFrame.tileIndex(-169.0))   // the gap belongs to the lower tile
        assertEquals(1, DungeonFrame.tileIndex(-168.0))
    }

    @Test fun boxAndSlots() {
        assertTrue(DungeonFrame.inDungeonBox(-200, -10)); assertFalse(DungeonFrame.inDungeonBox(-9, -10)); assertFalse(DungeonFrame.inDungeonBox(-8, -8))
        var n = 0
        for (axis in charArrayOf('E', 'S')) for (x in 0..5) for (z in 0..5) if (DungeonFrame.isSlot(axis, x, z)) n++
        assertEquals(60, n)
    }
}
