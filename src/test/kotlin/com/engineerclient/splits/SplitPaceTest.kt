package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pace against the dark green times, and lag (SplitPace). */
class SplitPaceTest {

    private fun stamp(t: Int, lagMs: Long = 0) = Stamp(t * 50L + lagMs, t)
    private fun split(label: String, from: Int, to: Int?, lagMs: Long = 0) = Split(label, stamp(from), to?.let { stamp(it, lagMs) })
    private val none: (String) -> List<SplitPace.Sub> = { emptyList() }

    /** Every split at dark green: 10.65 s + (1200 + 73 + 511 + 906) ticks + 39 s + (125 + 610 + 85) ticks. */
    private val darkGreenMs = 10_650L + (1200 + 73 + 511 + 906) * 50L + 39_000L + (125 + 610 + 85) * 50L

    @Test
    fun `before anything is proven, pace is a dark green run`() {
        val open = split(SplitTracker.OPEN, 0, null)
        assertEquals(darkGreenMs, SplitPace.pace(listOf(open), none, stamp(10)).ms)
    }

    @Test
    fun `a running split counts only once it is past its dark green`() {
        val open = split(SplitTracker.OPEN, 0, 200)               // 10.0 s: faster than 10.65
        val blood = split(SplitTracker.BLOOD, 200, null)
        assertEquals(darkGreenMs - 650, SplitPace.pace(listOf(open, blood), none, stamp(400)).ms)
        // 1300 ticks into Blood: 100 past its 1200.
        assertEquals(darkGreenMs - 650 + 100 * 50, SplitPace.pace(listOf(open, blood), none, stamp(1500)).ms)
    }

    @Test
    fun `a running split's sub splits prove time lost before the split itself is past dark green`() {
        val maxor = split(SplitTracker.MAXOR, 0, null)
        // Crystals took 216 ticks (20 over its 196): Maxor's projection is 20 over its 511.
        val subs = listOf(SplitPace.Sub("maxor.crystals", split("x", 0, 216)), SplitPace.Sub("maxor.lure", split("x", 216, null)))
        val paced = SplitPace.pace(listOf(maxor), { if (it == SplitTracker.MAXOR) subs else emptyList() }, stamp(220))
        assertEquals(darkGreenMs + 20 * 50, paced.ms)
    }

    @Test
    fun `a finished sub split faster than its dark green brings pace in`() {
        val storm = split(SplitTracker.STORM, 0, null)
        val subs = listOf(SplitPace.Sub("storm.opening", split("x", 0, 687)), SplitPace.Sub("storm.crush1", split("x", 687, 698)),
            SplitPace.Sub("storm.pin", split("x", 698, null)))     // Crush 2 ticks under its 13
        assertEquals(darkGreenMs - 2 * 50, SplitPace.pace(listOf(storm), { if (it == SplitTracker.STORM) subs else emptyList() }, stamp(698)).ms)
    }

    @Test
    fun `lag counts the tick-timed splits only`() {
        val open = split(SplitTracker.OPEN, 0, 200, lagMs = 3000)       // real time: not counted
        val blood = split(SplitTracker.BLOOD, 200, 1400, lagMs = 4200)  // 1200 ticks in 61.2 s: 1.2 s lost
        assertEquals(1200L, SplitPace.lag(listOf(open, Split(blood.label, stamp(200, 3000), blood.stop)), stamp(1400, 4200)))
    }

    @Test
    fun `pace reads m colon ss`() {
        assertEquals("4:58", SplitPace.mss(298_400))
        assertEquals("0:07", SplitPace.mss(7_900))
    }
}
