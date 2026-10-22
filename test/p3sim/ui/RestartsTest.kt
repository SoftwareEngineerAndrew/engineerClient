package com.engineerclient.p3sim.clear.ui

import com.engineerclient.p3sim.run.RunStage
import com.engineerclient.p3sim.run.Savepoint
import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestartsTest {
    private fun sp(id: String, label: String, t: Int, room: Int? = null) = Savepoint(id, label, t, RunStage.CLEAR, room, JsonObject())

    private val all = listOf(sp("a", "Start", -100), sp("b", "Room: Alpha", 200, 3), sp("c", "Room: Beta", 500, 5), sp("d", "Manual", 600), sp("e", "Room: Alpha", 900, 3))

    private fun use(c: Restarts.Choice): Restarts.Choice.Use { assertIs<Restarts.Choice.Use>(c); return c }

    @Test fun startIsTheStartSavepoint() {
        assertEquals("a", use(Restarts.start(all)).savepoint.id)
        assertEquals("b", use(Restarts.start(all.drop(1))).savepoint.id) // no label: the oldest
        assertIs<Restarts.Choice.None>(Restarts.start(emptyList()))
    }

    @Test fun aRoomGoesToTheLatestEntryOfThatRoom() {
        val u = use(Restarts.room(all, 3))
        assertEquals("e", u.savepoint.id); assertNull(u.note)
        assertEquals("c", use(Restarts.room(all, 5)).savepoint.id)
    }

    @Test fun noRoomOrAnUnsavedRoomGoesToTheLatestRoomSavepointAndSaysSo() {
        for (here in listOf(null, 9)) {
            val u = use(Restarts.room(all, here))
            assertEquals("e", u.savepoint.id)
            assertTrue(u.note!!.contains("Room: Alpha"))
        }
    }

    @Test fun withNoRoomSavepointsItFallsBackToTheStart() {
        val u = use(Restarts.room(listOf(sp("a", "Start", -100), sp("d", "Manual", 600)), 4))
        assertEquals("a", u.savepoint.id); assertTrue(u.note!!.contains("start"))
        assertIs<Restarts.Choice.None>(Restarts.room(emptyList(), 4))
    }

    @Test fun theListIsNumberedFromOneWithTheClock() {
        assertEquals(listOf("1. Start (-5.0 s)", "2. Room: Alpha (10.0 s)"), Restarts.lines(all.take(2)))
        assertEquals("0.0 s", Restarts.clock(0)); assertEquals("62.5 s", Restarts.clock(1250)); assertEquals("-0.5 s", Restarts.clock(-10))
    }

    @Test fun findTakesANumberOrAnId() {
        assertEquals("c", Restarts.find(all, "3")!!.id)
        assertEquals("d", Restarts.find(all, "d")!!.id)
        assertNull(Restarts.find(all, "9")); assertNull(Restarts.find(all, "zzz"))
    }
}
