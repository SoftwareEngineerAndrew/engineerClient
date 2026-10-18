package com.engineerclient.p3sim.blood

import com.engineerclient.p3sim.clear.blood.BloodRoom
import com.engineerclient.p3sim.clear.blood.NeutralCamp
import com.engineerclient.p3sim.clear.blood.WatcherStats
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunConfig
import com.engineerclient.p3sim.run.TestPlans
import com.engineerclient.p3sim.run.api.MobSpec
import com.engineerclient.p3sim.run.plugin.BloodCampHost
import com.engineerclient.p3sim.run.plugin.BloodRoomSpec
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeutralCampTest {
    private class FakeHost(override val room: BloodRoomSpec, private val takesKills: Boolean = true) : BloodCampHost {
        var kills = -1
        override fun watcherKills(kills: Int) { if (takesKills) this.kills = kills }
        override val run = Run("r", RunConfig(seed = 1), TestPlans.plan(), TestPlans.emptyPacks(), systems = emptyList())
        override val random: Random = Random(7)
        override var ticksSinceOpen = 0
        val specs = ArrayList<MobSpec>()
        val alive = LinkedHashSet<UUID>()
        val notes = ArrayList<String>()
        var failSpawns = false
        override fun spawnMob(spec: MobSpec): UUID? { if (failSpawns) return null; specs += spec; return UUID.randomUUID().also { alive += it } }
        override fun removeMob(id: UUID) { alive -= id }
        override fun mobsAlive(): List<UUID> = alive.toList()
        override fun partyInRoom(): List<UUID> = emptyList()
        override fun setHead(slot: Int, textureKey: String) {}
        override fun watcherSays(line: String) {}
        override fun note(text: String) { notes += text }
    }

    private val room = BloodRoom(3, "Blood", null, 0.5, 71.0, 0.5, 100.5, 69.0, 200.5, emptyList())

    private fun camp(h: BloodCampHost, count: Int = 3) = NeutralCamp(h, count, firstDelayTicks = 60, intervalTicks = 40, kinds = listOf("zombie_soldier"))

    @Test fun startSaysWhereTheRealCampComesFrom() {
        val h = FakeHost(room)
        camp(h).start()
        assertEquals(1, h.notes.size)
        assertTrue("too few wall heads" in h.notes[0])
    }

    @Test fun mobsComeOnAFixedTimerAroundTheWatcher() {
        val h = FakeHost(room)
        val c = camp(h)
        c.start()
        val releasedAt = ArrayList<Int>()
        for (t in 0..300) {
            h.ticksSinceOpen = t
            val before = h.specs.size
            c.tick()
            if (h.specs.size > before) releasedAt += t
        }
        assertEquals(listOf(60, 100, 140), releasedAt)
        for (s in h.specs) {
            val d = Math.hypot(s.x - room.watcherX, s.z - room.watcherZ)
            assertEquals(NeutralCamp.RING_RADIUS, d, 1e-6)
            assertEquals(room.watcherY, s.y)
            assertEquals(3, s.roomIndex)
            assertFalse(s.required)
        }
    }

    @Test fun itIsOverOnlyWhenEveryMobIsOutAndDead() {
        val h = FakeHost(room)
        val c = camp(h)
        for (t in 0..200) { h.ticksSinceOpen = t; c.tick() }
        assertEquals(3, h.alive.size)
        assertFalse(c.finished)
        h.alive.clear()
        assertTrue(c.finished)
    }

    @Test fun notFinishedBeforeTheLastMobIsReleased() {
        val h = FakeHost(room)
        val c = camp(h)
        h.ticksSinceOpen = 60; c.tick()
        h.alive.clear()
        assertFalse(c.finished, "two more are still to come")
    }

    @Test fun whenNoMobCanBeMadeItStillFinishesOnceTheTimerHasRun() {
        val h = FakeHost(room).also { it.failSpawns = true }
        val c = camp(h, count = 2)
        for (t in 0..200) { h.ticksSinceOpen = t; c.tick() }
        assertTrue(c.finished)
    }

    @Test fun usesThePacksMobCellsWhenThereAre() {
        val r = BloodRoom(3, "Blood", null, 0.0, 71.0, 0.0, 100.5, 69.0, 200.5, emptyList(), mobCells = listOf(doubleArrayOf(5.0, 69.0, 6.0)))
        val h = FakeHost(r)
        val c = camp(h, 1)
        h.ticksSinceOpen = 60; c.tick()
        assertEquals(5.0, h.specs[0].x); assertEquals(6.0, h.specs[0].z)
    }

    @Test fun aSnapshotKeepsHowManyWereReleased() {
        val h = FakeHost(room)
        val c = camp(h)
        h.ticksSinceOpen = 100; c.tick(); h.ticksSinceOpen = 100; c.tick()
        val saved = c.snapshot()
        val d = camp(h)
        d.restore(saved)
        val before = h.specs.size
        h.ticksSinceOpen = 100; d.tick()
        assertEquals(before, h.specs.size, "the restored camp does not release the first mob again")
    }

    @Test fun theWatchersKillsFollowTheDeadMobsAndFifteenIsTheLastOne() {
        val h = FakeHost(room)
        val c = camp(h)
        for (t in 0..200) { h.ticksSinceOpen = t; c.tick() }
        assertEquals(0, h.kills)
        h.alive.remove(h.alive.first()); c.tick()
        assertEquals(4, h.kills, "one of three: a third of the way to the last kill")
        h.alive.remove(h.alive.first()); c.tick()
        assertEquals(9, h.kills)
        h.alive.clear(); c.tick()
        assertEquals(WatcherStats.KILLS_TO_DEFEAT, h.kills, "the last mob dying is the defeat")
    }

    @Test fun noKillsAreOwedWhileMobsAreStillToCome() {
        val h = FakeHost(room)
        val c = camp(h)
        h.ticksSinceOpen = 60; c.tick()
        h.alive.clear(); c.tick()
        assertTrue(h.kills < WatcherStats.KILLS_TO_DEFEAT, "two more are still to come")
    }

    @Test fun aHostThatIgnoresTheKillReportsStillRunsTheCamp() {
        val h = FakeHost(room, takesKills = false)
        val c = camp(h)
        for (t in 0..200) { h.ticksSinceOpen = t; c.tick() }
        assertEquals(3, h.specs.size)
        assertEquals(-1, h.kills, "nothing is kept by a host that does not take it")
    }
}
