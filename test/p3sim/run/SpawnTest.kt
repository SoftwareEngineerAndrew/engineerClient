package com.engineerclient.p3sim.run

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpawnTest {
    @Test fun southEdgeFacesNorthIntoTheDungeon() {
        val inner = Runs.spawnOnEdge(4, 5, outer = false)!!
        assertEquals(-56.5, inner.x); assertEquals(-22.5, inner.z); assertEquals(75.5, inner.y); assertEquals(180f, inner.yaw)
        val outer = Runs.spawnOnEdge(4, 5, outer = true)!!
        assertEquals(-20.5, outer.z); assertEquals(76.5, outer.y)
    }

    @Test fun otherEdges() {
        val c = Runs.spawnOnEdge(0, 0, outer = true)!!
        assertEquals(-200.0 + 15.5, c.x); assertEquals(-200.0 + 11.5, c.z)
        val n = Runs.spawnOnEdge(2, 0, outer = false)!!
        assertEquals(-200.0 + 64 + 15.5, n.x); assertEquals(-200.0 + 14.5, n.z); assertEquals(0f, n.yaw)
        val w = Runs.spawnOnEdge(0, 3, outer = false)!!
        assertEquals(-200.0 + 14.5, w.x); assertEquals(270f, w.yaw)
        val e = Runs.spawnOnEdge(5, 3, outer = true)!!
        assertEquals(-200.0 + 160 + 19.5, e.x); assertEquals(90f, e.yaw)
    }

    @Test fun innerTilesHaveNoEdgeSpawn() = assertNull(Runs.spawnOnEdge(2, 2, outer = false))
}
