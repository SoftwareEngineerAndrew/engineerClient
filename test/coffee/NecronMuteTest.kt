package com.coffeeclient.misc

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NecronMuteTest {
    @Test
    fun opensOnGoldorDyingOrAnyNecronLine() {
        for (line in listOf("[BOSS] Goldor: ....", "[BOSS] Necron: You went further than any human before, congratulations.", "[BOSS] Necron: ARGH!")) {
            val m = NecronMute()
            m.chat(line)
            assertTrue(m.open, line)
        }
    }

    @Test
    fun nothingBeforeItOpensIt() {
        val m = NecronMute()
        for (line in listOf("[BOSS] Goldor: Who dares trespass into my domain?", "The Core entrance is opening!", "[BOSS] Storm: Pathetic Maxor, just like expected."))
            m.chat(line)
        assertFalse(m.open)
    }

    @Test
    fun overUntilANewWorld() {
        for (end in listOf("[BOSS] Necron: All this, for nothing...", "                             > EXTRA STATS <")) {
            val m = NecronMute()
            m.chat("[BOSS] Goldor: ....")
            m.chat(end)
            assertFalse(m.open, end)
            assertTrue(m.over, end)
            // A Necron line after the end doesn't open it again.
            m.chat("[BOSS] Necron: ARGH!")
            assertFalse(m.open, end)
            m.reset()
            assertFalse(m.over, end)
        }
    }
}
