package com.engineerclient.p3sim.run

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the clients are told about the run's stage, and when (Odin's bridge forces "in boss" until the first message says otherwise). */
class RunWireTest {
    private val sent = ArrayList<RunWire.Msg?>()
    private val oldSender = RunWire.sender

    init { RunWire.sender = { kind, data -> if (kind == RunWire.KIND) sent += RunWire.decode(data) } }

    @AfterEach fun restore() { RunWire.sender = oldSender }

    private fun run() = Run("r9", RunConfig(seed = 1), TestPlans.plan(), TestPlans.emptyPacks(), systems = emptyList())

    @Test fun theFirstStageIsAnnouncedAtBegin() {
        val r = run()
        assertTrue(sent.isEmpty())
        r.begin(10)
        assertEquals(listOf(RunStage.COUNTDOWN), sent.map { it?.stage })
        assertEquals("r9", sent[0]!!.runId); assertEquals("F7", sent[0]!!.floor)
    }

    @Test fun everyStageChangeIsAnnouncedAndALateJoinerHearsTheCurrentOne() {
        val r = run()
        r.begin(0); r.requestStage(RunStage.CLEAR); sent.clear()
        r.bus.post(PlayerJoined(0, UUID(0, 5)))
        assertEquals(listOf(RunStage.CLEAR), sent.map { it?.stage }, "the joiner is told the stage the run is in")
        r.requestStage(RunStage.BLOOD)
        assertEquals(RunStage.BLOOD, sent.last()!!.stage)
    }

    @Test fun stoppingTheRunTellsTheClientsThereIsNone() {
        val r = run()
        r.begin(0)
        Runs.install(r)
        sent.clear()
        Runs.stop()
        assertEquals(listOf(RunStage.END, null), sent.map { it?.stage }, "END from the run, then 'no run' from Runs.stop")
        assertEquals(null, Runs.current)
    }

    @Test fun theBridgeFollowsTheStage() {
        for (s in RunStage.entries) {
            ClientRun.apply(RunWire.Msg("r", "F7", s))
            // END keeps whatever the stage before it was (here BOSS, the last one before it in the loop).
            assertEquals(s == RunStage.BOSS_ENTRY || s == RunStage.BOSS || s == RunStage.END, ClientRun.bridgeInBoss, s.name)
        }
        ClientRun.reset()
        ClientRun.apply(RunWire.Msg("r", "F7", RunStage.CLEAR)); ClientRun.apply(RunWire.Msg("r", "F7", RunStage.END))
        assertEquals(false, ClientRun.bridgeInBoss, "a run that ended in the clear stays out of the boss")
        assertTrue(ClientRun.inClear.not())
        assertTrue(ClientRun.bridgeAsClear, "END after a clear leaves Odin's dungeon scan alone")
        ClientRun.apply(RunWire.Msg("r", "F7", RunStage.BOSS)); ClientRun.apply(RunWire.Msg("r", "F7", RunStage.END))
        assertTrue(ClientRun.bridgeAsClear.not(), "END after the boss keeps boss mode")
        ClientRun.reset()
        assertTrue(ClientRun.bridgeInBoss, "no run at all is today's boss practice")
        assertTrue(ClientRun.bridgeAsClear.not())
    }
}
