package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Necron hit cue: three hits, each counted down, called, and marked on time or late. */
class NecronHitCueTest {

    private val codes = Regex("§.")
    private fun NecronHitCue.at(n: Int) = line(1000 + n)?.replace(codes, "")

    /** A fight from his first line at tick 1000, left/back/left/back at those n, 25% on the bar at [at25]. */
    private fun fight(l1: Int? = 159, b1: Int? = null, at25: Int? = null, l2: Int? = null, b2: Int? = null) = NecronHitCue().apply {
        onStart(1000)
        for (n in 0..700) {
            val off = when {
                l1 != null && n >= l1 && (b1 == null || n < b1) -> 3.0
                l2 != null && n >= l2 && (b2 == null || n < b2) -> 3.0
                else -> 0.0
            }
            if ((l1 != null && n >= l1) || n < 159) onPosition(1000 + n, off)
            if (at25 != null && n == at25) onBar(1000 + n, 0.25f)
            if (b2 != null && n == b2) break
        }
    }

    @Test
    fun `nothing outside his fight`() {
        val c = NecronHitCue()
        assertNull(c.line(5))
        c.onStart(0); c.onEnd()
        assertNull(c.line(10))
    }

    @Test
    fun `the intro counts down to the end of the sidestep`() {
        val c = NecronHitCue().apply { onStart(1000) }
        assertEquals("Hit 1 20% in 8.30s", c.at(0))
        assertEquals("Hit 1 20% in 0.35s", c.at(159))
    }

    @Test
    fun `the sidestep is counted from when he really left`() {
        val c = NecronHitCue().apply { onStart(1000); onPosition(1162, 1.0) }
        assertEquals("Hit 1 20% in 0.20s", c.at(165))
        assertEquals("HIT NOW 20% · 0.75s left", c.at(169))
    }

    @Test
    fun `hit 1 is open from L1 plus 7 to 184`() {
        val c = NecronHitCue().apply { onStart(1000); onPosition(1159, 1.0) }
        assertEquals("HIT NOW 20% · 0.90s left", c.at(166))
        assertEquals("HIT NOW 20% · 0.00s left", c.at(184))
        assertEquals("HIT NOW 20% · late +1.00s", c.at(185))
        assertEquals("HIT NOW 20% · late +2.00s", c.at(205))
    }

    @Test
    fun `back on time - hit 2 counts down to the 325 grid tick`() {
        val c = fight(b1 = 180)
        assertEquals("Hit 1 on time · Hit 2 55% in 7.00s", c.at(185))
        assertEquals("Hit 2 55% in 0.05s".let { "Hit 1 on time · $it" }, c.at(324))
    }

    @Test
    fun `back late moves the floor lift to the next slot`() {
        val c = fight(b1 = 190)
        assertEquals("Hit 1 late +1.00s · Hit 2 55% in 7.75s", c.at(190))
        assertEquals("HIT NOW 55% · 0.75s left", c.at(345))
    }

    @Test
    fun `hit 2 is open from the floor lifting, 15 ticks to do it`() {
        val c = fight(b1 = 180)
        assertEquals("HIT NOW 55% · 0.75s left", c.at(325))
        assertEquals("HIT NOW 55% · 0.00s left", c.at(340))
        assertEquals("HIT NOW 55% · late", c.at(341))
    }

    @Test
    fun `25 percent on the bar - hit 3 when he leaves mid, 60 after`() {
        val c = fight(b1 = 180, at25 = 328)
        assertEquals("Hit 2 done · Hit 3 20% when he leaves mid ~3.00s", c.at(328))
        assertEquals("Hit 2 done · Hit 3 20% when he leaves mid ~0.00s", c.at(400))
    }

    @Test
    fun `25 percent before the floor lifts is not hit 2 done if the bar never showed it`() {
        // A bar at 0.8 through the lock changes nothing.
        val c = fight(b1 = 180).apply { onBar(1300, 0.8f) }
        assertEquals("Hit 1 on time · Hit 2 55% in 1.25s", c.at(300))
    }

    @Test
    fun `hit 3 is open from his leaving to 404`() {
        val c = fight(b1 = 180, at25 = 328, l2 = 388)
        assertEquals("HIT NOW 20% · 0.80s left", c.at(388))
        assertEquals("HIT NOW 20% · 0.00s left", c.at(404))
        assertEquals("HIT NOW 20% · late +1.00s", c.at(405))
    }

    @Test
    fun `no bar - his leaving mid is hit 2 done`() {
        val c = fight(b1 = 180, l2 = 392)
        assertEquals("HIT NOW 20% · 0.60s left", c.at(392))
    }

    @Test
    fun `a slow hit 2 that already lost the slot gets the next deadline, not a late mark`() {
        val c = fight(b1 = 180, at25 = 380, l2 = 440)
        // 545 is gone (he left at 440 > 404): 565's deadline is 424, also gone, so 585's: 444.
        assertEquals("HIT NOW 20% · 0.20s left", c.at(440))
    }

    @Test
    fun `all hits in - the fastest Necron`() {
        val c = fight(b1 = 180, at25 = 328, l2 = 388, b2 = 400)
        assertEquals("All hits in · stop hitting · Necron 30.35s", c.at(420))
    }

    @Test
    fun `all hits in late - what it cost`() {
        val c = fight(b1 = 180, at25 = 328, l2 = 388, b2 = 410)
        assertEquals("All hits in · stop hitting · Necron 31.35s (+1.00s)", c.at(420))
    }

    @Test
    fun `the end line hides it`() {
        val c = fight(b1 = 180, at25 = 328, l2 = 388, b2 = 400)
        c.onEnd()
        assertNull(c.at(610))
    }

    @Test
    fun `a new first line starts over`() {
        val c = fight(b1 = 180)
        c.onStart(5000)
        assertEquals("Hit 1 20% in 8.30s", c.line(5000)?.replace(codes, ""))
    }

    @Test
    fun `chat starts and ends it`() {
        val c = NecronHitCue()
        c.onChat("[BOSS] Necron: You went further than any human before, congratulations.", 1000)
        assertEquals("Hit 1 20% in 8.30s", c.at(0))
        c.onChat("[BOSS] Necron: ARGH!", 1300)
        assertEquals("Hit 1 20% in 8.30s", c.at(0))
        c.onChat("[BOSS] Necron: All this, for nothing...", 1607)
        assertNull(c.at(610))
    }

    @Test
    fun `the grid`() {
        assertEquals(325, NecronHitCue.grid(325))
        assertEquals(345, NecronHitCue.grid(326))
        assertEquals(545, NecronHitCue.grid(545))
        assertEquals(565, NecronHitCue.grid(546))
        assertEquals(607, NecronHitCue.FASTEST)
    }
}
