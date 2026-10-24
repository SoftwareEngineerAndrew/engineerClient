package com.engineerclient.misc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoreboardLinesTest {
    private val regex = Regex("§.")

    @Test fun stripsWhatTheRegexStrips() {
        for (s in listOf(
            "", "plain", "§j", "Early Summer 19§wth", "The Catac§uombs (F7)", "§a§lBold§r end", "§§a", "trailing §",
            "§\nline", "§\r\n", "§\u2028x", "§\uD83D\uDE00after", "§\uD83Dlone", "no codes at all", "§", "a§b§c§",
        )) assertEquals(s.replace(regex, ""), ScoreboardLines.stripFormatting(s), "for \"$s\"")
    }

    @Test fun saltedLinesStillMatch() {
        assertTrue(ScoreboardLines.hides(ScoreboardLines.stripFormatting("Early Summer 19§wth").trim()))
        assertTrue(ScoreboardLines.hides(ScoreboardLines.stripFormatting("§j").trim()))
        assertFalse(ScoreboardLines.hides("Purse: 1,234"))
        // A second look at the same text comes from the cache and says the same.
        assertTrue(ScoreboardLines.hides("Early Summer 19th"))
        assertFalse(ScoreboardLines.hides("Purse: 1,234"))
    }

    @Test fun objectiveTaskDependsOnTheLineAbove() {
        assertEquals(ScoreboardLines.Kind.OBJECTIVE_TASK, ScoreboardLines.kindOf("Talk to Enid", ScoreboardLines.Kind.OBJECTIVE))
        assertEquals(null, ScoreboardLines.kindOf("Talk to Enid", null))
    }
}
