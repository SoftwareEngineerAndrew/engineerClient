package com.engineerclient.clearbreakdown

import kotlin.test.Test
import kotlin.test.assertEquals

class ClearCreditTest {
    private val c = ClearCredit()

    private fun ClearCredit.ticks(n: Int, vararg p: Pair<String, String?>) = repeat(n) { tick(mapOf(*p)) }

    @Test fun soloClear() {
        c.ticks(100, "A" to "r1"); c.onCheckmark("r1", "Mirror", Mark.WHITE)
        assertEquals(mapOf("A" to listOf(Clear("Mirror", true))), c.clears())
    }

    @Test fun leaverAndFinisherBothCreditedNeitherSolo() {
        c.ticks(200, "B" to "r1"); c.ticks(5, "B" to null); c.ticks(100, "A" to "r1"); c.onCheckmark("r1", "Mirror", Mark.WHITE)
        assertEquals(listOf(Clear("Mirror", false)), c.clears()["A"])
        assertEquals(listOf(Clear("Mirror", false)), c.clears()["B"])
    }

    @Test fun runThroughUnderTwoSecondsDoesNotCount() {
        c.ticks(40, "B" to "r1"); c.ticks(100, "A" to "r1", "B" to null); c.onCheckmark("r1", "Mirror", Mark.WHITE)
        assertEquals(null, c.clears()["B"])
        assertEquals(true, c.clears()["A"]!!.single().solo)
    }

    @Test fun timeIsCumulativeAcrossVisits() {
        c.ticks(30, "B" to "r1"); c.ticks(10, "B" to null); c.ticks(30, "B" to "r1"); c.ticks(1, "B" to null)
        c.ticks(5, "A" to "r1"); c.onCheckmark("r1", "Mirror", Mark.WHITE)
        assertEquals(listOf(Clear("Mirror", false)), c.clears()["B"])
    }

    @Test fun presentAtCheckmarkAlwaysHelps() {
        c.ticks(3, "A" to "r1"); c.onCheckmark("r1", "Mirror", Mark.WHITE)
        assertEquals(listOf(Clear("Mirror", true)), c.clears()["A"])
    }

    @Test fun whiteThenGreenCreditedOnce() {
        c.ticks(50, "A" to "r1"); c.onCheckmark("r1", "M", Mark.WHITE); c.onCheckmark("r1", "M", Mark.GREEN)
        assertEquals(1, c.clears()["A"]!!.size)
    }

    @Test fun redNeverCreditsAndRestartsTime() {
        c.ticks(100, "B" to "r1"); c.onCheckmark("r1", "Water Board", Mark.RED); c.ticks(1, "B" to null)
        assertEquals(emptyMap(), c.clears())
        c.ticks(60, "A" to "r1"); c.onCheckmark("r1", "Water Board", Mark.WHITE)
        assertEquals(null, c.clears()["B"])
        assertEquals(listOf(Clear("Water Board", true)), c.clears()["A"])
    }

    @Test fun orderIsCheckmarkOrder() {
        c.ticks(50, "A" to "r2"); c.ticks(50, "A" to "r1")
        c.onCheckmark("r1", "One", Mark.WHITE); c.onCheckmark("r2", "Two", Mark.GREEN)
        assertEquals(listOf("One", "Two"), c.clears()["A"]!!.map { it.room })
    }

    @Test fun timeInClearedRoomDoesNothing() {
        c.ticks(50, "A" to "r1"); c.onCheckmark("r1", "One", Mark.WHITE); c.ticks(100, "B" to "r1")
        c.onCheckmark("r1", "One", Mark.GREEN)
        assertEquals(null, c.clears()["B"])
    }

    @Test fun otherMarksIgnored() {
        c.ticks(50, "A" to "r1"); c.onCheckmark("r1", "One", Mark.OTHER)
        assertEquals(emptyMap(), c.clears())
    }
}
