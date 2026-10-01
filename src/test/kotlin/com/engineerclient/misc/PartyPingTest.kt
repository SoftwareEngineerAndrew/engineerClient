package com.engineerclient.misc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyPingTest {
    @Test
    fun `a party line's ping is dropped`() {
        val p = PartyPing()
        assertTrue(p.sound(true, 1.0, 2.0, 3.0, 1000))
        assertNull(p.chat(true, 1001))
        assertNull(p.expired(2000))
    }

    @Test
    fun `a guild or private line's ping is played`() {
        val p = PartyPing()
        p.sound(true, 1.0, 2.0, 3.0, 1000)
        assertEquals(2.0, assertNotNull(p.chat(false, 1001)).y)
    }

    @Test
    fun `a ping nothing follows is played once the wait is over`() {
        val p = PartyPing()
        p.sound(true, 0.0, 0.0, 0.0, 1000)
        assertNull(p.expired(1100))
        assertNotNull(p.expired(1200))
        assertNull(p.expired(1300))
    }

    @Test
    fun `other sounds and lines are left alone`() {
        val p = PartyPing()
        assertFalse(p.sound(false, 0.0, 0.0, 0.0, 0))
        assertNull(p.chat(true, 1))
        assertTrue(PartyPing.isPartyLine("Party > [MVP+] someone: hi"))
        assertFalse(PartyPing.isPartyLine("Guild > someone: hi"))
    }
}
