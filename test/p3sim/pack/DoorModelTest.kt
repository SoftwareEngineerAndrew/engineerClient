package com.engineerclient.p3sim.pack

import com.engineerclient.p3sim.pack.layouts.DoorKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DoorModelTest {
    @Test fun theBodyIsThreeByFourByThree() {
        val cells = DoorModel.bodyCells(-41, -121)
        assertEquals(DoorModel.BODY_SIZE, cells.size)
        assertEquals(36, cells.toSet().size)
        assertEquals(69, cells.minOf { it.y }); assertEquals(72, cells.maxOf { it.y })
        assertEquals(-42, cells.minOf { it.x }); assertEquals(-40, cells.maxOf { it.x })
        assertEquals(-122, cells.minOf { it.z }); assertEquals(-120, cells.maxOf { it.z })
        assertTrue(cells.all { DoorModel.inBody(-41, -121, it.x, it.y, it.z) })
        assertFalse(DoorModel.inBody(-41, -121, -41, 73, -121), "y 73 is the lintel")
        assertFalse(DoorModel.inBody(-41, -121, -41, 68, -121), "y 68 is the threshold")
        assertEquals(Pos(-42, 69, -122), cells.first(), "stable order: y, then z, then x")
    }

    @Test fun onlyTheKeyedKindsHaveABody() {
        assertEquals("minecraft:coal_block", DoorModel.bodyBlock(DoorKind.WITHER))
        assertEquals("minecraft:coal_block", DoorModel.bodyBlock(DoorKind.FAIRY))
        assertEquals("minecraft:red_terracotta", DoorModel.bodyBlock(DoorKind.BLOOD))
        assertEquals("minecraft:infested_chiseled_stone_bricks", DoorModel.bodyBlock(DoorKind.ENTRANCE))
        for (k in listOf(DoorKind.NORMAL, DoorKind.NONE, DoorKind.UNKNOWN)) assertNull(DoorModel.bodyBlock(k), k.name)
    }

    @Test fun theSlotCentresAreTheOnesTheFrameSays() {
        // The door body is centred on the slot spot, which sits in the gap column: the slot at E(4,2) is the documented (-41, -121).
        assertEquals(-41 to -121, DungeonFrame.doorSpot('E', 4, 2))
        assertTrue(DoorModel.inBody(-41, -121, -42, 70, -121), "one block into the west room")
        assertTrue(DoorModel.inBody(-41, -121, -40, 70, -121), "one block into the east room")
    }
}
