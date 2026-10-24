package com.engineerclient.splits

import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** [SplitFormat] against the String.format versions it replaced: the same text for every input. */
class SplitFormatTest {
    private fun oldSeconds(ms: Long): String = String.format(Locale.ROOT, "%.2f", ms / 1000.0) + "s"

    private fun oldMinutes(ms: Long): String {
        val tenths = Math.round(ms / 100.0)
        return "${tenths / 600}m " + String.format(Locale.ROOT, "%.1f", tenths % 600 / 10.0) + "s"
    }

    private var mismatches = 0
    private val examples = mutableListOf<String>()

    private fun check(ms: Long) {
        val s = SplitFormat.seconds(ms)
        val os = oldSeconds(ms)
        if (s != os) { mismatches++; if (examples.size < 10) examples += "seconds($ms) = $s, was $os" }
        val m = SplitFormat.minutes(ms)
        val om = oldMinutes(ms)
        if (m != om) { mismatches++; if (examples.size < 10) examples += "minutes($ms) = $m, was $om" }
    }

    /** Each value and the 1000 ms either side of it, negated too. */
    private fun around(vararg centres: Long) {
        for (c in centres) for (d in -1000L..1000L) { check(c + d); check(-(c + d)) }
    }

    private fun assertNone() = assertEquals(0, mismatches, examples.joinToString("\n"))

    @Test fun formatsLikeBefore() {
        assertEquals("14.00s", SplitFormat.seconds(14_000))
        assertEquals("1.01s", SplitFormat.seconds(1005))
        assertEquals("-0.00s", SplitFormat.seconds(-4))
        assertEquals("-0.01s", SplitFormat.seconds(-5))
        assertEquals("3m 8.2s", SplitFormat.minutes(188_200))
        assertEquals("2m 0.0s", SplitFormat.minutes(119_970))
    }

    /** Five minutes back to fifty forward, every ms: any split, Pace, lag or tick time a run has. */
    @Test fun everyMsOfARun() {
        for (ms in -300_000L..3_000_000L) check(ms)
        assertNone()
    }

    /**
     * Larger values: a stamp against a zero start (epoch ms), the most server ticks times 50, the
     * powers of 10 and 2 (10^15 is where seconds goes back to String.format), and the extremes.
     */
    @Test fun largeAndEdgeValues() {
        around(1_800_000_000_000L, 1_700_000_000_005L, Int.MAX_VALUE * 50L)
        var p = 1L
        repeat(18) { p *= 10; around(p) }
        for (k in 20..62) around(1L shl k)
        for (ms in listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE, Long.MAX_VALUE - 1)) check(ms)
        assertNone()
    }

    /** Random values of every length up to 15 digits, and the same rounded to a tie (ending in 5). */
    @Test fun randomValuesAndTiesUpTo15Digits() {
        val rnd = Random(263)
        var hi = 10L
        repeat(15) {
            repeat(20_000) {
                val ms = rnd.nextLong(hi / 10, hi)
                check(ms); check(-ms)
                val tie = ms - ms % 10 + 5
                check(tie); check(-tie)
            }
            hi *= 10
        }
        assertNone()
    }
}
