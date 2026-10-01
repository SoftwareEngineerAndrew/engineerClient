package com.engineerclient.misc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompletionDingTest {
    private val P = 4.047619f

    @Test
    fun `terminal dings are muted, other sounds and plings left alone`() {
        val d = CompletionDing()
        assertTrue(d.sound(true, 8f, P, 0))
        assertFalse(d.sound(true, 1f, 1f, 10))   // an ordinary pling (a mod's, a note block)
        assertFalse(d.sound(true, 8f, 2f, 20))
        assertFalse(d.sound(false, 8f, P, 30))   // another sound at the same volume and pitch
    }

    @Test
    fun `gate line after its ding - the held ding is played`() {
        val d = CompletionDing()
        assertTrue(d.sound(true, 8f, P, 1000))
        val replay = d.keptMessage(1003)
        assertNotNull(replay); assertEquals(P, replay.pitch)
    }

    @Test
    fun `gate line before its ding - the ding goes through, once`() {
        val d = CompletionDing()
        assertNull(d.keptMessage(1000))
        assertFalse(d.sound(true, 8f, P, 1005))
        assertTrue(d.sound(true, 8f, P, 1010)) // the next one is a terminal's again
    }

    @Test
    fun `a terminal ding long before the gate line isn't replayed`() {
        val d = CompletionDing()
        assertTrue(d.sound(true, 8f, P, 0))
        assertNull(d.keptMessage(5000))
        assertFalse(d.sound(true, 8f, P, 5001))
    }
}
