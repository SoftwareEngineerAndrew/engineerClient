package com.engineerclient.misc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fixtures from recorded runs (Dungeon Recorder): the blocks each door turned to barrier, and the clicks that opened them. */
class DoorGridTest {

    @Test fun cryptDoorFromItsBlocks() {
        // 2026-10-13 Fairy -> Crypt: barriers at x -90..-88, z -74..-72, y 69..72; tiles (3,3) and (3,4).
        val d = DoorGrid.doorAt(-89, 70, -74)!!
        assertEquals(-89 to -73, d.x to d.z)
        assertEquals(setOf(3 to 3, 3 to 4), setOf(d.a, d.b))
    }

    @Test fun frameClicksOpenToo() {
        // Opens from a click one block outside the door's 36 blocks.
        assertEquals(-89 to -73, DoorGrid.doorAt(-91, 70, -74)!!.let { it.x to it.z })
        assertEquals(-121 to -137, DoorGrid.doorAt(-119, 70, -138)!!.let { it.x to it.z })
        assertEquals(-153 to -169, DoorGrid.doorAt(-151, 72, -167)!!.let { it.x to it.z })
    }

    @Test fun outsideTheZone() {
        assertNull(DoorGrid.doorAt(-89, 74, -73))
        assertNull(DoorGrid.doorAt(-89, 67, -73))
        assertNull(DoorGrid.doorAt(-86, 70, -73))
        assertNull(DoorGrid.doorAt(-100, 70, -100))
    }

    @Test fun farSideIsTheOtherRoom() {
        val d = DoorGrid.doorAt(-89, 70, -74)!!
        // Standing in Fairy (3,3) just north of the door.
        assertEquals(3 to 4, DoorGrid.farSide(d, -88.5, -74.4))
        assertEquals(3 to 3, DoorGrid.farSide(d, -88.5, -60.0))
    }

    @Test fun sixtyDoorPlaces() = assertEquals(60, DoorGrid.ALL.size)
}
