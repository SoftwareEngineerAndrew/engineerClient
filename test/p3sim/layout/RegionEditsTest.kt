package com.engineerclient.p3sim.layout

import com.engineerclient.p3sim.clear.layout.RegionEdits
import com.engineerclient.p3sim.pack.Pos
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.rooms.RoomBlockEntity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RegionEditsTest {
    private fun be(x: Int, y: Int, z: Int, marker: Boolean) = RoomBlockEntity(Pos(x, y, z), "sign", null, listOf("cuboid"), marker, null)

    @Test fun builderMarkerSignsAreRemovedAndOtherSignsStay() {
        val vol = TestRooms.volume()
        // Put signs into the volume at (5,70,5) marker, (6,70,5) real, (15,70,15) marker in the centre column.
        val cells = vol.cells.copyOf()
        fun idx(x: Int, y: Int, z: Int) = ((y - vol.y0) * 31 + z) * 31 + x
        for ((x, z) in listOf(5 to 5, 6 to 5, 15 to 15)) cells[idx(x, 70, z)] = 2
        val v = com.engineerclient.p3sim.pack.rooms.RoomVolume(31, 31, vol.h, vol.y0, vol.palette, cells)
        val d = TestRooms.design("Room_S", v, emptySet(), entities = listOf(be(5, 70, 5, true), be(6, 70, 5, false), be(15, 70, 15, true)))
        val inst = com.engineerclient.p3sim.clear.layout.RoomInstance(d, v, d.place(Rot.WEST, listOf(Tile(2, 2))))
        val edits = RegionEdits.markerRemovals(listOf(inst))
        // Only the marker outside the centre column; at its WORLD cell.
        assertEquals(1, edits.size)
        val w = inst.placement.toWorld(5, 70, 5)
        assertEquals(Triple(w.x, w.y, w.z), Triple(edits[0].x, edits[0].y, edits[0].z))
        assertEquals("minecraft:air", edits[0].state)
    }

    @Test fun aFairyRoomGetsItsBlueClayAtTheTileCorner() {
        val vol = TestRooms.volume()
        val base = TestRooms.design("Fairy", vol, emptySet())
        val fairy = TestRooms.withType(base, "fairy", 99)
        val inst = com.engineerclient.p3sim.clear.layout.RoomInstance(fairy, vol, fairy.place(Rot.SOUTH, listOf(Tile(5, 1))))
        val e = RegionEdits.fairyMarkers(listOf(inst))
        assertEquals(1, e.size)
        assertEquals(Triple(-40, 99, -168), Triple(e[0].x, e[0].y, e[0].z))
        assertEquals("minecraft:blue_terracotta", e[0].state)
        // Other rooms get none.
        val other = com.engineerclient.p3sim.clear.layout.RoomInstance(base, vol, base.place(Rot.SOUTH, listOf(Tile(1, 1))))
        assertEquals(0, RegionEdits.fairyMarkers(listOf(other)).size)
    }
}
