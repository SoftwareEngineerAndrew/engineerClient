package com.engineerclient.p3sim.run

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Taking and restoring savepoints: the order the contract promises (ARCHITECTURE 7), and what is refused. */
class RunSavepointsTest {
    private val log = ArrayList<String>()

    private inner class FakeWorld : SavepointWorld {
        override fun capturePlayers() = JsonArray().also { it.add("p@${log.size}") }
        override fun restorePlayers(data: JsonArray) { log += "players ${data}" }
        override fun resyncRegions(): Int { log += "resync"; return 3 }
        override fun clearEntities() { log += "clear" }
    }

    private inner class Mod(override val id: String, var value: Int = 0) : RunSystem {
        override fun snapshot(run: Run): JsonObject = JsonObject().also { it.addProperty("v", value) }
        override fun restore(run: Run, data: JsonObject) { value = data.get("v").asInt; log += "$id restore $value" }
        override fun onStage(run: Run, from: RunStage?, to: RunStage) { log += "$id stage $from->$to" }
    }
    private inner class Silent(override val id: String) : RunSystem { override fun restore(run: Run, data: JsonObject) { log += "$id restore!" } }

    private val a = Mod("a"); private val b = Mod("b"); private val silent = Silent("silent")
    private val store = RunSavepoints(FakeWorld())

    private fun run(): Run {
        val r = Run("r", RunConfig(seed = 3), TestPlans.plan(), TestPlans.emptyPacks(), systems = listOf(a, silent, b))
        r.savepoints = store
        r.begin(100); r.setT0(160)
        return r
    }

    @Test fun aSavepointHoldsTheStateEverySystemAndThePlayers() {
        val r = run()
        r.state.keys.witherHeld = 1; a.value = 5; b.value = 6
        r.tick(200)
        val sp = r.savepoint("Room: Wide Hall", 1)!!
        assertEquals("Room: Wide Hall", sp.label); assertEquals(1, sp.roomIndex); assertEquals(40, sp.t); assertEquals(RunStage.COUNTDOWN, sp.stage)
        assertEquals(5, sp.data.getAsJsonObject("modules").getAsJsonObject("a").get("v").asInt)
        assertNull(sp.data.getAsJsonObject("modules").get("silent"), "a system with no snapshot has no entry")
        assertEquals(1, sp.data.getAsJsonObject("state").getAsJsonObject("keys").get("witherHeld").asInt)
        assertTrue(r.bus.recentEvents().any { it is SavepointTaken && it.savepointId == sp.id })
        assertEquals(listOf(sp.id), store.list().map { it.id })
        assertEquals(sp, store.forRoom(1))
    }

    @Test fun restoreGoesStageClockStateRegionsEntitiesModulesPlayersThenTheEvent() {
        val r = run()
        r.requestStage(RunStage.CLEAR)
        r.tick(200); r.state.counters.completed = 2; a.value = 1; b.value = 2
        val sp = r.savepoint("Start")!!
        r.requestStage(RunStage.BLOOD)
        r.tick(900); r.state.counters.completed = 30; a.value = 9; b.value = 8
        log.clear()
        assertTrue(store.restore(r, sp.id))
        // stage back first (the modules hear it), then the world is put back, then each module re-applies its own, the players last.
        assertEquals(listOf("a stage BLOOD->CLEAR", "b stage BLOOD->CLEAR", "resync", "clear", "a restore 1", "b restore 2"), log.take(6))
        assertTrue(log.last().startsWith("players"))
        assertEquals(RunStage.CLEAR, r.stage)
        assertEquals(2, r.state.counters.completed)
        assertEquals(sp.t, r.t, "the run clock reads what it read when the savepoint was taken")
        val tail = r.bus.recentEvents().takeLast(2)
        assertTrue(tail.last() is SavepointRestored, "SavepointRestored is posted after everything is back")
        r.tick(910)
        assertEquals(sp.t + 10, r.t, "and goes on from there")
    }

    @Test fun restoringTwiceGivesTheSameWorld() {
        val r = run()
        r.tick(200); a.value = 4
        val sp = r.savepoint("x")!!
        a.value = 8; assertTrue(store.restore(r, sp.id)); assertEquals(4, a.value)
        a.value = 12; assertTrue(store.restore(r, sp.id)); assertEquals(4, a.value)
    }

    @Test fun onlyTheDungeonStagesAreSavedOrRestored() {
        val r = run()
        val early = r.savepoint("early")!!
        r.requestStage(RunStage.CLEAR); r.requestStage(RunStage.BLOOD); r.requestStage(RunStage.BOSS_ENTRY)
        assertNull(r.savepoint("late"), "nothing is saved once the boss entry has begun")
        assertFalse(store.restore(r, early.id), "and the boss has its own restart")
        r.end(EndReason.STOPPED)
        assertNull(store.take(r, "after the end"))
        assertFalse(store.restore(r, "nope"))
    }

    @Test fun anUnknownIdIsRefused() {
        val r = run()
        assertFalse(store.restore(r, "sp99"))
    }

    @Test fun aClockBeforeT0WasPlannedComesBackUnknown() {
        val r = Run("r", RunConfig(seed = 3), TestPlans.plan(), TestPlans.emptyPacks(), systems = emptyList())
        r.savepoints = store
        r.begin(50)
        val sp = r.savepoint("before T0")!!
        r.setT0(300)
        assertTrue(store.restore(r, sp.id))
        assertNull(r.t0Tick); assertEquals(0, r.t)
    }

    @Test fun aThrowingSystemDoesNotStopTheRestoreOfTheOthers() {
        val boom = object : RunSystem {
            override val id = "boom"
            override fun snapshot(run: Run) = JsonObject()
            override fun restore(run: Run, data: JsonObject) = error("restore boom")
        }
        val r = Run("r", RunConfig(seed = 3), TestPlans.plan(), TestPlans.emptyPacks(), systems = listOf(boom, a))
        r.savepoints = store
        r.begin(0); r.setT0(0)
        a.value = 3
        val sp = r.savepoint("x")!!
        a.value = 99
        assertTrue(store.restore(r, sp.id))
        assertEquals(3, a.value)
    }

    @Test fun theOldestAreDroppedButNotTheStart() {
        val small = RunSavepoints(FakeWorld(), maxKept = 4)
        val r = Run("r", RunConfig(seed = 3), TestPlans.plan(), TestPlans.emptyPacks(), systems = emptyList())
        r.savepoints = small
        r.begin(0); r.setT0(0)
        val ids = (0 until 7).map { small.take(r, "s$it")!!.id }
        val kept = small.list().map { it.id }
        assertEquals(4, kept.size)
        assertEquals(ids.first(), kept.first(), "the first savepoint stays")
        assertEquals(ids.takeLast(3), kept.takeLast(3))
    }

    @Test fun nothingIsSavedWhileARestoreIsRunning() {
        lateinit var run: Run
        var inner: Savepoint? = null
        val world = object : SavepointWorld by SavepointWorld.None {
            override fun resyncRegions(): Int { inner = store2.take(run, "inside"); return 0 }
        }
        val s = RunSavepoints(world); store2 = s
        run = Run("r", RunConfig(seed = 3), TestPlans.plan(), TestPlans.emptyPacks(), systems = emptyList())
        run.savepoints = s; run.begin(0); run.setT0(0)
        val sp = s.take(run, "a")!!
        assertTrue(s.restore(run, sp.id))
        assertNull(inner)
        assertNotNull(s.take(run, "after"))
    }
    private lateinit var store2: RunSavepoints

    @Test fun beginBossWalksTheStagesAndCallsTheStarterOnce() {
        val r = Run("r", RunConfig(seed = 3), TestPlans.plan(), TestPlans.emptyPacks(), systems = emptyList())
        val started = ArrayList<BossEntryOptions>()
        r.bossStarter = { _, o -> started += o }
        r.begin(0)
        val opts = BossEntryOptions(giveHotbar = false, firstLineDelayTicks = 2)
        r.beginBoss(opts)
        assertEquals(RunStage.BOSS, r.stage)
        assertEquals(listOf(opts), started)
        r.beginBoss(opts)
        assertEquals(1, started.size, "a second call in the BOSS stage does nothing")
    }

    @Test fun aFailingBossStarterIsLoggedNotThrown() {
        val r = Run("r", RunConfig(seed = 3), TestPlans.plan(), TestPlans.emptyPacks(), systems = emptyList())
        r.bossStarter = { _, _ -> error("no boss today") }
        r.begin(0)
        r.beginBoss()
        assertEquals(RunStage.BOSS, r.stage)
    }

    @Test fun noSavepointIsTakenWhileAPersonIsAGhost() {
        val r = run()
        r.requestStage(RunStage.CLEAR)
        val ghost = PlayerRecord(java.util.UUID.randomUUID(), "Ann", "Mage", false).also { it.ghost = true }
        r.state.players[ghost.uuid] = ghost
        assertNull(r.savepoint("Room: A", 1), "the ghost kit would come back as the inventory")
        ghost.ghost = false
        assertNotNull(r.savepoint("Room: A", 1))
    }
}
