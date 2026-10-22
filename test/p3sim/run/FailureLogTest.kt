package com.engineerclient.p3sim.run

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FailureLogTest {
    @Test fun firstFailureIsReportedAndTheRestAreCounted() {
        var now = 0L
        val log = FailureLog(1000) { now }
        assertEquals(0, log.admit("a"))
        repeat(5) { now += 50; assertNull(log.admit("a")) }
        assertEquals(0, log.admit("b"), "another kind is its own")
        now = 1500
        assertEquals(5, log.admit("a"), "the count of what was held back comes with the next report")
        assertNull(log.admit("a"))
        assertEquals(" (5 more since the last report)", log.suffix(5))
        assertEquals("", log.suffix(0))
    }
}
