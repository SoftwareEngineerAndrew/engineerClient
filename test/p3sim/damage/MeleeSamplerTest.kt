package com.engineerclient.p3sim.clear.damage

import com.engineerclient.p3sim.pack.mobs.HitQuantiles
import com.engineerclient.p3sim.pack.mobs.MeleeTable
import org.junit.jupiter.api.Test
import java.util.TreeMap
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Melee hit sizes drawn from the measured table (synthetic numbers shaped like the F7 table). */
class MeleeSamplerTest {
    private fun table(atoms: List<MeleeTable.Atom> = emptyList()): MeleeTable {
        val q = TreeMap<Double, Double>()
        listOf(0 to 0.05, 1 to 0.1, 5 to 0.3, 10 to 0.52, 20 to 0.9, 30 to 1.3, 40 to 1.7, 50 to 2.06, 60 to 2.6, 70 to 3.4, 80 to 5.0, 90 to 10.0, 95 to 18.0, 99 to 35.0, 100 to 90.0)
            .forEach { (k, v) -> q[k.toDouble()] = v }
        return MeleeTable(1000, 5.3, q, atoms, false)
    }

    @Test fun quantilesInterpolateInTheLogAndClampAtTheEnds() {
        val q = table().quantiles
        assertEquals(2.06, MeleeSampler.fromQuantiles(q, 50.0), 1e-9)
        assertEquals(0.05, MeleeSampler.fromQuantiles(q, -5.0))
        assertEquals(90.0, MeleeSampler.fromQuantiles(q, 150.0))
        // halfway (in the log) between 0.9 at 20 and 1.3 at 30
        assertEquals(Math.sqrt(0.9 * 1.3), MeleeSampler.fromQuantiles(q, 25.0), 1e-9)
    }

    @Test fun theMedianAndSpreadOfManyDrawsMatchTheTable() {
        val t = table()
        val r = Random(7)
        val xs = (0 until 20000).map { MeleeSampler.sample(t, r) }.sorted()
        val p50 = xs[xs.size / 2]; val p90 = xs[xs.size * 9 / 10]
        assertTrue(p50 in 1.9..2.2, "median $p50")
        assertTrue(p90 in 9.0..11.0, "p90 $p90")
        assertTrue(xs.first() >= 0.05 && xs.last() <= 90.0)
    }

    @Test fun anAtomIsReturnedWithItsProbability() {
        val t = table(listOf(MeleeTable.Atom(10.0, 0.4), MeleeTable.Atom(2.0, 0.1)))
        val r = Random(3)
        val xs = (0 until 20000).map { MeleeSampler.sample(t, r) }
        val tens = xs.count { it == 10.0 }; val twos = xs.count { it == 2.0 }
        assertTrue(tens in 7600..8400, "tens $tens")
        assertTrue(twos in 1700..2300, "twos $twos")
    }

    @Test fun anArchetypeFromItsThreePointsKeepsItsMedian() {
        val h = HitQuantiles(62, 0.70, 3.29, 10.0)
        val r = Random(11)
        val xs = (0 until 20000).map { MeleeSampler.fromArchetype(h, r) }.sorted()
        assertTrue(xs[xs.size / 2] in 3.1..3.5, "median ${xs[xs.size / 2]}")
        assertTrue(xs[xs.size * 9 / 10] in 8.0..12.5, "p90 ${xs[xs.size * 9 / 10]}")
        assertTrue(xs.all { it in 0.01..100.0 })
    }

    @Test fun theSameSeedGivesTheSameHits() {
        val t = table()
        assertEquals((0 until 5).map { MeleeSampler.sample(t, Random(5)) }, (0 until 5).map { MeleeSampler.sample(t, Random(5)) })
        val a = Random(9); val b = Random(9)
        assertEquals((0 until 20).map { MeleeSampler.sample(t, a) }, (0 until 20).map { MeleeSampler.sample(t, b) })
    }
}
