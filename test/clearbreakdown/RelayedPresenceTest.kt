package com.engineerclient.clearbreakdown

import kotlin.test.Test
import kotlin.test.assertEquals

class RelayedPresenceTest {
    private val r = RelayedPresence(staleMs = 10_000)

    @Test fun seenRoomIsKnown() {
        r.update("Bob", "Cat", "k1", 0)
        assertEquals("k1", r.roomOf("Cat", 5_000))
    }

    @Test fun noRoomIsEmptyNotUnknown() {
        r.update("Bob", "Cat", "", 0)
        assertEquals("", r.roomOf("Cat", 0))
    }

    @Test fun noLongerSeenForgets() {
        r.update("Bob", "Cat", "k1", 0); r.update("Bob", "Cat", null, 100)
        assertEquals(null, r.roomOf("Cat", 200))
    }

    @Test fun silentSenderGoesStale() {
        r.update("Bob", "Cat", "k1", 0)
        assertEquals(null, r.roomOf("Cat", 10_001))
    }

    @Test fun freshestSenderWins() {
        r.update("Bob", "Cat", "k1", 0); r.update("Dan", "Cat", "k2", 3_000)
        assertEquals("k2", r.roomOf("Cat", 4_000))
    }

    @Test fun unknownPlayerIsNull() {
        assertEquals(null, r.roomOf("Cat", 0))
    }
}
