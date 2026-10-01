package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sub splits' colour bands, bests and filler (SubSplitGrades). */
class SubSplitGradesTest {

    private fun colour(id: String, value: Long, finished: Boolean = true, best: Long? = null, banded: Boolean = true) =
        SubSplitGrades.colour(id, value, finished, best, banded, "§x")

    @Test
    fun `percentile bands, slowest black`() {
        assertEquals("§2", colour("storm.pin", 0))
        assertEquals("§a", colour("storm.pin", 3))
        assertEquals("§e", colour("storm.pin", 6))
        assertEquals("§c", colour("storm.pin", 16))
        assertEquals("§4", colour("storm.pin", 25))
        assertEquals("§0", colour("storm.pin", 26))
    }

    @Test
    fun `grid steps are graded by checks missed, the on-time step all dark green`() {
        assertEquals("§2", colour("storm.crush1", 11))   // the t 699 check...
        assertEquals("§2", colour("storm.crush1", 13))   // ...with its jitter
        assertEquals("§e", colour("storm.crush1", 32))   // one check late
        assertEquals("§c", colour("storm.crush1", 52))   // two
        assertEquals("§4", colour("storm.crush1", 72))   // three
        assertEquals("§0", colour("storm.crush1", 92))   // four
        // Maxor's lure on the tick of his first hit, from his first line.
        assertEquals(206L, SubSplitGrades.value("maxor.lure", 600, 11, 206))
        assertEquals("§2", colour("maxor.lure", 207))
        assertEquals("§e", colour("maxor.lure", 216))
        assertEquals("§2", colour("split.necron", 608))
        assertEquals("§e", colour("split.necron", 627))
    }

    @Test
    fun `filler is gray, and a Necron lock turns dark red when ARGH missed its grid tick`() {
        assertEquals("§7", colour("maxor.animation", 102))
        assertEquals("§7", colour("necron.lock1", 330))
        assertEquals("§4", colour("necron.lock1", 350))
        assertEquals("§7", colour("necron.lock2", 350, finished = false))
    }

    @Test
    fun `a best is gold, but not when it is under the floor or the step is still running`() {
        assertEquals("§6", colour("storm.pin", 4, best = 4))
        assertEquals("§a", colour("storm.pin", 3, finished = false, best = 3))
        assertTrue(SubSplitGrades.canBeBest("storm.flight", 90))
        assertFalse(SubSplitGrades.canBeBest("storm.flight", 40))      // under his 85-tick floor: a missed moment
        assertFalse(SubSplitGrades.canBeBest("maxor.animation", 102))  // filler never is
    }

    @Test
    fun `real-time steps use milliseconds, and off F7 only gold and gray apply`() {
        assertEquals(10150L, SubSplitGrades.value("maxor.cooldown", 10150, 199))
        assertEquals("§2", colour("maxor.cooldown", 10150))
        assertEquals("§e", colour("maxor.cooldown", 10650))
        assertEquals("§x", colour("terms.s2", 9000, banded = false))
        assertEquals("§6", colour("terms.s2", 9000, best = 9000, banded = false))
    }

    @Test
    fun `bests round-trip through the config text`() {
        val bests = mapOf("storm.pin" to 3L, "terms.s1" to 12000L)
        assertEquals(bests, SubSplitGrades.parseBests(SubSplitGrades.formatBests(bests)))
        assertEquals(emptyMap(), SubSplitGrades.parseBests(""))
    }
}
