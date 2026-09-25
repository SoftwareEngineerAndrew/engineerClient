package com.engineerclient.misc

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sidebar line hider's matching, over plain text.
 *
 * READ THIS BEFORE TRUSTING IT: the sample lines below are what Hypixel's sidebar is *believed* to
 * look like, written from memory rather than captured from the game. Nobody has run "Dump
 * Scoreboard" in-game since the dump was fixed. So this file pins the patterns to an assumption,
 * not to reality — it will tell you if a pattern changes behaviour, and it will happily pass while
 * matching the wrong thing.
 *
 * When a real dump exists: replace these samples with the real lines, and any test that then fails
 * is a pattern in ScoreboardLines that needs correcting — that is the point of the exercise.
 */
class ScoreboardLinesTest {

    @BeforeTest
    @AfterTest
    fun reset() {
        ScoreboardLines.hideLines = false
        ScoreboardLines.hideDateTime = false
        ScoreboardLines.hideSeason = false
        ScoreboardLines.hideKeys = false
        ScoreboardLines.hideCleared = false
        ScoreboardLines.customPatterns = ""
    }

    /** Lines that must never be hidden by the built-in patterns, whatever is switched on. */
    private val keep = listOf(
        "⏣ The Catacombs (F7)",
        "Coins: 1,234,567",
        "Bits: 0",
        "SKYBLOCK CO-OP",
        "Team Score: 305 (S+)",
        "undonecoffee",
        "Cleared by: someone",
    )

    private fun assertKeepsTheRest() {
        for (line in keep) assertFalse(ScoreboardLines.hides(line), "should not have hidden: $line")
    }

    @Test
    fun `date and time lines`() {
        ScoreboardLines.hideDateTime = true
        assertTrue(ScoreboardLines.hides("11/12/23 m1CK"))
        // The clock line leads with a sun or moon glyph, so the pattern cannot be anchored on a digit.
        assertTrue(ScoreboardLines.hides("☀ 12:10pm"))
        assertTrue(ScoreboardLines.hides("☽ 4:20am"))
        assertKeepsTheRest()
        assertFalse(ScoreboardLines.hides("Late Summer 13th"))
    }

    @Test
    fun `season lines`() {
        ScoreboardLines.hideSeason = true
        assertTrue(ScoreboardLines.hides("Late Summer 13th"))
        assertTrue(ScoreboardLines.hides("Early Winter 1st"))
        assertTrue(ScoreboardLines.hides("Spring 22nd"))
        assertKeepsTheRest()
        assertFalse(ScoreboardLines.hides("11/12/23 m1CK"))
    }

    @Test
    fun `dungeon counters`() {
        ScoreboardLines.hideKeys = true
        ScoreboardLines.hideCleared = true
        assertTrue(ScoreboardLines.hides("Keys: ✗ 1"))
        assertTrue(ScoreboardLines.hides("Cleared: 42% (180)"))
        assertTrue(ScoreboardLines.hides("Dungeon Cleared: 7%"))
        assertKeepsTheRest()
    }

    @Test
    fun `each toggle only hides its own lines`() {
        ScoreboardLines.hideSeason = true
        assertFalse(ScoreboardLines.hides("Keys: ✗ 1"))
        assertFalse(ScoreboardLines.hides("Cleared: 42% (180)"))
        assertFalse(ScoreboardLines.hides("☀ 12:10pm"))
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
        ScoreboardLines.customPatterns = "(F7)"
        assertTrue(ScoreboardLines.hides("⏣ The Catacombs (F7)"))
        assertFalse(ScoreboardLines.hides("Coins: 1,234,567"))
    }

    @Test
    fun `blank and empty patterns hide nothing`() {
        ScoreboardLines.customPatterns = "  ;  ; "
        for (line in keep) assertFalse(ScoreboardLines.hides(line))
        assertFalse(ScoreboardLines.hides(""))
    }

    @Test
    fun `the master switch is what the mixin asks`() {
        ScoreboardLines.hideSeason = true
        ScoreboardLines.hideLines = false
        // hides() is the matcher; the master switch is checked by shouldHide() at the render call,
        // so a line still "matches" here — this pins that split so it is not silently inverted.
        assertTrue(ScoreboardLines.hides("Late Summer 13th"))
    }
}
