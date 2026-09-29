package com.engineerclient.misc

import com.engineerclient.misc.ScoreboardLines.Kind
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every line here is real, from the scoreboard recorder (salt already stripped unless noted). */
class ScoreboardLinesTest {

    @BeforeTest fun defaults() {
        ScoreboardLines.customPatterns = ""
        ScoreboardLines.hideCatacombsLocation = false
        ScoreboardLines.hideTimeElapsed = true
        ScoreboardLines.hideCleared = true
    }
    @AfterTest fun reset() = defaults()

    @Test fun `every recorded kind is recognised`() {
        val lines = mapOf(
            "09/28/26 m33CJ" to Kind.DATE, "09/28/26  L25H" to Kind.DATE, "09/28/26 m70C 1-2" to Kind.DATE,
            "Early Spring 17th" to Kind.SEASON, "8:30am" to Kind.CLOCK, "4:40am ☽" to Kind.CLOCK, "10:50am ☀" to Kind.CLOCK,
            " The Catacombs (F7)" to Kind.LOCATION_CATACOMBS, " The Catacombs (M3)" to Kind.LOCATION_CATACOMBS,
            " The Catacombs (E)" to Kind.LOCATION_CATACOMBS, " Dungeon Hub" to Kind.LOCATION_OTHER,
            " Village" to Kind.LOCATION_OTHER, " None" to Kind.LOCATION_OTHER,
            "Purse: 478,376,408" to Kind.PURSE, "Purse: 478,376,413 (+5)" to Kind.PURSE, "Bits: 188,376 (+609)" to Kind.BITS,
            "Objective ➡" to Kind.OBJECTIVE, "Objective" to Kind.OBJECTIVE, "Hype: 200/200" to Kind.HYPE,
            "under heavy development!" to Kind.PROTOTYPE_LOBBY, "www.hypixel.net" to Kind.FOOTER,
            "Auto-closing in: 1:55" to Kind.STARTING, "Starting in: 0:05" to Kind.STARTING,
            "[M] sanguchete [Lv33]" to Kind.TEAMMATE_LOBBY, "[B] Shadowhunter101 [Lv3" to Kind.TEAMMATE_LOBBY,
            "[M] sanguchete 5,531❤" to Kind.TEAMMATE_RUN, "[A] RedRosie989 11,466" to Kind.TEAMMATE_RUN,
            "Solo" to Kind.SOLO, "Time Elapsed: 01s" to Kind.TIME_ELAPSED, "Time Elapsed: 1m 12s" to Kind.TIME_ELAPSED,
            "Keys: ■ ✗ ■ 0x" to Kind.KEYS, "Cleared: 11% (0)" to Kind.CLEARED, "" to Kind.BLANK,
        )
        for ((line, kind) in lines) assertEquals(kind, ScoreboardLines.kindOf(line, null), "kind of \"$line\"")
    }

    @Test fun `the page's choices`() {
        for (keep in listOf("Purse: 478,376,408", "Bits: 187,767", "[M] sanguchete [Lv33]", "[H] Miximum 9,363❤", " The Catacombs (F7)"))
            assertFalse(ScoreboardLines.hides(keep), "should stay: $keep")
        for (gone in listOf("09/28/26 m33CJ", "Early Spring 17th", "8:30am", " Village", "Objective ➡", "Hype: 200/200",
                            "www.hypixel.net", "Starting in: 0:05", "Solo", "Keys: ■ ✗ ■ 0x", "Time Elapsed: 09s", "Cleared: 3% (0)", ""))
            assertTrue(ScoreboardLines.hides(gone), "should hide: $gone")
    }

    @Test fun `the three settings`() {
        ScoreboardLines.hideCatacombsLocation = true; ScoreboardLines.hideTimeElapsed = false; ScoreboardLines.hideCleared = false
        assertTrue(ScoreboardLines.hides(" The Catacombs (M7)"))
        assertFalse(ScoreboardLines.hides("Time Elapsed: 09s"))
        assertFalse(ScoreboardLines.hides("Cleared: 3% (0)"))
    }

    @Test fun `the objective task is known by the line above it`() {
        assertEquals(Kind.OBJECTIVE_TASK, ScoreboardLines.kindOf("Talk to Enid", Kind.OBJECTIVE))
        assertTrue(ScoreboardLines.hides("Talk to Enid", Kind.OBJECTIVE))
        assertFalse(ScoreboardLines.hides("Talk to Enid", null), "free text is only hidden under Objective")
    }

    @Test fun `unknown lines stay`() {
        for (line in listOf("Team Score: 305 (S+)", "Coins: 1,234,567", "undonecoffee", "[M] someone DEAD"))
            assertFalse(ScoreboardLines.hides(line), "unknown should stay: $line")
    }

    @Test fun `salted lines as Hypixel sends them`() {
        assertTrue(ScoreboardLines.hidesRaw("Early Summer 19§wth"))
        assertTrue(ScoreboardLines.hidesRaw("§j"), "a spacer that is nothing but salt")
        assertFalse(ScoreboardLines.hidesRaw("Purse: §6478,376§j§6,408"))
    }

    @Test fun `custom patterns still work`() {
        ScoreboardLines.customPatterns = "Bits:"
        assertTrue(ScoreboardLines.hides("Bits: 0"))
        assertFalse(ScoreboardLines.hides("Purse: 1"))
    }
}
