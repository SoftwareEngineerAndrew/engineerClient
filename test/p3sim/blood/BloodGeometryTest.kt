package com.engineerclient.p3sim.blood

import com.engineerclient.p3sim.clear.blood.BloodGeometry
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.RoomVolume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BloodGeometryTest {
    /** An invented hall, deliberately not symmetric: interior x 4..26, z 4..22, floor top at y 68, roof from y 90. */
    private fun hall(): RoomVolume {
        val w = 31; val d = 31; val h = 34; val y0 = 66
        val cells = ShortArray(w * d * h)
        for (y in y0 until y0 + h) for (z in 0 until d) for (x in 0 until w) {
            val solid = y <= 68 || y >= 90 || x <= 3 || x >= 27 || z <= 3 || z >= 23
            if (solid) cells[((y - y0) * d + z) * w + x] = 1
        }
        return RoomVolume(w, d, h, y0, listOf("minecraft:air", "minecraft:stone"), cells)
    }

    @Test fun theFloorIsWhereAPlayerStandsInTheMiddle() {
        assertEquals(69, BloodGeometry.standY(hall()))
    }

    @Test fun aRoomWithNoFloorInTheMiddleHasNoSlots() {
        val w = 31; val d = 31; val h = 10
        val v = RoomVolume(w, d, h, 66, listOf("minecraft:air"), ShortArray(w * d * h))
        assertNull(BloodGeometry.standY(v))
        assertEquals(emptyList(), BloodGeometry.canonicalSlots(v))
    }

    @Test fun thirtyFixedStationsInThreeRowsOfTen() {
        val slots = BloodGeometry.canonicalSlots(hall())
        assertEquals(BloodGeometry.ROWS * BloodGeometry.PER_ROW, slots.size)
        assertEquals(setOf(71, 75, 79), slots.map { it.y }.toSet(), "stand rows 71.75, 75.75 and 79.75 sit in cells 71, 75 and 79")
        assertEquals(slots.size, slots.map { Triple(it.x, it.y, it.z) }.toSet().size, "no two heads share a cell")
        // The same ten positions in every row, in the recorded offsets from the middle column (15, 15).
        val expected = listOf(-12 to -4, -12 to 0, -12 to 4, 12 to -4, 12 to 0, 12 to 4, -9 to -12, 9 to -12, -9 to 12, 9 to 12).map { (15 + it.first) to (15 + it.second) }
        for (row in 0 until 3) assertEquals(expected, slots.subList(row * 10, row * 10 + 10).map { it.x to it.z })
    }

    @Test fun theStationsDoNotDependOnTheRoomsShapeOnlyOnItsFloor() {
        val a = BloodGeometry.canonicalSlots(hall())
        // The same floor, a different hall: the same cells.
        val w = 31; val d = 31; val h = 34; val y0 = 66
        val cells = ShortArray(w * d * h)
        for (y in y0 until y0 + h) for (z in 0 until d) for (x in 0 until w) if (y <= 68 || y >= 90) cells[((y - y0) * d + z) * w + x] = 1
        val b = BloodGeometry.canonicalSlots(RoomVolume(w, d, h, y0, listOf("minecraft:air", "minecraft:stone"), cells))
        assertEquals(a, b)
    }

    @Test fun facingsLookIntoTheRoom() {
        val slots = BloodGeometry.canonicalSlots(hall()).take(10)
        assertEquals(listOf("east", "east", "east", "west", "west", "west", "south", "south", "north", "north"), slots.map { it.facing })
    }

    @Test fun everyRotationTurnsTheStationsWithTheRoom() {
        val v = hall()
        for (rot in Rot.entries) {
            val p = Placement.of("1x1", 31, 31, rot, listOf(Tile(2, 5)))
            val slots = BloodGeometry.worldSlots(v, p)
            assertEquals(30, slots.size)
            assertEquals((0 until 30).toList(), slots.map { it.index })
            val cx = -200 + 32 * 2 + 15; val cz = -200 + 32 * 5 + 15
            // The offsets of FORMAT-rooms 4.2: SOUTH (ox, oz), WEST (-oz, ox), NORTH (-ox, -oz), EAST (oz, -ox).
            val offsets = listOf(-12 to -4, -12 to 0, -12 to 4, 12 to -4, 12 to 0, 12 to 4, -9 to -12, 9 to -12, -9 to 12, 9 to 12)
            for ((i, o) in offsets.withIndex()) {
                val (wx, wz) = when (rot) {
                    Rot.SOUTH -> o.first to o.second
                    Rot.WEST -> -o.second to o.first
                    Rot.NORTH -> -o.first to -o.second
                    Rot.EAST -> o.second to -o.first
                }
                assertEquals(cx + wx, slots[i].x, "$rot station $i x")
                assertEquals(cz + wz, slots[i].z, "$rot station $i z")
                assertEquals(71, slots[i].y)
            }
        }
    }

    @Test fun theWorldCentreFollowsTheRotation() {
        val v = hall()
        for (rot in Rot.entries) {
            val p = Placement.of("1x1", 31, 31, rot, listOf(Tile(2, 5)))
            val (x, y, z) = assertNotNull(BloodGeometry.worldCentre(v, p))
            assertEquals(-200.0 + 32 * 2 + 15.5, x, 1e-9, "$rot")
            assertEquals(-200.0 + 32 * 5 + 15.5, z, 1e-9, "$rot")
            assertEquals(69.0, y, 1e-9)
        }
    }

    @Test fun facingsTurnClockwise() {
        assertEquals("east", BloodGeometry.rotateFacing("north", 1))
        assertEquals("north", BloodGeometry.rotateFacing("west", 1))
        assertEquals("south", BloodGeometry.rotateFacing("north", 2))
        assertEquals("west", BloodGeometry.rotateFacing("north", 3))
        assertEquals("north", BloodGeometry.rotateFacing("north", 0))
    }

    @Test fun theExitIsTheBackAlcoveInEveryRotation() {
        // The recorded strike cells on a 31-block tile at local offset (0, 0): SOUTH (15, 29), WEST (1, 15), NORTH (15, 1), EAST (29, 15).
        val expected = mapOf(Rot.SOUTH to (15 to 29), Rot.WEST to (1 to 15), Rot.NORTH to (15 to 1), Rot.EAST to (29 to 15))
        for (rot in Rot.entries) {
            val p = Placement.of("1x1", 31, 31, rot, listOf(Tile(3, 1)))
            val site = BloodGeometry.exitSite(p, 69)
            val (lx, lz) = expected.getValue(rot)
            val ox = -200 + 32 * 3; val oz = -200 + 32 * 1
            assertEquals(ox + lx + 0.5, site.strikeX, 1e-9, "$rot")
            assertEquals(oz + lz + 0.5, site.strikeZ, 1e-9, "$rot")
            assertEquals(69.5, site.strikeY, 1e-9)
            assertEquals(12, site.cells.size)
            assertEquals(setOf(69, 70, 71, 72), site.cells.map { it.y }.toSet())
            assertEquals(rot == Rot.SOUTH || rot == Rot.NORTH, site.axisX, "$rot axis")
            // Three wide along the wall, one thick across it.
            val xs = site.cells.map { it.x }.toSet(); val zs = site.cells.map { it.z }.toSet()
            if (site.axisX) { assertEquals(3, xs.size); assertEquals(1, zs.size) } else { assertEquals(1, xs.size); assertEquals(3, zs.size) }
        }
    }

    @Test fun theSameDungeonExampleLandsOnTheRecordedCells() {
        // A recorded entry in a room on tile (3, 1): bolt at (-88.5, 69.5, -138.5), portal blocks x -90..-88 at z -139, y 69..72.
        val p = Placement.of("1x1", 31, 31, Rot.SOUTH, listOf(Tile(3, 1)))
        val site = BloodGeometry.exitSite(p, 69)
        assertEquals(-88.5, site.strikeX, 1e-9); assertEquals(-138.5, site.strikeZ, 1e-9)
        assertEquals(setOf(-90, -89, -88), site.cells.map { it.x }.toSet())
        assertEquals(setOf(-139), site.cells.map { it.z }.toSet())
    }

    @Test fun aBodyTouchesThePortalOnlyWhereItOverlapsTheBlocks() {
        val p = Placement.of("1x1", 31, 31, Rot.SOUTH, listOf(Tile(3, 1)))
        val site = BloodGeometry.exitSite(p, 69)
        // Standing in the middle of the portal.
        assertTrue(site.touches(-88.5, 69.0, -138.5))
        // Six blocks in front of it (the recorded spot when the portal opened): no.
        assertFalse(site.touches(-88.18, 69.0, -144.59))
        // Just short of the cell: the body's half width reaches in.
        assertTrue(site.touches(-88.5, 69.0, -139.25))
        assertFalse(site.touches(-88.5, 69.0, -139.4))
        // Beside it and over it.
        assertFalse(site.touches(-86.5, 69.0, -138.5))
        assertFalse(site.touches(-88.5, 73.0, -138.5))
        assertTrue(site.touches(-88.5, 71.5, -138.5), "a jump up into the top cell")
    }
}
