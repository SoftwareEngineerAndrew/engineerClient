package com.engineerclient.p3sim.run

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The messages between the sim's server and the game for the mods that read Hypixel's mod API (HypixelWire). Synthetic ids only. */
class HypixelWireTest {
    @Test fun serverNamesLookLikeADungeonServers() {
        val pattern = Regex("mini[0-9]{1,3}[A-Z]{1,2}")
        val random = Random(7)
        val seen = HashSet<String>()
        repeat(500) { val n = HypixelWire.serverName(random); assertTrue(pattern.matches(n), n); seen += n }
        assertTrue(seen.size > 300, "names should vary")
        assertEquals(HypixelWire.serverName(Random(3)), HypixelWire.serverName(Random(3)), "same seed, same name")
    }

    @Test fun partyAnswersComeZeroToThreeTicksLaterMostlyAtOnce() {
        val random = Random(11)
        val counts = IntArray(4)
        repeat(20_000) { counts[HypixelWire.partyDelayTicks(random)]++ }
        // Measured over 56 pairs: 37, 10, 7, 2 (66 %, 18 %, 12.5 %, 3.6 %).
        assertEquals(0.66, counts[0] / 20_000.0, 0.02)
        assertEquals(0.18, counts[1] / 20_000.0, 0.02)
        assertEquals(0.125, counts[2] / 20_000.0, 0.02)
        assertEquals(0.036, counts[3] / 20_000.0, 0.01)
    }

    @Test fun theHostLeadsAndTheBotsFollow() {
        val host = UUID(0, 1); val joiner = UUID(0, 2); val b1 = UUID(0, 3); val b2 = UUID(0, 4)
        val members = HypixelWire.partyMembers(listOf(host, joiner), listOf(b1, b2, host))
        assertEquals(listOf(host, joiner, b1, b2), members.map { it.uuid })
        assertEquals(listOf(true, false, false, false), members.map { it.leader })
    }

    @Test fun messagesRoundTrip() {
        assertEquals("mini7B", HypixelWire.decodeLocation(HypixelWire.encodeLocation("mini7B")))
        val members = listOf(HypixelWire.Member(UUID(5, 6), true), HypixelWire.Member(UUID(7, 8), false))
        assertEquals(members, HypixelWire.decodeParty(HypixelWire.encodeParty(members)))
    }

    @Test fun badMessagesAreNothing() {
        assertNull(HypixelWire.decodeLocation("not json"))
        assertNull(HypixelWire.decodeLocation("{}"))
        assertNull(HypixelWire.decodeLocation("""{"server":""}"""))
        assertTrue(HypixelWire.decodeParty("[1,2]").isEmpty())
        assertEquals(1, HypixelWire.decodeParty("""{"members":[{"id":"nope"},{"id":"${UUID(1, 2)}"}]}""").size)
    }

    @Test fun thePartyKeepAliveClockMatchesTheMeasuredPattern() {
        // First keep-alive 14 ticks after the login, then every 11 (275 of 275 measured gaps).
        assertEquals(14, HypixelModApi.KEEP_ALIVE_FIRST)
        assertEquals(11, HypixelModApi.KEEP_ALIVE_EVERY)
    }
}
