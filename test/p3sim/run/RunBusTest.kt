package com.engineerclient.p3sim.run

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunBusTest {
    private val p = UUID(0, 1)

    @Test fun deliversInPriorityThenSubscriptionOrder() {
        val bus = RunBus()
        val seen = ArrayList<String>()
        bus.subscribe(10) { seen += "late" }
        bus.subscribe(0) { seen += "a" }
        bus.subscribe(0) { seen += "b" }
        bus.subscribe(-5) { seen += "first" }
        bus.post(RunStarted(0))
        assertEquals(listOf("first", "a", "b", "late"), seen)
    }

    @Test fun anEventPostedByAHandlerWaitsItsTurn() {
        val bus = RunBus()
        val order = ArrayList<String>()
        bus.subscribe(0) { e ->
            order += "A sees ${e::class.simpleName}"
            if (e is RoomDiscovered) bus.post(RoomEntered(1, e.room, p))
        }
        bus.subscribe(1) { e -> order += "B sees ${e::class.simpleName}" }
        bus.post(RoomDiscovered(0, 3))
        // B has seen the first event before anyone sees the one A posted: no recursion, same order everywhere.
        assertEquals(listOf("A sees RoomDiscovered", "B sees RoomDiscovered", "A sees RoomEntered", "B sees RoomEntered"), order)
    }

    @Test fun aFailingHandlerDoesNotStopTheOthers() {
        val errors = ArrayList<Throwable>()
        val bus = RunBus(onError = { _, t -> errors += t })
        var reached = false
        bus.subscribe(0) { error("boom") }
        bus.subscribe(1) { reached = true }
        bus.post(RunStarted(0))
        assertTrue(reached); assertEquals(1, errors.size)
    }

    @Test fun typedSubscriptionAndCancel() {
        val bus = RunBus()
        var kills = 0
        val sub = bus.on<MobKilled> { kills++ }
        val mob = MobRef(UUID(0, 2), "x", 1, true, true)
        bus.post(MobKilled(1, mob, null)); bus.post(RunStarted(1)); bus.post(MobKilled(2, mob, p))
        assertEquals(2, kills)
        sub.cancel()
        bus.post(MobKilled(3, mob, null))
        assertEquals(2, kills)
    }

    @Test fun postFromTheWrongThreadIsAnError() {
        val bus = RunBus(serverThread = { false })
        assertFailsWith<IllegalStateException> { bus.post(RunStarted(0)) }
    }

    @Test fun remembersTheLastEvents() {
        val bus = RunBus()
        repeat(300) { bus.post(RunStarted(it)) }
        val recent = bus.recentEvents()
        assertEquals(200, recent.size)
        assertEquals(299, recent.last().t)
    }
}
