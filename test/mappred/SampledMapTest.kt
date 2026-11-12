package com.engineerclient.mappred

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [Prediction.map]: one whole map that keeps the rules at once (made-up maps, no recorded data). */
class SampledMapTest {
    private fun check(v: MapView, puzzlesKnown: Int = 0) {
        val p = MapPredictor(v)
        val rnd = Random(3)
        repeat(40) { p.run(400, 40_000, rnd) }
        val pred = assertNotNull(p.prediction(), "a prediction")
        val m = assertNotNull(pred.map, "a whole map")
        val unknown = (0 until 36).filter { pred.probs[it] != null }.toSet()
        val covered = m.rooms.flatMap { it.toList() }
        assertEquals(covered.size, covered.toSet().size, "no tile in two rooms")
        assertEquals(unknown, covered.toSet(), "every unknown tile is in one of its rooms")
        val count = { c: Int -> m.cls.count { it == c } }
        assertEquals(1, count(Cls.BLOOD), "one blood"); assertEquals(1, count(Cls.FAIRY), "one fairy")
        assertEquals(v.puzzles - puzzlesKnown, count(Cls.PUZZLE), "the tab's puzzles")
        assertTrue(count(Cls.TRAP) <= 1 && count(Cls.YELLOW) <= 1)
        for ((i, tiles) in m.rooms.withIndex()) {
            assertTrue(MapView.shapeOf(tiles, 6) != null, "room $i is a room shape: ${tiles.toList()}")
            if (tiles.size > 1) assertEquals(Cls.BIG, m.cls[i]) else assertTrue(m.cls[i] != Cls.BIG)
        }
        println("rooms: ${v.kinds.size} known + ${m.rooms.size} predicted")
    }

    @Test fun aMapJustStarted() {
        val v = MapView(6, 6, 5, FloorRules.F7)
        v.addRoom(Kind.ENTRANCE, intArrayOf(2)); v.addRoom(Kind.NORMAL, intArrayOf(8, 14))
        v.closeEdges { a, b -> if (setOf(a, b) == setOf(2, 8) || setOf(a, b) == setOf(14, 15)) Edge.DOOR else null }
        check(v)
    }

    @Test fun aMapWithAPuzzleKnown() {
        val v = MapView(6, 6, 5, FloorRules.F7)
        v.addRoom(Kind.ENTRANCE, intArrayOf(2)); v.addRoom(Kind.NORMAL, intArrayOf(8, 14)); v.addRoom(Kind.PUZZLE, intArrayOf(20))
        v.closeEdges { a, b -> if (setOf(a, b) in listOf(setOf(2, 8), setOf(14, 15), setOf(14, 20))) Edge.DOOR else null }
        check(v, puzzlesKnown = 1)
    }
}
