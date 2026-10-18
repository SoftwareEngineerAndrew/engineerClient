package com.engineerclient.clearbreakdown

import kotlin.test.Test
import kotlin.test.assertEquals

class RunGateTest {
    private val g = RunGate()
    private val end = "                         The Catacombs - Floor VII"
    private val defeated = "               ☠ Defeated Maxor, Storm, Goldor, and Necron in 05m 31s"

    @Test fun endLineThenDefeatedFiresOnDefeated() {
        assertEquals(false, g.line(end))
        assertEquals(false, g.line("                 Team Score: 317 (S+)"))
        assertEquals(true, g.line(defeated))
    }

    @Test fun defeatedThenEndLineFiresOnEndLine() {
        assertEquals(false, g.line(defeated))
        assertEquals(true, g.line("                    Master Mode The Catacombs - Floor VII"))
    }

    @Test fun endLineAloneNeverFires() {
        listOf(end, "                 Team Score: 120 (D)", "> EXTRA STATS <").forEach { assertEquals(false, g.line(it)) }
    }

    @Test fun firesOnce() {
        g.line(end); g.line(defeated)
        assertEquals(false, g.line(defeated))
        assertEquals(false, g.line(end))
    }

    @Test fun statsHeaderIsNotTheEndLine() {
        g.line(defeated)
        assertEquals(false, g.line("            The Catacombs - Floor VII Stats"))
    }

    @Test fun resetStartsOver() {
        g.line(defeated); g.reset()
        assertEquals(false, g.line(end))
    }
}
