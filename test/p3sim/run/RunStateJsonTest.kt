package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.layouts.DoorKind
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A savepoint's copy of the hub: every field comes back, the records modules hold stay the same objects, nothing is announced. */
class RunStateJsonTest {
    private val a = UUID(1, 1); private val b = UUID(2, 2)

    private fun state(): RunState {
        val bus = RunBus()
        return RunState(TestPlans.plan(), bus) { 0 }
    }

    /** Sets something different in every part of the hub. */
    private fun scribble(s: RunState, n: Int) {
        s.rooms[1].check = CheckMark.GREEN; s.rooms[1].tDiscovered = 10 * n; s.rooms[1].tWhite = 20 * n; s.rooms[1].tGreen = 30 * n
        s.rooms[1].secretsFound = n; s.rooms[1].mobsRequired = 4 + n; s.rooms[1].mobsRequiredAlive = n
        s.rooms[1].occupants.clear(); s.rooms[1].occupants += a; s.rooms[1].visitors += listOf(a, b)
        s.rooms[2].check = CheckMark.RED; s.rooms[2].tRed = 5 * n
        s.doors[0].phase = DoorPhase.OPEN; s.doors[0].openedAt = 100 * n; s.doors[0].openedBy = b
        s.keys.witherHeld = n; s.keys.bloodHeld = n % 2 == 0; s.keys.witherObtained = 2 * n; s.keys.witherUsed = n; s.keys.bloodObtained = true; s.keys.bloodUsed = n > 1
        s.counters.opened = 3 * n; s.counters.completed = 2 * n
        s.secrets.teamFound = n; s.secrets.crypts = 2 * n; s.secrets.mimicKilled = true; s.secrets.princeKilled = n > 1; s.secrets.batKilled = false; s.secrets.witherEssence = 7 * n
        s.secrets.byPlayer[a] = n; s.secrets.byPlayer[b] = 2 * n; s.secrets.blessings["Power"] = n
        s.deaths.teamDeaths = n; s.deaths.log += (40 * n) to b
        s.score.score = 100 * n; s.score.clearedPct = 10 * n; s.score.lastUpdateT = 220 * n; s.score.history += (220 * n) to (100 * n)
        s.puzzles += PuzzleRecord(1, "BLAZE", "Higher Or Lower").also { it.status = PuzzleStatus.SOLVED; it.revealed = true; it.failures = n; it.solvedBy = a }
        s.players.getOrPut(a) { PlayerRecord(a, "Alpha", "Archer", false) }.also { it.hpPercent = 100.0 - n; it.ghost = n > 1; it.deaths = n; it.secretsFound = n; it.roomIndex = 1; it.x = 1.5 * n; it.y = 69.0; it.z = -2.5 * n; it.lastHitT = 7 * n; it.reviveAtT = 9 * n }
        s.players.getOrPut(b) { PlayerRecord(b, "Bravo", "Mage", true) }
        s.milestones.bloodKeyT = n; s.milestones.bloodOpenT = 2 * n; s.milestones.watcherDoneT = 3 * n; s.milestones.bossEntryT = 4 * n; s.milestones.bossDefeatedT = 5 * n; s.milestones.endT = 6 * n
    }

    @Test fun everyFieldRoundTrips() {
        val s = state()
        scribble(s, 2)
        val saved = s.snapshot()
        val before = s.snapshot().toString()
        scribble(s, 5)                                  // later play
        s.rooms[3].check = CheckMark.WHITE; s.players.remove(b)
        assertTrue(s.snapshot().toString() != before)
        s.restore(saved)
        assertEquals(before, s.snapshot().toString(), "the hub reads exactly as it did when the savepoint was taken")
        assertEquals(CheckMark.UNDISCOVERED, s.rooms[3].check)
        assertNotNull(s.players[b], "a party member who left since comes back")
    }

    @Test fun nullsComeBackAsNullsAndNothingIsAnnounced() {
        val s = state()
        val saved = s.snapshot()                         // a fresh hub: every optional field null
        scribble(s, 3)
        val seen = ArrayList<RunEvent>()
        val bus = RunBus(); bus.subscribe { seen += it }
        s.restore(saved)
        assertNull(s.rooms[1].tDiscovered); assertNull(s.doors[0].openedBy); assertNull(s.milestones.endT)
        assertEquals(CheckMark.UNDISCOVERED, s.rooms[1].check)
        assertEquals(0, s.puzzles.size); assertEquals(0, s.players.size)
        assertTrue(seen.isEmpty())
        assertEquals(DoorPhase.CLOSED, s.doors[0].phase)
    }

    @Test fun recordsKeepTheirIdentityAcrossARestore() {
        val s = state()
        scribble(s, 2)
        val room = s.rooms[1]; val player = s.players.getValue(a); val puzzle = s.puzzles[0]
        val saved = s.snapshot()
        scribble(s, 4)
        s.restore(saved)
        assertSame(room, s.rooms[1]); assertSame(player, s.players[a]); assertSame(puzzle, s.puzzles[0])
        assertEquals(98.0, player.hpPercent)
    }

    @Test fun anOldOrPartialSnapshotRestoresWhatItCanAndIgnoresTheRest() {
        val s = state()
        scribble(s, 2)
        val partial = com.google.gson.JsonParser.parseString("""{"keys":{"witherHeld":1},"rooms":[{"check":"WHITE"},{},{},{},{},{},{}],"doors":[{"phase":"NOPE"}],"future":42}""").asJsonObject
        s.restore(partial)
        assertEquals(1, s.keys.witherHeld)
        assertEquals(CheckMark.WHITE, s.rooms[0].check)
        assertEquals(DoorPhase.OPEN, s.doors[0].phase, "an unknown phase word keeps what was there")
        assertEquals(CheckMark.GREEN, s.rooms[1].check, "an empty room entry changes only what it names (here: nothing but the times)")
    }

    @Test fun theDoorsOfTheTestPlanAreTheOnesWeScribbleOn() {
        // Guards the fixture: scribble uses door 0 and rooms 0..3 of TestPlans.
        val s = state()
        assertEquals(DoorKind.ENTRANCE, s.doors[0].plan.kind)
        assertEquals(4, s.rooms.size)
    }
}
