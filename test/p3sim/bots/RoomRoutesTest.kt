package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.*
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomRoutesTest {
    @Test fun visitOrderIsNearestFirst() {
        val order = RoomRoutes.visitOrder(Pt(0.0, 0.0), listOf(Pt(10.0, 0.0), Pt(2.0, 0.0), Pt(5.0, 0.0)))
        assertEquals(listOf(Pt(2.0, 0.0), Pt(5.0, 0.0), Pt(10.0, 0.0)), order)
    }

    @Test fun legsFillTheBudgetExactlyAndStayContinuous() {
        val spots = listOf(Pt(5.0, 3.0), Pt(-4.0, 6.0), Pt(8.0, -2.0))
        for (budget in listOf(1, 10, 60, 300, 1200)) {
            val (legs, end) = RoomRoutes.legs(100, 100 + budget, Pt(0.0, 0.0), spots, Pt(1.0, 1.0), 1.2, Random(budget))
            assertEquals(100, legs.first().t0); assertEquals(100 + budget, legs.last().t1)
            for ((a, b) in legs.zipWithNext()) { assertEquals(a.t1, b.t0); assertTrue(a.b.dist(b.a) < 1e-9, "legs join") }
            assertEquals(legs.last().b, end)
        }
    }

    @Test fun noTimeNoLegs() {
        val (legs, end) = RoomRoutes.legs(50, 50, Pt(3.0, 4.0), listOf(Pt(9.0, 9.0)), Pt(0.0, 0.0), 1.2, Random(1))
        assertTrue(legs.isEmpty()); assertEquals(Pt(3.0, 4.0), end)
    }

    @Test fun aWalkerVisitsTheSecretsOfARoomWithTime() {
        val spots = listOf(Pt(5.0, 3.0), Pt(-4.0, 6.0), Pt(8.0, -2.0))
        var visited = 0; var rooms = 0
        for (seed in 1..60) {
            val (legs, _) = RoomRoutes.legs(0, 600, Pt(0.0, 0.0), spots, Pt(0.0, 0.0), 1.2, Random(seed), FreeArea(listOf(Rect(-20.0, -20.0, 20.0, 20.0))))
            rooms++
            visited += spots.count { s -> legs.any { it.b.dist(s) < 1e-9 } }
        }
        assertTrue(visited.toDouble() / (rooms * spots.size) > 0.7, "most secrets of a room that takes ten seconds get a visit: ${visited.toDouble() / (rooms * spots.size)}")
    }

    @Test fun inRoomLegsAreNotHops() {
        for (seed in 1..40) {
            val (legs, _) = RoomRoutes.legs(0, 400, Pt(0.0, 0.0), listOf(Pt(30.0, 0.0)), Pt(0.0, 0.0), 1.2, Random(seed))
            assertTrue(legs.none { it.hop })
            for (l in legs) if (l.a != l.b) assertTrue(l.a.dist(l.b) / (l.t1 - l.t0) <= RoomWalk.MAX_BPT + 1e-9, "no leg above the top speed")
        }
    }

    @Test fun hopLegsMoveInWholeBlinks() {
        val leg = Leg(0, 10, Pt(0.0, 0.0), Pt(100.0, 0.0), hop = true)
        assertEquals(0.0, leg.at(0).x); assertEquals(0.0, leg.at(0 + 0).x)
        for (t in 0..10) assertEquals(0.0, leg.at(t).x % BotTuning.HOP_BLOCKS, 1e-9, "hops are 10 blocks")
        assertEquals(100.0, leg.at(10).x)
        val walk = Leg(0, 10, Pt(0.0, 0.0), Pt(10.0, 0.0))
        assertEquals(5.0, walk.at(5).x, 1e-9)
    }
}
