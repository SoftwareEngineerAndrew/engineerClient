package com.engineerclient.p3sim.blood.camp

import com.engineerclient.p3sim.clear.blood.BloodRoom
import com.engineerclient.p3sim.clear.blood.camp.CampLines
import com.engineerclient.p3sim.clear.blood.camp.CampMobs
import com.engineerclient.p3sim.clear.blood.camp.WatcherCamp
import com.engineerclient.p3sim.run.MobKilled
import com.engineerclient.p3sim.run.MobRef
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunConfig
import com.engineerclient.p3sim.run.TestPlans
import com.engineerclient.p3sim.run.api.MobSpec
import com.engineerclient.p3sim.run.plugin.BloodCampHost
import com.engineerclient.p3sim.run.plugin.HeadSlot
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Watcher's camp on a fake blood host: the whole camp, headless. */
class WatcherCampTest {
    private val cx = 100.5
    private val cz = 200.5
    private val floor = 69.0

    /** 30 head slots in a ring around the middle of the room at the three row heights, as block cells (the stand hangs 0.75 above the cell). */
    private fun slots(n: Int = 30): List<HeadSlot> = (0 until n).map { i ->
        val a = Math.PI * 2 * i / n
        HeadSlot(i, Math.round(cx + 12.0 * cos(a)).toInt(), 71 + 4 * (i % 3), Math.round(cz + 12.0 * sin(a)).toInt(), "north")
    }

    private fun room(n: Int = 30) = BloodRoom(3, "Blood", null, cx, 71.0, cz - 30, cx, floor, cz, slots(n))

    private class FakeHost(override val room: BloodRoom, seed: Int = 7) : BloodCampHost {
        override val run = Run("r", RunConfig(seed = 1), TestPlans.plan(), TestPlans.emptyPacks(), systems = emptyList())
        override val random: Random = Random(seed)
        override var ticksSinceOpen = 0
        val specs = ArrayList<MobSpec>()
        val ids = ArrayList<UUID>()
        val alive = LinkedHashSet<UUID>()
        val heads = HashMap<Int, String>()
        val flights = ArrayList<List<Number>>()
        val said = ArrayList<Pair<Int, String>>()
        val moves = ArrayList<DoubleArray>()
        var kills = 0
        override fun spawnMob(spec: MobSpec): UUID? { specs += spec; return UUID.randomUUID().also { ids += it; alive += it } }
        override fun removeMob(id: UUID) { alive -= id }
        override fun mobsAlive(): List<UUID> = alive.toList()
        override fun partyInRoom(): List<UUID> = emptyList()
        override fun setHead(slot: Int, textureKey: String) { heads[slot] = textureKey }
        override fun flyHead(slot: Int, x: Double, y: Double, z: Double, ticks: Int) { flights += listOf(slot, x, y, z, ticks) }
        override fun moveWatcher(x: Double, y: Double, z: Double, yaw: Float) { moves += doubleArrayOf(x, y, z, yaw.toDouble()) }
        override fun watcherKills(kills: Int) { this.kills = kills }
        override fun watcherSays(line: String) { said += ticksSinceOpen to line }
        override fun note(text: String) {}
    }

    private fun kill(c: WatcherCamp, h: FakeHost, id: UUID) {
        h.alive -= id
        c.onEvent(MobKilled(0, MobRef(id, "blood_mob", 3, false, false), null))
    }

    /** Drives the camp; every mob dies [life] ticks after it appears. */
    private fun drive(c: WatcherCamp, h: FakeHost, from: Int, to: Int, life: Int, born: HashMap<UUID, Int>) {
        for (t in from..to) {
            h.ticksSinceOpen = t
            for (id in h.ids) born.putIfAbsent(id, t)
            for (id in h.alive.toList()) if (t - born.getValue(id) >= life) kill(c, h, id)
            c.tick()
            if (c.finished) return
        }
    }

    @Test
    fun theCampPlaysAllTheWayToTheDefeat() {
        val h = FakeHost(room())
        val c = WatcherCamp(h)
        c.start()
        drive(c, h, 0, 4000, life = 25, born = HashMap())
        assertTrue(c.finished)
        assertEquals(15, h.kills, "his health steps down to the fifteenth count")
        assertTrue(h.specs.size in 15..16)
        // The wall: 30 heads were dressed (plain here: no rooms pack), and 15 or 16 were thrown on a flight each.
        assertEquals(30, h.heads.size)
        assertEquals(h.specs.size, h.flights.size)
        assertTrue(h.flights.all { it[4].toInt() == 80 || it[4].toInt() == 40 })
        // He opens 3 ticks after the door with one of his two opening lines, and said more than that.
        assertEquals(3, h.said.first().first)
        assertTrue(h.said.first().second == CampLines.INTRO || h.said.first().second == CampLines.INTRO_OTHER)
        assertTrue(h.said.size >= 5)
        assertTrue(h.moves.size > 100)
    }

    @Test
    fun theCampMobsAppearAtTheHeadsEndOverTheFloorWithMeasuredHp() {
        val h = FakeHost(room())
        val c = WatcherCamp(h)
        c.start()
        drive(c, h, 0, 4000, life = 25, born = HashMap())
        for (s in h.specs) {
            assertTrue(s.y in floor + CampMobs.SPAWN_RISE_LOW..floor + CampMobs.SPAWN_RISE_HIGH, "y ${s.y}")
            assertEquals(3, s.roomIndex)
            assertTrue(!s.required && !s.starred)
            assertNotNull(s.hp)
            assertEquals("blood", s.extras["camp"])
            assertTrue(s.extras["landing"] == "initial" || s.extras["landing"] == "later")
        }
        assertEquals(1, h.specs.count { it.kind == WatcherCamp.KIND_GIANT })
        assertEquals(1, h.specs.count { it.kind == WatcherCamp.KIND_BOSS })
        assertEquals(4, h.specs.count { it.extras["landing"] == "initial" })
    }

    @Test
    fun aRoomWithTooFewHeadsIsNotForTheCamp() {
        assertTrue(WatcherCamp.supports(room(30)))
        assertTrue(WatcherCamp.supports(room(WatcherCamp.MIN_HEADS)))
        assertTrue(!WatcherCamp.supports(room(WatcherCamp.MIN_HEADS - 1)))
        assertTrue(!WatcherCamp.supports(room(0)))
    }

    @Test
    fun theWatcherHangsFourBlocksOverTheFloorAndMovesFacingHisFlight() {
        val h = FakeHost(room())
        val c = WatcherCamp(h)
        c.start()
        drive(c, h, 0, 4000, life = 25, born = HashMap())
        // His legs run between the home height (floor + 4) and one block over a stand.
        assertTrue(h.moves.all { it[1] in floor + 3.0..floor + 12.0 })
        assertTrue(h.moves.map { it[3] }.toSet().size > 3, "he faces the way he flies")
    }

    @Test
    fun theSameSeedGivesTheSameCamp() {
        fun play(): List<String> {
            val h = FakeHost(room(), seed = 21)
            val c = WatcherCamp(h)
            c.start()
            drive(c, h, 0, 4000, life = 40, born = HashMap())
            return h.said.map { it.toString() } + h.specs.map { "${it.kind} ${it.hp} ${it.x} ${it.z}" }
        }
        assertEquals(play(), play())
    }

    @Test
    fun aSavedCampCarriesOnWithTheMobsItHadMade() {
        val h = FakeHost(room())
        val c = WatcherCamp(h)
        c.start()
        val born = HashMap<UUID, Int>()
        drive(c, h, 0, 560, life = 120, born = born)
        val made = h.specs.size
        assertTrue(made in 4..6, "four initial mobs and maybe the first later one by 560: $made")
        val saved = assertNotNull(c.snapshot())
        // A new host and camp: the world keeps the mobs (the same ids), the camp takes its state back.
        val h2 = FakeHost(room(), seed = 99)
        h2.ids += h.ids; h2.alive += h.alive
        val c2 = WatcherCamp(h2)
        c2.restore(saved)
        drive(c2, h2, 561, 5000, life = 120, born = born)
        assertTrue(c2.finished)
        assertEquals(15, h2.kills)
        assertTrue(made + h2.specs.size in 15..16, "no mob is made twice: ${made + h2.specs.size}")
    }

    @Test
    fun aSavedCampOfAnotherWallStartsOver() {
        val h = FakeHost(room())
        val c = WatcherCamp(h)
        c.start()
        drive(c, h, 0, 100, life = 120, born = HashMap())
        val saved = assertNotNull(c.snapshot())
        val h2 = FakeHost(room(20))
        val c2 = WatcherCamp(h2)
        c2.restore(saved)
        // A fresh camp dressed the 20-head wall and runs from its start.
        assertEquals(20, h2.heads.size)
        assertTrue(!c2.finished)
    }
}
