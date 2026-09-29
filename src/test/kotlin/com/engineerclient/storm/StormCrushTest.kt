package com.engineerclient.storm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Storm's crush rule against moments from the recorded runs (docs/storm-crush.md). */
class StormCrushTest {

    private fun pillar(name: String) = StormCrush.PILLARS.first { it.name == name }

    /** Where Storm stopped when he was crushed (x, y, z), and the crushing pillar's lowest block. */
    private val crushes = listOf(
        Triple("Purple", doubleArrayOf(101.998, 179.938, 64.125), 181),
        Triple("Purple", doubleArrayOf(99.654, 178.594, 63.625), 181),
        // In the Purple square's rounded -x/-z corner, where the pillar has no blocks.
        Triple("Purple", doubleArrayOf(97.751, 179.0, 63.14), 181),
        // Right on the zone's -x edge, and in the Yellow square's rounded corner.
        Triple("Yellow", doubleArrayOf(43.012, 173.253, 65.769), 176),
        Triple("Yellow", doubleArrayOf(43.075, 173.277, 63.664), 176),
        Triple("Yellow", doubleArrayOf(44.755, 172.047, 62.408), 169),
        // Pushed onto the floor by the pillar coming down.
        Triple("Green", doubleArrayOf(48.761, 169.0, 41.87), 170),
    )

    @Test
    fun `every recorded crush is inside`() {
        for ((name, at, bottom) in crushes) {
            val v = StormCrush.judge(pillar(name), at[0], at[1], at[2], bottom)
            assertTrue(v.inside, "$name at ${at.toList()}: inset ${v.inset}, head ${v.head}")
            assertEquals(name, StormCrush.nearest(at[0], at[2]).name)
        }
    }

    @Test
    fun `recorded checks that did not crush are outside`() {
        // Beyond the zone's +z edge (still over the Green pillar's own blocks).
        val z = StormCrush.judge(pillar("Green"), 44.559, 171.499, 44.251, 170)
        assertFalse(z.inside); assertEquals(-0.251, z.inset, 1e-9)
        // A block off the Yellow zone's -x edge.
        assertFalse(StormCrush.judge(pillar("Yellow"), 42.016, 173.176, 65.023, 175).inside)
        // Under the pillar, but his head an eighth of a block short of its bottom.
        val low = StormCrush.judge(pillar("Yellow"), 47.756, 172.896, 64.405, 176)
        assertTrue(low.inset > 0); assertFalse(low.inside); assertEquals(-0.129, low.head!!, 1e-9)
    }

    @Test
    fun `the zone is the 6x6 inside the pillar's -x-z corner`() {
        val p = pillar("Yellow")
        assertEquals(0.0, StormCrush.inset(p, 43.0, 65.0))
        assertEquals(0.0, StormCrush.inset(p, 49.0, 65.0))
        assertTrue(StormCrush.inset(p, 49.1, 65.0) < 0)
        assertEquals(3.0, StormCrush.inset(p, 46.0, 65.0))
        assertEquals(0.0, StormCrush.distance(p, 46.0, 65.0))
        assertEquals(5.0, StormCrush.distance(p, 38.0, 65.0), 1e-9)
    }

    @Test
    fun `checks every 20 ticks from the phase start`() {
        assertFalse(StormCrush.isCheck(0))
        assertTrue(StormCrush.isCheck(20)); assertTrue(StormCrush.isCheck(700))
        assertFalse(StormCrush.isCheck(19)); assertFalse(StormCrush.isCheck(21))
    }

    @Test
    fun `a pillar crushes for 60 ticks after its last step`() {
        assertTrue(StormCrush.armed(100, 100)); assertTrue(StormCrush.armed(100, 160))
        assertFalse(StormCrush.armed(100, 163)); assertFalse(StormCrush.armed(null, 100))
    }
}
