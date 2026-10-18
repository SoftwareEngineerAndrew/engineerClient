package com.engineerclient.p3sim.run

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunConfigTest {
    @Test fun roundTripsThroughJson() {
        val cases = listOf(
            RunConfig(),
            RunConfig(layout = LayoutSource.Seed(42), seed = 7, bots = false, partySize = 2, lag = LagPreset.BAD, noDeaths = true, mobHpScale = null, statsPreset = "p1", strategy = "x"),
            RunConfig(layout = LayoutSource.Recorded("2000-01-01_00-00-00_F7"), seed = -9, lag = LagPreset.HIGH_SLOT, mobHpScale = 1.5),
        )
        for (c in cases) assertEquals(c, RunConfig.fromJson(c.toJson()))
    }

    @Test fun missingFieldsKeepDefaults() {
        val c = RunConfig.fromJson(com.google.gson.JsonObject())
        assertEquals("F7", c.floor); assertEquals(LayoutSource.Random, c.layout); assertEquals(5, c.partySize); assertEquals(LagPreset.OFF, c.lag)
        assertNull(c.strategy)
    }

    @Test fun partySizeIsClamped() {
        val o = RunConfig(partySize = 5).toJson().also { it.addProperty("partySize", 99) }
        assertEquals(5, RunConfig.fromJson(o).partySize)
        o.addProperty("partySize", 0)
        assertEquals(1, RunConfig.fromJson(o).partySize)
    }

    @Test fun stageWireRoundTrips() {
        for (s in RunStage.entries) assertEquals(RunWire.Msg("r1", "F7", s), RunWire.decode(RunWire.encode("r1", "F7", s)))
        assertEquals(RunWire.Msg(null, null, null), RunWire.decode(RunWire.encode(null, null, null)))
        assertNull(RunWire.decode("not json"))
    }

    @Test fun stageRules() {
        assertTrue(RunStage.canGo(RunStage.COUNTDOWN, RunStage.CLEAR))
        assertTrue(!RunStage.canGo(RunStage.COUNTDOWN, RunStage.BLOOD), "no skipping")
        assertTrue(!RunStage.canGo(RunStage.BOSS, RunStage.CLEAR), "no going back")
        assertTrue(RunStage.canGo(RunStage.CLEAR, RunStage.END), "any stage may end")
        assertTrue(!RunStage.canGo(RunStage.END, RunStage.END))
        assertTrue(RunStage.BOSS.inBoss && RunStage.BOSS_ENTRY.inBoss && !RunStage.CLEAR.inBoss && !RunStage.BLOOD.inBoss)
        assertTrue(RunStage.COUNTDOWN.inDungeon && !RunStage.BOSS.inDungeon)
    }
}
