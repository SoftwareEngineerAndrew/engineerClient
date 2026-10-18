package com.engineerclient.p3sim.layout

import com.engineerclient.p3sim.clear.layout.DoorGeometry
import com.engineerclient.p3sim.clear.layout.Slot
import com.engineerclient.p3sim.pack.DoorModel
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.layouts.DoorKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The door geometry (pure): which slot a tile side is, where its cells are, what stands in the gap column. */
class DoorGeometryTest {
    @Test fun turnsClockwiseFromAbove() {
        assertEquals('E', DoorGeometry.turn('N', 1))
        assertEquals('N', DoorGeometry.turn('W', 1))
        assertEquals('N', DoorGeometry.turn('S', 2))
        assertEquals('W', DoorGeometry.turn('N', 3))
        assertEquals('W', DoorGeometry.turn('N', -1))
        assertEquals('S', DoorGeometry.turn('S', 4))
    }

    @Test fun everyTileSideNamesItsSlot() {
        assertEquals(Slot('E', 2, 3), DoorGeometry.slotOf(2, 3, 'E'))
        assertEquals(Slot('E', 1, 3), DoorGeometry.slotOf(2, 3, 'W'))
        assertEquals(Slot('S', 2, 3), DoorGeometry.slotOf(2, 3, 'S'))
        assertEquals(Slot('S', 2, 2), DoorGeometry.slotOf(2, 3, 'N'))
    }

    @Test fun theEdgeOfTheGridHasNoSlot() {
        assertNull(DoorGeometry.slotOf(5, 0, 'E'))
        assertNull(DoorGeometry.slotOf(0, 4, 'W'))
        assertNull(DoorGeometry.slotOf(3, 0, 'N'))
        assertNull(DoorGeometry.slotOf(3, 5, 'S'))
    }

    @Test fun aSlotJoinsTheSidesTheTwoTilesShow() {
        for (axis in listOf('E', 'S')) for (tx in 0..4) for (tz in 0..4) {
            val s = Slot(axis, tx, tz)
            val sides = DoorGeometry.sidesOf(s)
            assertEquals(2, sides.size)
            for ((x, z, side) in sides) assertEquals(s, DoorGeometry.slotOf(x, z, side), "$s from $x,$z $side")
        }
    }

    @Test fun theCentreCellOfASideIsNextToTheDoorSpot() {
        // E side of tile (1,2): offset 15, plane 0 is the tile's last column, one block west of the gap column that holds the door's centre.
        val (sx, sz) = DungeonFrame.doorSpot('E', 1, 2)
        assertEquals((sx - 1) to sz, DoorGeometry.sideCell(1, 2, 'E', 15, 0))
        assertEquals((sx - 2) to sz, DoorGeometry.sideCell(1, 2, 'E', 15, 1))
        // The W side of the next tile is one block east of it.
        assertEquals((sx + 1) to sz, DoorGeometry.sideCell(2, 2, 'W', 15, 0))
        val (nx, nz) = DungeonFrame.doorSpot('S', 3, 1)
        assertEquals(nx to (nz - 1), DoorGeometry.sideCell(3, 1, 'S', 15, 0))
        assertEquals(nx to (nz + 1), DoorGeometry.sideCell(3, 2, 'N', 15, 0))
        assertEquals((sx to sz), DoorGeometry.gapCell(Slot('E', 1, 2), 15))
        assertEquals((nx to nz), DoorGeometry.gapCell(Slot('S', 3, 1), 15))
    }

    @Test fun anOpeningIsThreeWideAndFourTall() {
        val cells = DoorGeometry.openingCells(1, 1, 'S', 0)
        assertEquals(12, cells.size)
        assertEquals((69..72).toSet(), cells.map { it.second }.toSet())
        assertEquals(3, cells.map { it.first }.toSet().size)
        assertEquals(1, cells.map { it.third }.toSet().size)
    }

    @Test fun aDoorBodyIsThirtySixBlocksOfItsKind() {
        val slot = Slot('S', 3, 1)
        val coal = DoorGeometry.bodyEdits(slot, DoorKind.WITHER)
        assertEquals(36, coal.size)
        assertTrue(coal.all { it.state == "minecraft:coal_block" })
        val (cx, cz) = DungeonFrame.doorSpot('S', 3, 1)
        assertEquals(DoorModel.bodyCells(cx, cz).map { Triple(it.x, it.y, it.z) }.toSet(), coal.map { Triple(it.x, it.y, it.z) }.toSet())
        assertEquals("minecraft:red_terracotta", DoorGeometry.bodyEdits(slot, DoorKind.BLOOD).first().state)
        assertEquals("minecraft:infested_chiseled_stone_bricks", DoorGeometry.bodyEdits(slot, DoorKind.ENTRANCE).first().state)
        assertEquals("minecraft:coal_block", DoorGeometry.bodyEdits(slot, DoorKind.FAIRY).first().state)
        assertTrue(DoorGeometry.bodyEdits(slot, DoorKind.NORMAL).isEmpty())
    }
}
