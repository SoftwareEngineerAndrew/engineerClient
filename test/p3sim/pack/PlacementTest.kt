package com.engineerclient.p3sim.pack

import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.PlacementException
import com.engineerclient.p3sim.pack.rooms.WorldPos
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The rotation law of FORMAT-rooms.md section 4, with the worked numbers of 4.4 as test vectors. */
class PlacementTest {
    @Test fun admin1x1WestAtTile23() {
        val p = Placement.of("1x1", 31, 31, Rot.WEST, listOf(Tile(2, 3)))
        assertEquals(-136, p.originX); assertEquals(-104, p.originZ)
        // 4.4: canonical (3, 80, 2) at WEST -> (D-1-z, x) = (28, 3) -> world (-108, 80, -101)
        assertEquals(WorldPos(-108, 80, -101), p.toWorld(3, 80, 2))
        assertEquals(1, p.quarterTurns)
        assertEquals(31, p.boxW); assertEquals(31, p.boxD)
    }

    @Test fun sameCellAtNorthAndEast() {
        val n = Placement.of("1x1", 31, 31, Rot.NORTH, listOf(Tile(2, 3)))
        // 4.4: NORTH (31-1-3, 31-1-2) = (27, 28) -> (-109, 80, -76)
        assertEquals(WorldPos(-109, 80, -76), n.toWorld(3, 80, 2))
        val e = Placement.of("1x1", 31, 31, Rot.EAST, listOf(Tile(2, 3)))
        // 4.4: EAST (z, W-1-x) = (2, 27)
        assertEquals(WorldPos(-136 + 2, 80, -104 + 27), e.toWorld(3, 80, 2))
        assertEquals(3, e.quarterTurns)
    }

    @Test fun toCanonicalIsTheInverse() {
        for (rot in Rot.entries) {
            val p = Placement.of("1x1", 31, 31, rot, listOf(Tile(1, 1)))
            for (x in intArrayOf(0, 3, 15, 30)) for (z in intArrayOf(0, 2, 14, 30)) {
                val w = p.toWorld(x, 70, z)
                assertEquals(x to z, p.toCanonical(w.x, w.z), "rot $rot cell $x,$z")
            }
        }
    }

    @Test fun lRoomWestLeavesHighXHighZEmpty() {
        // 4.4: an L (tiles (0,0) (0,1) (1,1)) placed WEST with nx=nz=2 -> (1,0) (0,0) (0,1) relative to (tx0,tz0).
        val tiles = listOf(Tile(4, 2), Tile(3, 2), Tile(3, 3))
        val p = Placement.of("L", 63, 63, Rot.WEST, tiles)
        assertEquals(setOf(Tile(4, 2), Tile(3, 2), Tile(3, 3)), p.tiles().toSet())
        assertEquals(Tile(4, 2), p.worldTileOf(0, 0))
        assertEquals(Tile(3, 2), p.worldTileOf(0, 1))
        assertEquals(Tile(3, 3), p.worldTileOf(1, 1))
    }

    @Test fun lRoomSouthLeavesHighXLowZEmpty() {
        val p = Placement.of("L", 63, 63, Rot.SOUTH, listOf(Tile(0, 4), Tile(0, 5), Tile(1, 5)))
        assertEquals(-200, p.originX); assertEquals(-200 + 32 * 4, p.originZ)
    }

    @Test fun lineRoomsRunAlongZWhenWest() {
        val p = Placement.of("1x3", 95, 31, Rot.WEST, listOf(Tile(2, 0), Tile(2, 1), Tile(2, 2)))
        assertEquals(31, p.boxW); assertEquals(95, p.boxD)
        assertEquals(Tile(2, 1), p.worldTileOf(1, 0))
    }

    @Test fun tileLawMismatchIsRefused() {
        // A 1x2 placed SOUTH must run along x; tiles along z are the WEST layout.
        assertFailsWith<PlacementException> { Placement.of("1x2", 63, 31, Rot.SOUTH, listOf(Tile(0, 0), Tile(0, 1))) }
    }

    @Test fun rotationsThatNeverOccurAreRefused() {
        assertFailsWith<PlacementException> { Placement.of("2x2", 63, 63, Rot.WEST, listOf(Tile(0, 0), Tile(1, 0), Tile(0, 1), Tile(1, 1))) }
        assertFailsWith<PlacementException> { Placement.of("1x4", 127, 31, Rot.NORTH, (0..3).map { Tile(it, 0) }) }
    }

    @Test fun wrongFootprintIsRefused() {
        assertFailsWith<PlacementException> { Placement.of("1x1", 63, 31, Rot.SOUTH, listOf(Tile(0, 0))) }
    }

    @Test fun fairyIsAlwaysSouth() {
        val p = Placement.of("1x1", 31, 31, Rot.NORTH, listOf(Tile(5, 4)), fairy = true)
        assertEquals(Rot.SOUTH, p.rot)
    }

    @Test fun tileOutsideTheGridIsRefused() {
        assertFailsWith<PlacementException> { Placement.of("1x1", 31, 31, Rot.SOUTH, listOf(Tile(6, 0))) }
    }

    @Test fun footprints() {
        assertEquals(31 to 31, Placement.footprint("1x1"))
        assertEquals(127 to 31, Placement.footprint("1x4"))
        assertEquals(63 to 63, Placement.footprint("2x2"))
        assertEquals(63 to 63, Placement.footprint("L"))
    }

    @Test fun tilesForIsTheInverseOfTheTileLaw() {
        for (shape in listOf("1x1", "1x2", "1x3", "1x4", "2x2", "L")) for (rot in Placement.legalRotations(shape)) {
            val tiles = Placement.tilesFor(shape, rot, 1, 2)
            val (w, d) = Placement.footprint(shape)
            val p = Placement.of(shape, w, d, rot, tiles)
            assertEquals(tiles, p.tiles(), "$shape $rot")
            assertEquals(Placement.canonicalTiles(shape).size, tiles.size)
        }
    }

    @Test fun tilesForPlacesAnLNorthWithLowXHighZEmpty() {
        // 4.3: NORTH leaves the low-x / high-z cell empty.
        assertEquals(setOf(Tile(2, 3), Tile(2, 2), Tile(1, 2)), Placement.tilesFor("L", Rot.NORTH, 1, 2).toSet())
    }

    @Test fun anLRoomDoesNotOwnTheCornerOfItsBox() {
        // L placed SOUTH at tx0=1, tz0=2: tiles (1,2) (1,3) (2,3); the corner tile (2,2) is a neighbour's.
        val p = Placement.of("L", 63, 63, Rot.SOUTH, Placement.tilesFor("L", Rot.SOUTH, 1, 2))
        fun at(tx: Int, tz: Int, dx: Int = 15, dz: Int = 15) = p.ownsColumn(-200 + 32 * tx + dx, -200 + 32 * tz + dz)
        assertEquals(true, at(1, 2)); assertEquals(true, at(1, 3)); assertEquals(true, at(2, 3))
        assertEquals(false, at(2, 2), "the corner belongs to the neighbour")
        assertEquals(false, at(0, 2)); assertEquals(false, at(3, 3))
        // The gap between its own tiles is its own; the gap beside the missing corner is not.
        assertEquals(true, at(1, 3, 31, 15), "gap between (1,3) and (2,3)")
        assertEquals(true, at(1, 2, 15, 31), "gap between (1,2) and (1,3)")
        assertEquals(false, at(1, 2, 31, 15), "gap between (1,2) and the missing (2,2)")
        assertEquals(false, at(2, 2, 15, 31), "gap between the missing (2,2) and (2,3)")
    }
}
