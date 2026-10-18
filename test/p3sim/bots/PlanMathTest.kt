package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.*
import com.engineerclient.p3sim.pack.runstate.Dist
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanMathTest {
    private val d = Dist(100, doubleArrayOf(5.0, 25.0, 50.0, 75.0, 95.0), doubleArrayOf(10.0, 20.0, 40.0, 60.0, 100.0), 0.0, 200.0)

    @Test fun aCurveIsStraightBetweenItsPointsAndFlatOutside() {
        val pts = listOf(0.0 to 1.0, 0.5 to 3.0, 1.0 to 4.0)
        assertEquals(1.0, PlanMath.curve(pts, -0.3), 1e-9)
        assertEquals(2.0, PlanMath.curve(pts, 0.25), 1e-9)
        assertEquals(3.5, PlanMath.curve(pts, 0.75), 1e-9)
        assertEquals(4.0, PlanMath.curve(pts, 1.4), 1e-9)
        assertEquals(0.0, PlanMath.curve(emptyList(), 0.5), 1e-9)
    }

    @Test fun percentOfInvertsAt() {
        for (p in listOf(1.0, 5.0, 10.0, 25.0, 33.0, 50.0, 60.0, 75.0, 90.0, 95.0, 98.0)) assertEquals(p, PlanMath.percentOf(d, d.at(p)), 1e-6)
    }

    @Test fun conditionalSamplesStayAboveTheFloorAndKeepTheShape() {
        val r = Random(1)
        val xs = (1..2000).map { PlanMath.sampleAtLeast(d, r.nextDouble(), 40.0) }
        assertTrue(xs.all { it >= 40.0 })
        // Given at least the median, the median of what is left is the 75th percentile.
        assertEquals(60.0, xs.sorted()[1000], 3.0)
    }

    @Test fun phiIsTheNormalCdf() {
        assertEquals(0.5, PlanMath.phi(0.0), 1e-6)
        assertEquals(0.8413, PlanMath.phi(1.0), 1e-3)
        assertEquals(0.0228, PlanMath.phi(-2.0), 1e-3)
    }

    @Test fun paceKeepsTheMarginalsUniformAndCorrelatesRuns() {
        val us = ArrayList<Double>(); val pairs = ArrayList<Pair<Double, Double>>()
        for (s in 1..3000) {
            val pace = PlanMath.Pace(Random(s), 0.7)
            val a = pace.u(); val b = pace.u()
            us += a; us += b; pairs += a to b
        }
        assertEquals(0.5, us.average(), 0.02)
        assertEquals(0.25, us.map { it * it }.average() - 0.0 - 1.0 / 12, 0.05)   // E[u^2] = 1/3 for uniform
        val ma = pairs.map { it.first }.average(); val mb = pairs.map { it.second }.average()
        val cov = pairs.sumOf { (it.first - ma) * (it.second - mb) } / pairs.size
        val va = pairs.sumOf { (it.first - ma) * (it.first - ma) } / pairs.size
        val vb = pairs.sumOf { (it.second - mb) * (it.second - mb) } / pairs.size
        assertTrue(cov / Math.sqrt(va * vb) > 0.4, "a fast party is fast at both")
        val share = us.count { it < 0.5 }.toDouble() / us.size
        assertTrue(abs(share - 0.5) < 0.02)
    }

    @Test fun aroundBuildsAShapeFromAMedian() {
        val a = PlanMath.around(100.0)
        assertEquals(100.0, a.median, 1e-9)
        assertTrue(a.q.toList().zipWithNext().all { (x, y) -> x <= y })
    }
}
