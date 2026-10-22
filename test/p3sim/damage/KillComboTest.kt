package com.engineerclient.p3sim.clear.damage

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KillComboTest {
    private val me = UUID(1, 1)

    @Test
    fun tiersComeAtTheirCounts() {
        val c = KillCombo()
        val lines = (1..30).mapNotNull { c.kill(me, it) }
        assertEquals(6, lines.size)
        assertEquals("§a§l+5 Kill Combo §r§b+3%  Magic Find", lines[0])
        assertEquals("§6§l+30 Kill Combo §r§8+§r§610 §r§7coins per kill", lines[5])
    }

    @Test
    fun expiresSixtyTicksAfterTheLastKill() {
        val c = KillCombo()
        for (t in 0 until 7) c.kill(me, t * 10)
        assertTrue(c.expire(60 + 59).isEmpty())
        assertEquals(listOf(me to "§cYour Kill Combo has expired! You reached a 7 Kill Combo!"), c.expire(60 + 60))
        assertTrue(c.expire(500).isEmpty())
    }

    @Test
    fun aShortComboEndsWithoutALineAndALateKillStartsAgain() {
        val c = KillCombo()
        repeat(4) { c.kill(me, 0) }
        assertEquals(listOf(me to null), c.expire(60))
        repeat(4) { assertNull(c.kill(me, 100)) }
        assertEquals("§a§l+5 Kill Combo §r§b+3%  Magic Find", c.kill(me, 100))
    }
}
