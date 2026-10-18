package com.engineerclient.p3sim.run

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stage machine, the fan-out to systems and the bus, and the RunState helpers, with recording fake systems. */
class RunTest {
    private class Fake(override val id: String, val log: MutableList<String>, val failOnTick: Boolean = false) : RunSystem {
        override fun onRunStart(run: Run) { log += "$id start" }
        override fun onTick(run: Run) { log += "$id tick t=${run.t}"; if (failOnTick) error("tick boom") }
        override fun onStage(run: Run, from: RunStage?, to: RunStage) { log += "$id stage $from->$to" }
        override fun onEvent(run: Run, event: RunEvent) { log += "$id event ${event::class.simpleName}" }
        override fun onRunEnd(run: Run, reason: EndReason) { log += "$id end $reason" }
    }

    private fun run(log: MutableList<String>, fail: Boolean = false) =
        Run("r1", RunConfig(seed = 5), TestPlans.plan(), TestPlans.emptyPacks(), systems = listOf(Fake("a", log, fail), Fake("b", log)))

    @Test fun beginCallsSystemsInOrder() {
        val log = ArrayList<String>()
        val r = run(log)
        r.begin(100)
        assertEquals(RunStage.COUNTDOWN, r.stage)
        assertEquals(
            listOf("a start", "b start", "a stage null->COUNTDOWN", "b stage null->COUNTDOWN",
                "a event RunStarted", "b event RunStarted", "a event StageChanged", "b event StageChanged"), log)
    }

    @Test fun tickRunsEverySystemInOrderEvenWhenOneFails() {
        val log = ArrayList<String>()
        val r = run(log, fail = true)
        r.begin(100); log.clear()
        r.setT0(120)
        r.tick(110)
        assertEquals(listOf("a tick t=-10", "b tick t=-10"), log)
        assertEquals(-10, r.t)
        r.tick(120)
        assertEquals(0, r.t)
    }

    @Test fun theClockIsZeroUntilT0IsKnown() {
        val r = run(ArrayList())
        r.begin(50)
        r.tick(60)
        assertEquals(0, r.t)
        r.setT0(100)
        assertEquals(-40, r.t)
        assertEquals(100, r.state.milestones.t0Tick)
    }

    @Test fun stagesMoveForwardOneAtATimeAndAnyStageMayEnd() {
        val log = ArrayList<String>()
        val r = run(log)
        r.begin(0)
        assertFalse(r.requestStage(RunStage.BLOOD), "no skipping")
        assertEquals(RunStage.COUNTDOWN, r.stage)
        assertTrue(r.requestStage(RunStage.CLEAR))
        assertTrue(r.requestStage(RunStage.BLOOD))
        assertTrue(r.requestStage(RunStage.BOSS_ENTRY))
        assertTrue(r.requestStage(RunStage.BOSS))
        assertFalse(r.requestStage(RunStage.CLEAR), "no going back")
        assertTrue(log.contains("a stage BOSS_ENTRY->BOSS"))
        r.end(EndReason.COMPLETED)
        assertTrue(r.isOver); assertEquals(RunStage.END, r.stage)
        assertTrue(log.contains("a end COMPLETED") && log.contains("b end COMPLETED"))
        val n = log.size
        r.end(EndReason.STOPPED); r.tick(5)
        assertEquals(n, log.size, "ending twice, or ticking after the end, does nothing")
        assertFalse(r.requestStage(RunStage.END))
    }

    @Test fun endingFromAnyStageCallsOnStageAndEndOnce() {
        val log = ArrayList<String>()
        val r = run(log)
        r.begin(0); r.requestStage(RunStage.CLEAR); log.clear()
        r.end(EndReason.STOPPED)
        assertEquals(1, log.count { it == "a stage CLEAR->END" })
        assertEquals(1, log.count { it == "a end STOPPED" })
        assertEquals(1, log.count { it == "b event RunEnded" })
    }

    @Test fun requestingEndThroughTheStageMachineEndsTheRun() {
        val r = run(ArrayList())
        r.begin(0)
        assertTrue(r.requestStage(RunStage.END))
        assertTrue(r.isOver)
        assertEquals(EndReason.COMPLETED, (r.bus.recentEvents().last { it is RunEnded } as RunEnded).reason)
    }

    @Test fun eventsPostedByASystemReachLaterSystemsInOrder() {
        val log = ArrayList<String>()
        val r = run(log)
        r.begin(0); log.clear()
        r.bus.post(RoomEntered(0, 1, UUID(0, 9)))
        assertEquals(listOf("a event RoomEntered", "b event RoomEntered"), log)
    }

    @Test fun setCheckPostsTheMatchingEventsOnce() {
        val r = run(ArrayList())
        r.begin(0); r.setT0(0); r.tick(40)
        val seen = ArrayList<RunEvent>()
        r.bus.subscribe(5) { seen += it }
        r.state.setCheck(1, CheckMark.NONE)
        r.state.setCheck(1, CheckMark.NONE)
        r.state.setCheck(1, CheckMark.WHITE)
        r.state.setCheck(1, CheckMark.GREEN)
        r.state.setCheck(2, CheckMark.GREEN)   // a room that goes straight from UNDISCOVERED to GREEN is discovered too
        r.state.setCheck(3, CheckMark.RED)
        assertEquals(listOf("RoomDiscovered", "RoomCleared", "RoomGreen", "RoomDiscovered", "RoomGreen", "RoomDiscovered", "RoomFailed"), seen.map { it::class.simpleName })
        val room = r.state.room(1)!!
        assertEquals(40, room.tDiscovered); assertEquals(40, room.tWhite); assertEquals(40, room.tGreen); assertNull(room.tRed)
        assertEquals(CheckMark.GREEN, room.check)
    }

    @Test fun stateStartsFromThePlan() {
        val r = run(ArrayList())
        val s = r.state
        assertEquals(4, s.rooms.size)
        assertEquals(DoorPhase.CLOSED, s.door(0)!!.phase, "an entrance door starts closed")
        assertEquals(3, s.secrets.teamMax)
        assertEquals(36, s.counters.total)
        s.counters.completed = 5
        assertEquals(14, s.counters.clearedPct, "round(100 * 5 / 36)")
        s.secrets.teamFound = 1
        assertEquals(33.3, s.secrets.percent)
        assertEquals("Wide Hall", s.roomAt(-100.0, -130.0)!!.plan.name)
    }

    @Test fun subRandomsAreIndependentAndRepeatable() {
        val a = run(ArrayList()).subRandom("mobs"); val b = run(ArrayList()).subRandom("mobs"); val c = run(ArrayList()).subRandom("bots")
        val xs = List(5) { a.nextInt() }
        assertEquals(xs, List(5) { b.nextInt() })
        assertTrue(xs != List(5) { c.nextInt() })
    }
}
