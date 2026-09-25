package com.engineerclient.misc

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sidebar line hider's matching.
 *
 * The lines in `realDump` came off a real Hypixel sidebar and are the ones that matter. The rest
 * are still written from memory — they will tell you if a pattern changes behaviour, but they can
 * pass while matching the wrong thing, so correct them from a dump when one turns up.
 */
class ScoreboardLinesTest {

    @BeforeTest
    @AfterTest
    fun reset() {
        ScoreboardLines.hideLines = false
        ScoreboardLines.customPatterns = ""
    }

    /** Lines that must survive: the sidebar would be pointless if these went too. */
    private val keep = listOf(
        "Coins: 1,234,567",
        "Bits: 0",
        "Team Score: 305 (S+)",
        "undonecoffee",
        "Cleared by: someone",
    )

    private fun assertKeepsTheRest() {
        for (line in keep) assertFalse(ScoreboardLines.hides(line), "should not have hidden: $line")
    }

    @Test
    fun `date and time lines`() {
        assertTrue(ScoreboardLines.hides("11/12/23 m1CK"))
        // The clock line leads with a sun or moon glyph, so the pattern cannot be anchored on a digit.
        assertTrue(ScoreboardLines.hides("☀ 12:10pm"))
        assertTrue(ScoreboardLines.hides("☽ 4:20am"))
        assertKeepsTheRest()
    }

    @Test
    fun `season lines`() {
        assertTrue(ScoreboardLines.hides("Late Summer 13th"))
        assertTrue(ScoreboardLines.hides("Early Winter 1st"))
        assertTrue(ScoreboardLines.hides("Spring 22nd"))
        assertKeepsTheRest()
    }

    @Test
    fun `dungeon counters`() {
        assertTrue(ScoreboardLines.hides("Keys: ✗ 1"))
        assertTrue(ScoreboardLines.hides("Cleared: 42% (180)"))
        assertTrue(ScoreboardLines.hides("Dungeon Cleared: 7%"))
        assertKeepsTheRest()
    }

    /**
     * Straight off a real sidebar. Hypixel salts each line with a § and a letter so that no two
     * are identical, and it lands mid-word — which is exactly what defeated the first version of
     * these patterns.
     */
    @Test
    fun `the real sidebar's salted lines are matched`() {
        assertTrue(ScoreboardLines.hidesRaw("Early Summer 19\u00a7wth"), "the season, salted between the number and its suffix")
        assertTrue(ScoreboardLines.hidesRaw(" The Catac\u00a7uombs (F7)"), "the location, salted inside the word")
        assertTrue(ScoreboardLines.hidesRaw("\u00a7j"), "a spacer that is nothing but salt")
        // The salt must not make everything vanish.
        assertFalse(ScoreboardLines.hidesRaw("Coins: \u00a7a1,234,567"))
    }

    @Test
    fun `the header, the location and blank lines go too`() {
        assertTrue(ScoreboardLines.hides("SKYBLOCK"))
        assertTrue(ScoreboardLines.hides("SKYBLOCK CO-OP"))
        assertTrue(ScoreboardLines.hides("⏣ The Catacombs (F7)"))
        assertTrue(ScoreboardLines.hides(""))
        assertTrue(ScoreboardLines.hides("   "))
    }

    @Test
    fun `a piece of a line is enough for a custom pattern`() {
        ScoreboardLines.customPatterns = "Bits:"
        assertTrue(ScoreboardLines.hides("Bits: 0"))
        assertFalse(ScoreboardLines.hides("Coins: 1,234,567"))
    }

    @Test
    fun `custom patterns can be regexes, and several at once`() {
        ScoreboardLines.customPatterns = "^Team Score; Bits:"
        assertTrue(ScoreboardLines.hides("Team Score: 305 (S+)"))
        assertTrue(ScoreboardLines.hides("Bits: 0"))
        assertFalse(ScoreboardLines.hides("Not a Team Score line"))
    }

    @Test
    fun `a custom pattern that is not valid regex is still matched as text`() {
        // Hypixel's lines are full of brackets, so someone pasting one straight out of the dump
        // hands us something that does not compile as a regex.
        ScoreboardLines.customPatterns = "(S+"
        assertTrue(ScoreboardLines.hides("Team Score: 305 (S+)"))
        assertFalse(ScoreboardLines.hides("Coins: 1,234,567"))
    }

    @Test
    fun `blank custom patterns hide nothing extra`() {
        ScoreboardLines.customPatterns = "  ;  ; "
        for (line in keep) assertFalse(ScoreboardLines.hides(line))
    }

    @Test
    fun `the master switch is what the mixin asks`() {
        ScoreboardLines.hideLines = false
        // hides() is the matcher; whether the module is on is checked by shouldHide() at the render
        // call, so a line still "matches" here — this pins that split so it is not silently inverted.
        assertTrue(ScoreboardLines.hides("Late Summer 13th"))
    }
}
