package com.engineerclient.p3sim.layout

import com.engineerclient.p3sim.clear.layout.RoomInstance
import com.engineerclient.p3sim.clear.layout.SpawnFinder
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.rooms.RoomVolume
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SpawnFinderTest {
    @Test fun standsOnTheDoorLevelAtTheTileCentre() {
        val r = TestRooms.instance("Room_A", 2, 3)
        val s = assertNotNull(SpawnFinder.find(r, 2, 3))
        assertEquals(69.0, s.y)
        assertEquals(DungeonFrame.tileCentre(2) + 0.5, s.x)
        assertEquals(DungeonFrame.tileCentre(3) + 0.5, s.z)
    }

    @Test fun movesOffAPillarInTheMiddle() {
        val base = TestRooms.volume()
        val cells = base.cells.copyOf()
        fun idx(x: Int, y: Int, z: Int) = ((y - base.y0) * 31 + z) * 31 + x
        for (y in 69..73) cells[idx(15, y, 15)] = 2
        val vol = RoomVolume(31, 31, base.h, base.y0, base.palette, cells)
        val d = TestRooms.design("Room_P", vol, emptySet())
        val r = RoomInstance(d, vol, d.place(com.engineerclient.p3sim.pack.Rot.SOUTH, listOf(Tile(1, 1))))
        val s = assertNotNull(SpawnFinder.find(r, 1, 1))
        // Not the pillar's column, and not edge-adjacent to it either (one block of margin).
        val dx = Math.abs(s.x - 0.5 - DungeonFrame.tileCentre(1)); val dz = Math.abs(s.z - 0.5 - DungeonFrame.tileCentre(1))
        assert(dx + dz >= 2.0) { "too close to the pillar: $dx $dz" }
        assertEquals(69.0, s.y)
    }

    @Test fun aRoomWithoutAFloorHasNoSpot() {
        val vol = RoomVolume(31, 31, 4, 100, listOf("minecraft:air"), ShortArray(31 * 31 * 4))
        val d = TestRooms.design("Room_E", vol, emptySet())
        val r = RoomInstance(d, vol, d.place(com.engineerclient.p3sim.pack.Rot.SOUTH, listOf(Tile(0, 0))))
        assertEquals(null, SpawnFinder.find(r, 0, 0))
    }
}
