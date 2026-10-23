package com.coffeeclient.misc

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NecronMuteTest {
    @Test
    fun opensOnEitherFirstLine() {
        for (line in NecronMute.START_LINES) {
            val m = NecronMute()
            m.chat(line)
            assertTrue(m.open, line)
        }
    }

    @Test
    fun nothingBeforeNecronOpensIt() {
        val m = NecronMute()
        for (line in listOf("[BOSS] Goldor: Who dares trespass into my domain?", "[BOSS] Goldor: ....", "The Core entrance is opening!", "[BOSS] Necron: ARGH!"))
            m.chat(line)
        assertFalse(m.open)
    }

    @Test
    fun closesOnEveryEnd() {
        for (end in listOf("[BOSS] Necron: All this, for nothing...", "[BOSS] Wither King: You... again?", "                             > EXTRA STATS <")) {
            val m = NecronMute()
            m.chat("[BOSS] Necron: You went further than any human before, congratulations.")
            m.chat(end)
            assertFalse(m.open, end)
        }
        val m = NecronMute()
        m.chat("[BOSS] Necron: You went further than any human before, congratulations.")
        m.reset()
        assertFalse(m.open)
    }
}
