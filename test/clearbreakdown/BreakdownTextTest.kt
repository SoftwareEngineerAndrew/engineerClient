package com.engineerclient.clearbreakdown

import kotlin.test.Test
import kotlin.test.assertEquals

class BreakdownTextTest {
    private fun strip(s: String) = s.replace(Regex("§."), "")

    @Test fun headline() {
        val p = BreakdownText.line("Andrew", 'b', listOf(Clear("A", true), Clear("B", false)), PlayerSecrets(12, emptyList(), 12))
        assertEquals("Andrew: ", strip(p.name))
        assertEquals("1-2", strip(p.rooms))
        assertEquals("12 secrets", strip(p.secrets))
    }

    @Test fun roomsHoverInOrderMarksSolo() {
        assertEquals(listOf("1. Mirror (solo)", "2. Cell"),
            strip(BreakdownText.line("A", 'f', listOf(Clear("Mirror", true), Clear("Cell", false)), null).roomsHover).lines())
    }

    @Test fun noClears() {
        assertEquals("No rooms cleared", strip(BreakdownText.line("A", 'f', emptyList(), null).roomsHover))
    }

    @Test fun secretsHover() {
        assertEquals(listOf("4 Prison Cell", "~2 Mirror", "3 in unknown rooms"), strip(BreakdownText.line("A", 'f', emptyList(),
            PlayerSecrets(9, listOf(RoomSecrets("Prison Cell", 4, false), RoomSecrets("Mirror", 2, true)), 3)).secretsHover).lines())
    }

    @Test fun apiFailedIsPartial() {
        val p = BreakdownText.line("A", 'f', emptyList(), PlayerSecrets(null, listOf(RoomSecrets("Mirror", 2, false)), 0))
        assertEquals("? secrets", strip(p.secrets))
        assertEquals(listOf("Hypixel API didn't answer; seen in rooms:", "2 Mirror"), strip(p.secretsHover).lines())
    }

    @Test fun noSecrets() {
        assertEquals("No secrets", strip(BreakdownText.line("A", 'f', emptyList(), PlayerSecrets(0, emptyList(), 0)).secretsHover))
    }
}
