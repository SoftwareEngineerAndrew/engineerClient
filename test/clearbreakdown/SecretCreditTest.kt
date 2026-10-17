package com.engineerclient.clearbreakdown

import kotlin.test.Test
import kotlin.test.assertEquals

class SecretCreditTest {
    private val s = SecretCredit()

    // Direct sightings

    @Test fun exactSightingIsCertain() {
        s.sighting("item:1", "r1", "Mirror", setOf("A"), true, 0)
        assertEquals(PlayerSecrets(1, listOf(RoomSecrets("Mirror", 1, false)), 0), s.resolve(mapOf("A" to 1, "B" to 0))["A"])
    }

    @Test fun proximityTieSettledByElimination() {
        s.sighting("chest:1,2,3", "r1", "Mirror", setOf("A", "B"), false, 0)
        assertEquals(listOf(RoomSecrets("Mirror", 1, false)), s.resolve(mapOf("A" to 0, "B" to 1))["B"]!!.rooms)
    }

    @Test fun exactReplacesProximity() {
        s.sighting("chest:1", "r1", "M", setOf("A", "B"), false, 0)
        s.sighting("chest:1", "r1", "M", setOf("A"), true, 2000)
        assertEquals(listOf(RoomSecrets("M", 1, false)), s.resolve(mapOf("A" to 5, "B" to 5))["A"]!!.rooms)
    }

    @Test fun laterExactSightingIsAReopenNotTheFinder() {
        s.sighting("chest:1", "r1", "M", setOf("A"), false, 0)
        s.sighting("chest:1", "r1", "M", setOf("B"), true, 30_000)
        val r = s.resolve(mapOf("A" to 5, "B" to 5))
        assertEquals(1, r["A"]!!.rooms.single().count)
        assertEquals(emptyList(), r["B"]!!.rooms)
    }

    @Test fun laterProximitySightingOfSameKeyIgnored() {
        s.sighting("chest:1", "r1", "M", setOf("A"), false, 0)
        s.sighting("chest:1", "r1", "M", setOf("B"), false, 5000)
        val r = s.resolve(mapOf("A" to 5, "B" to 5))
        assertEquals(1, r["A"]!!.rooms.single().count)
        assertEquals(emptyList(), r["B"]!!.rooms)
    }

    @Test fun sameMomentProximitySightingsUnion() {
        s.sighting("chest:1", "r1", "M", setOf("A"), false, 0)
        s.sighting("chest:1", "r1", "M", setOf("B"), false, 400)
        val r = s.resolve(mapOf("A" to 0, "B" to 1))
        assertEquals(1, r["B"]!!.rooms.single().count)
    }

    @Test fun emptyCandidatesCreditNobody() {
        s.sighting("bat:1", "r1", "M", emptySet(), false, 0)
        assertEquals(emptyList(), s.resolve(mapOf("A" to 1))["A"]!!.rooms)
    }

    // Odin gap batches

    @Test fun riseExplainedByDirectEventsMakesNoGap() {
        s.tick(mapOf("A" to "r1", "B" to "r1"))
        s.sighting("item:1", "r1", "M", setOf("A"), true, 0); s.observe("r1", "M", 1)
        assertEquals(emptyList(), s.resolve(mapOf("A" to 1, "B" to 9))["B"]!!.rooms)
    }

    @Test fun riseBeyondDirectEventsIsAGapForEveryoneSinceLastCount() {
        s.tick(mapOf("B" to "r1")); s.tick(mapOf("B" to null)); s.tick(mapOf("A" to "r1"))
        s.sighting("item:1", "r1", "One", setOf("A"), true, 0); s.observe("r1", "One", 6)
        val r = s.resolve(mapOf("A" to 1, "B" to 5))
        assertEquals(listOf(RoomSecrets("One", 1, false)), r["A"]!!.rooms)
        assertEquals(listOf(RoomSecrets("One", 5, false)), r["B"]!!.rooms)
    }

    @Test fun leftBeforePreviousCountIsNotAGapCandidate() {
        s.tick(mapOf("B" to "r1")); s.tick(mapOf("B" to null, "A" to "r1")); s.observe("r1", "One", 1)
        s.tick(mapOf("A" to "r1")); s.observe("r1", "One", 2)
        assertEquals(listOf(RoomSecrets("One", 2, false)), s.resolve(mapOf("A" to 2, "B" to 0))["A"]!!.rooms)
    }

    @Test fun lowerOrEqualCountIgnored() {
        s.tick(mapOf("A" to "r1")); s.observe("r1", "One", 3); s.observe("r1", "One", 2); s.observe("r1", "One", 3)
        assertEquals(3, s.resolve(mapOf("A" to 3))["A"]!!.rooms.sumOf { it.count })
    }

    @Test fun lateRelayedSightingTakesTheGapsPlace() {
        s.tick(mapOf("A" to "r1", "B" to "r1"))
        s.observe("r1", "One", 1, atMs = 10_000)
        s.sighting("item:1", "r1", "One", setOf("B"), true, 10_400)
        val r = s.resolve(mapOf("A" to 9, "B" to 9))
        assertEquals(listOf(RoomSecrets("One", 1, false)), r["B"]!!.rooms)
        assertEquals(emptyList(), r["A"]!!.rooms)
    }

    @Test fun gapWithNobodyCreditsNobody() {
        s.observe("r1", "One", 3)
        assertEquals(emptyList(), s.resolve(mapOf("A" to 3))["A"]!!.rooms)
    }

    // Settling

    @Test fun eliminationChains() {
        s.sighting("c:1", "r1", "One", setOf("A", "B"), false, 0)
        s.sighting("c:2", "r2", "Two", setOf("B", "C"), false, 0)
        val r = s.resolve(mapOf("A" to 0, "B" to 1, "C" to 1))
        assertEquals(listOf(RoomSecrets("One", 1, false)), r["B"]!!.rooms)
        assertEquals(listOf(RoomSecrets("Two", 1, false)), r["C"]!!.rooms)
    }

    @Test fun tieSplitsProportionallyAsEstimate() {
        s.tick(mapOf("A" to "r1", "B" to "r1")); s.observe("r1", "One", 4)
        val r = s.resolve(mapOf("A" to 3, "B" to 1))
        assertEquals(listOf(RoomSecrets("One", 3, true)), r["A"]!!.rooms)
        assertEquals(listOf(RoomSecrets("One", 1, true)), r["B"]!!.rooms)
    }

    @Test fun unseenSecretsAreUnknownRooms() {
        s.sighting("i:1", "r1", "One", setOf("A"), true, 0)
        assertEquals(PlayerSecrets(5, listOf(RoomSecrets("One", 1, false)), 4), s.resolve(mapOf("A" to 5))["A"])
    }

    @Test fun certainAboveApiTotalKeepsRoomsAndApiHeadline() {
        repeat(4) { s.sighting("i:$it", "r1", "One", setOf("A"), true, 0) }
        assertEquals(PlayerSecrets(2, listOf(RoomSecrets("One", 4, false)), 0), s.resolve(mapOf("A" to 2))["A"])
    }

    @Test fun apiFailureMeansUnlimitedAndNullTotal() {
        s.sighting("c:1", "r1", "One", setOf("A", "B"), false, 0)
        assertEquals(PlayerSecrets(null, listOf(RoomSecrets("One", 1, false)), 0), s.resolve(mapOf("A" to null, "B" to 0))["A"])
    }

    @Test fun roomsMergeAndKeepFirstSeenOrder() {
        s.sighting("i:1", "r2", "Two", setOf("A"), true, 0)
        s.sighting("i:2", "r1", "One", setOf("A"), true, 0)
        s.sighting("i:3", "r2", "Two", setOf("A"), true, 0)
        assertEquals(listOf(RoomSecrets("Two", 2, false), RoomSecrets("One", 1, false)), s.resolve(mapOf("A" to 3))["A"]!!.rooms)
    }

    @Test fun everyRosterNameGetsAnEntry() {
        assertEquals(PlayerSecrets(0, emptyList(), 0), s.resolve(mapOf("A" to 0))["A"])
    }
}
