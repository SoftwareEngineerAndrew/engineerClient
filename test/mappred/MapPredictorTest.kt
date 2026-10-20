package com.engineerclient.mappred

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic 6x6 maps (tile = z * 6 + x), built by hand. */
class MapPredictorTest {

    /** [rooms] discovered (kind, tiles), [doors] (a, b, kind) between tiles; every other edge by a discovered tile is a wall. */
    private fun map(rooms: List<Pair<Kind, IntArray>>, doors: List<Triple<Int, Int, Byte>>, puzzles: Int = 3): MapView {
        val v = MapView(6, 6, puzzles)
        for ((k, t) in rooms) assertNotNull(v.addRoom(k, t))
        val byPair = doors.associate { (a, b, k) -> (minOf(a, b) to maxOf(a, b)) to k }
        v.closeEdges { a, b -> byPair[minOf(a, b) to maxOf(a, b)] }
        return v
    }

    /** Entrance at (1,0), a 2x2 behind it with a wither door down from (2,2) to (2,3), normal doors east and west. */
    private fun opening() = map(
        listOf(Kind.ENTRANCE to intArrayOf(1), Kind.NORMAL to intArrayOf(7, 8, 13, 14)),
        listOf(Triple(1, 7, Edge.DOOR), Triple(14, 20, Edge.WITHER), Triple(8, 9, Edge.DOOR), Triple(7, 6, Edge.DOOR)),
    )

    private fun predict(v: MapView, samples: Int = 400): Prediction {
        val p = MapPredictor(v)
        p.run(samples, 400_000, Random(42))
        return assertNotNull(p.prediction())
    }

    private fun total(p: Prediction, c: Int) = p.probs.sumOf { (it?.get(c) ?: 0f).toDouble() }
    private fun dist(a: Int, b: Int) = abs(a % 6 - b % 6) + abs(a / 6 - b / 6)

    @Test fun shapesFromTiles() {
        assertEquals(Shape.ONE, MapView.shapeOf(intArrayOf(3), 6))
        assertEquals(Shape.TWO, MapView.shapeOf(intArrayOf(3, 9), 6))
        assertEquals(Shape.L, MapView.shapeOf(intArrayOf(6, 7, 12), 6))
        assertEquals(Shape.THREE, MapView.shapeOf(intArrayOf(0, 1, 2), 6))
        assertEquals(Shape.BOX, MapView.shapeOf(intArrayOf(7, 8, 13, 14), 6))
        assertEquals(Shape.FOUR, MapView.shapeOf(intArrayOf(0, 6, 12, 18), 6))
        assertNull(MapView.shapeOf(intArrayOf(0, 2), 6))
        assertNull(MapView.shapeOf(intArrayOf(0, 1, 7, 8, 9), 6))
    }

    @Test fun everyMapHasItsSpecialRoomsOnce() {
        val p = predict(opening())
        assertEquals(0, p.level)
        assertEquals(1.0, total(p, Cls.TRAP), 1e-4)
        assertEquals(1.0, total(p, Cls.YELLOW), 1e-4)
        assertEquals(1.0, total(p, Cls.BLOOD), 1e-4)
        assertEquals(1.0, total(p, Cls.FAIRY), 1e-4)
        assertEquals(3.0, total(p, Cls.PUZZLE), 1e-4)
    }

    @Test fun bloodAndFairyAreBehindTheWitherDoor() {
        val p = predict(opening())
        val wither = p.splits.single { it.door == Edge.WITHER }
        assertEquals(20, wither.tile)
        assertEquals(1f, wither.expect[Cls.BLOOD], 1e-4f)
        assertEquals(1f, wither.expect[Cls.FAIRY], 1e-4f)
        for (s in p.splits) if (s !== wither) assertEquals(0f, s.expect[Cls.BLOOD])
        // the splits share out every unknown tile
        assertEquals(31f, p.splits.sumOf { it.tiles.toDouble() }.toFloat(), 1e-3f)
    }

    @Test fun bloodIsFourStepsPastAKnownFairy() {
        // a wither path of 1x1s from the entrance: (1,1) (1,2) (1,3) (1,4), the fairy at (2,4) five steps in,
        // and its wither door on to (3,4): blood must be exactly 4 walk steps past the fairy
        val v = map(
            listOf(Kind.ENTRANCE to intArrayOf(1), Kind.NORMAL to intArrayOf(7), Kind.NORMAL to intArrayOf(13), Kind.NORMAL to intArrayOf(19),
                Kind.NORMAL to intArrayOf(25), Kind.FAIRY to intArrayOf(26)),
            listOf(Triple(1, 7, Edge.DOOR), Triple(7, 13, Edge.WITHER), Triple(13, 19, Edge.WITHER), Triple(19, 25, Edge.WITHER),
                Triple(25, 26, Edge.WITHER), Triple(26, 27, Edge.WITHER), Triple(7, 8, Edge.DOOR)),
        )
        val p = predict(v, 300)
        assertEquals(0, p.level)
        var seen = 0.0
        for (t in 0 until 36) {
            val b = p.probs[t]?.get(Cls.BLOOD) ?: continue
            if (b <= 0f) continue
            seen += b
            assertTrue(dist(t, 26) == 2 || dist(t, 26) == 4, "blood at $t, ${dist(t, 26)} from the fairy")
            assertTrue(dist(t, 27) % 2 == 1, "blood at $t, ${dist(t, 27)} from the wither door's tile")
        }
        assertEquals(1.0, seen, 1e-4)
        assertEquals(0.0, total(p, Cls.FAIRY), 1e-6)
    }

    @Test fun bigRoomGuessesAreRealRoomsOnUnknownTiles() {
        val v = opening()
        val p = predict(v)
        assertTrue(p.bigRooms.isNotEmpty())
        val perTile = DoubleArray(36)
        for (room in p.bigRooms) {
            val shape = assertNotNull(MapView.shapeOf(room.tiles, 6))
            assertTrue(shape != Shape.ONE && room.chance in 0.05f..1f)
            for (t in room.tiles) { assertEquals(-1, v.roomAt[t]); perTile[t] += room.chance.toDouble() }
        }
        // the placements of a tile's big room add up to its chance of being in a big room
        for (t in 0 until 36) assertTrue(perTile[t] <= (p.probs[t]?.get(Cls.BIG) ?: 0f) + 1e-4, "tile $t")
        assertEquals(p.bigRooms.sortedByDescending { it.chance }.map { it.chance }, p.bigRooms.map { it.chance })
    }

    @Test fun predictedDoorsJoinUnknownNeighbours() {
        val v = opening()
        val p = predict(v)
        assertTrue(p.doors.isNotEmpty())
        for (d in p.doors) {
            assertTrue(d.b == d.a + 1 && d.a % 6 != 5 || d.b == d.a + 6, "door ${d.a}-${d.b}")
            assertEquals(-1, v.roomAt[d.a]); assertEquals(-1, v.roomAt[d.b])
            assertTrue(d.chance in 0.05f..1.0001f && d.wither + d.blood <= d.chance + 1e-4f && d.fairy <= d.chance + 1e-4f)
        }
        // the wither path goes on from the wither door's tile, so some door out of the unknown part is a wither door
        assertTrue(p.doors.any { it.wither > 0f })
    }

    @Test fun aWalledInTileWithOneDoorIsASpecialRoom() {
        // an L behind the entrance at (0,1),(1,1),(0,2); (0,0) touches only the entrance (no door) and the L (a door)
        val v = map(
            listOf(Kind.ENTRANCE to intArrayOf(1), Kind.NORMAL to intArrayOf(6, 7, 12)),
            listOf(Triple(1, 7, Edge.DOOR), Triple(6, 0, Edge.DOOR), Triple(12, 18, Edge.WITHER), Triple(7, 8, Edge.DOOR)),
        )
        val p = predict(v)
        val corner = assertNotNull(p.probs[0])
        assertEquals(1f, corner[Cls.PUZZLE] + corner[Cls.TRAP] + corner[Cls.YELLOW] + corner[Cls.RARE], 1e-4f)
        assertEquals(0f, corner[Cls.BLOOD])
    }

    @Test fun aTileBehindTheBloodDoorIsBlood() {
        val v = map(
            listOf(Kind.ENTRANCE to intArrayOf(1), Kind.NORMAL to intArrayOf(7, 8, 13, 14)),
            listOf(Triple(1, 7, Edge.DOOR), Triple(14, 20, Edge.BLOOD), Triple(8, 9, Edge.DOOR)),
        )
        val p = predict(v, 200)
        assertEquals(1f, assertNotNull(p.probs[20])[Cls.BLOOD], 1e-4f)
    }

    @Test fun aMapNoTreeFitsGivesNothing() {
        // (2,0) has doors from the entrance and from the room below it: two ways in from the known side
        val v = map(
            listOf(Kind.ENTRANCE to intArrayOf(1), Kind.NORMAL to intArrayOf(7, 8)),
            listOf(Triple(1, 7, Edge.DOOR), Triple(1, 2, Edge.DOOR), Triple(8, 2, Edge.DOOR), Triple(7, 13, Edge.WITHER)),
        )
        val p = MapPredictor(v)
        assertEquals(0, p.run(50, 5_000, Random(1)))
        assertNull(p.prediction())
    }
}
