package com.engineerclient.p3sim.blood.camp

import com.engineerclient.p3sim.clear.blood.camp.CampConfig
import com.engineerclient.p3sim.clear.blood.camp.CampEngine
import com.engineerclient.p3sim.clear.blood.camp.CampMech
import com.engineerclient.p3sim.clear.blood.camp.CampPort
import com.engineerclient.p3sim.clear.blood.camp.Landing
import com.engineerclient.p3sim.clear.blood.camp.Vec3
import com.engineerclient.p3sim.clear.blood.camp.WallClass
import com.engineerclient.p3sim.clear.blood.camp.WallPlan
import com.engineerclient.p3sim.clear.blood.camp.WallStation
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Watcher's camp state machine against the rules it is built from. Synthetic geometry only: a ring of stands around the room
 * centre at three row heights, and mobs that die when a test policy says so.
 */
class CampEngineTest {
    private val home = Vec3(100.5, 73.0, 200.5)

    private fun ring(n: Int = 30): List<Vec3> = (0 until n).map { i ->
        val a = Math.PI * 2 * i / n
        val r = 12.0 + (i % 3) * 1.5
        Vec3(home.x + r * cos(a), doubleArrayOf(71.75, 75.75, 79.75)[i % 3], home.z + r * sin(a))
    }

    /** When a mob dies: [initial] gets the order of the initial mob (1 to 4) and its landing tick; [later] the later mob's index and landing tick. Both return a door tick. */
    private class Policy(val initial: (Int, Int) -> Int, val later: (Int, Int) -> Int) {
        companion object {
            /** Every mob dies on the tick it is added. */
            val ON_ADD = Policy({ _, add -> add }, { _, add -> add })
        }
    }

    /** A port whose mobs die on the ticks a [Policy] says; it records what the engine did. */
    private class Sim(val policy: Policy) : CampPort {
        val deaths = HashMap<String, Int>()
        var now = 0
        val counts = ArrayList<Pair<Int, Int>>()
        val moveTicks = ArrayList<Int>()
        var spawned = 0
        private var initialN = 0
        private var laterN = 0
        override fun spawn(landing: Landing): String? {
            val h = "m${spawned++}"
            val d = if (landing.initial) policy.initial(++initialN, landing.landTick) else policy.later(laterN++, landing.landTick)
            deaths[h] = maxOf(d, landing.landTick)
            return h
        }
        override fun killsCounted(total: Int) { counts += now to total }
        override fun watcherAt(x: Double, y: Double, z: Double, yaw: Float) { moveTicks += now }

        /** The decision ticks of the legs: a leg starts the tick after its decision, on the first tick after a gap in the moves. */
        fun decisions(): List<Int> = moveTicks.filterIndexed { i, t -> i == 0 || moveTicks[i - 1] != t - 1 }.map { it - 1 }
    }

    private class Ran(val engine: CampEngine, val sim: Sim, val wall: List<WallStation>)

    private fun run(seed: Int, policy: Policy = Policy.ON_ADD, config: CampConfig = CampConfig(chatCoins = false, wsChance = 0.0), until: Int = 5000): Ran {
        val rng = Random(seed)
        val wall = WallPlan.assign(ring(), rng)
        val sim = Sim(policy)
        val e = CampEngine(home, wall, rng, sim, config)
        var r = 0
        while (!e.finished && r <= until) {
            sim.now = r
            val alive = sim.deaths.filter { it.value >= r }.keys.toSet()
            for ((h, d) in sim.deaths) if (d == r) e.noteDeath(h, r)
            e.step(r, alive)
            r++
        }
        return Ran(e, sim, wall)
    }

    @Test
    fun theFlightRuleStopsOnTheFirstTickInsideTheArrivalRadius() {
        val rng = Random(1)
        repeat(200) {
            val a = Vec3(rng.nextDouble(-20.0, 20.0), 73.0, rng.nextDouble(-20.0, 20.0))
            val b = Vec3(rng.nextDouble(-20.0, 20.0), rng.nextDouble(72.0, 81.0), rng.nextDouble(-20.0, 20.0))
            for (v in doubleArrayOf(0.70, 0.50, 0.6125, 0.4375)) {
                val f = CampMech.flyTo(a, b, v)
                // Brute force: step toward the target at v a tick until the squared distance is below the radius.
                val dir = (b - a) / (b - a).length().coerceAtLeast(1e-12)
                var n = 0
                var p = a
                while ((b - p).let { it dot it } >= CampMech.ARRIVE_R2) { n++; p = a + dir * v * n.toDouble() }
                assertEquals(n, f.n)
                assertEquals(0.0, p.dist(f.end), 1e-9)
            }
        }
    }

    @Test
    fun theOpeningHopsStartOnTheThirtyTickGridFromDoor90() {
        val ran = run(3)
        val first = ran.engine.landings[0]
        // Independent of the engine: he is at home on tick 90, flies at 0.70 to one block over the stand, throws 10 ticks after coming to rest.
        val fly = CampMech.flyTo(home, CampMech.target(first.station.stand), CampMech.V_OPEN)
        assertEquals(90 + fly.n + 1 + CampMech.THROW_DELAY, first.throwTick)
        assertEquals(first.throwTick + CampMech.FLIGHT_INITIAL, first.landTick)
        assertEquals(first.landTick - 1, first.spawnTick)
        val decisions = ran.sim.decisions().take(4)
        assertEquals(90, decisions[0])
        assertTrue(decisions.all { (it - 90) % 30 == 0 }, "opening decisions $decisions")
        assertEquals(4, ran.engine.landings.count { it.initial })
    }

    @Test
    fun aMobIsAddedATickBeforeItsHeadLands() {
        val ran = run(4)
        for (l in ran.engine.landings) {
            if (l.initial) { assertEquals(l.throwTick + 80, l.landTick); assertEquals(l.landTick - 1, l.spawnTick) }
            else { assertEquals(l.throwTick + 40, l.landTick); assertEquals(l.throwTick + 39, l.spawnTick) }
        }
    }

    @Test
    fun withNobodyAliveEveryLaterLegLeavesOnTheFortyTickGrid() {
        for (seed in 1..20) {
            val ran = run(seed)
            assertTrue(ran.engine.finished, "seed $seed")
            val later = ran.sim.decisions().drop(5)
            assertTrue(later.size >= 11, "seed $seed legs $later")
            assertTrue(later.all { it % 40 == 0 && it >= 440 }, "seed $seed decisions $later")
        }
    }

    @Test
    fun withAMobAliveEveryLaterLegAfterTheFirstLeavesOnTheSixtyTickGrid() {
        // Each later mob lives 400 ticks, so one is always alive on the next decision tick.
        val policy = Policy({ _, add -> add }, { _, add -> add + 400 })
        for (seed in 1..20) {
            val ran = run(seed, policy)
            assertTrue(ran.engine.finished, "seed $seed")
            val later = ran.sim.decisions().drop(5)
            // Leg 0 leaves FAST (everyone is dead by then); the legs after it find the previous mob alive.
            assertTrue(later.drop(1).take(10).all { it % 60 == 0 }, "seed $seed decisions $later")
            assertTrue(later.drop(1).take(10).zipWithNext().all { (a, b) -> b - a >= 60 }, "seed $seed gaps $later")
        }
    }

    @Test
    fun anInitialMobStillAliveOnDoor420MakesTheFirstLaterLegLeaveOnDoor420() {
        val policy = Policy({ _, add -> maxOf(add, 430) }, { _, add -> add })
        assertEquals(420, run(5, policy).engine.firstLaterDecision)
    }

    @Test
    fun aMobKilledOnTheDecisionTickItselfStillCountsAlive() {
        // The last initial mob dies on exactly door+420: alive on that tick, so the leg is SLOW on 420.
        val policy = Policy({ o, add -> if (o == 4) 420 else add }, { _, add -> add })
        val ran = run(5, policy)
        assertEquals(420, ran.engine.firstLaterDecision)
        // One tick earlier it is dead, and the first leg waits for the FAST beat.
        val early = Policy({ o, add -> if (o == 4) 419 else add }, { _, add -> add })
        assertEquals(440, run(5, early).engine.firstLaterDecision)
    }

    @Test
    fun withAllFourDeadEarlyTheFirstLaterLegWaitsForDoor440() {
        assertEquals(440, run(6).engine.firstLaterDecision)
    }

    @Test
    fun theWatcherIsBusyWhileHeFliesSoNoLegStartsInsideAnother() {
        val ran = run(8)
        val decisions = ran.sim.decisions()
        val legLengths = ran.sim.moveTicks.size
        assertTrue(legLengths > 50)
        // Every move tick belongs to exactly one leg: the moves are never reported twice for a tick.
        assertEquals(ran.sim.moveTicks.size, ran.sim.moveTicks.toSet().size)
        assertTrue(decisions.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun throwOrderIsGiantBossThenStandard() {
        val wall = WallPlan.assign(ring(), Random(7))
        val order = WallPlan.throwOrder(wall, Random(8), 11)
        assertEquals(15, order.size)
        assertEquals(WallClass.GIANT, order[0].cls)
        assertEquals(WallClass.BOSS, order[1].cls)
        assertTrue(order.drop(2).all { it.cls == WallClass.STANDARD })
        assertEquals(15, order.map { it.slot }.toSet().size)
    }

    @Test
    fun aFullWallHasEachStandardMobOnce() {
        val wall = WallPlan.assign(ring(), Random(3))
        assertEquals(30, wall.size)
        assertEquals(1, wall.count { it.cls == WallClass.GIANT })
        val std = wall.filter { it.cls == WallClass.STANDARD }
        assertEquals(17, std.size)
        assertEquals(17, std.map { it.mobName }.toSet().size)
        assertEquals(WallPlan.WALL_BOSSES.toSet(), wall.filter { it.cls == WallClass.BOSS }.map { it.mobName }.toSet())
        assertEquals(4, wall.count { it.cls == WallClass.BOSS })
        assertEquals(1, wall.count { it.cls == WallClass.WANDERING_SOUL })
        assertEquals(WallPlan.DECOR_PER_30, wall.count { it.cls == WallClass.DECOR })
    }

    @Test
    fun theLaterHeadsAreDrawnUniformlyFromTheStandardHeadsThatAreLeft() {
        // Over many camps every one of the 17 standard names is thrown about equally often among the later heads.
        val counts = HashMap<String, Int>()
        repeat(3000) { seed ->
            val rng = Random(seed)
            val wall = WallPlan.assign(ring(), rng)
            for (st in WallPlan.throwOrder(wall, rng, 11).drop(2)) counts.merge(st.mobName, 1, Int::plus)
        }
        assertEquals(17, counts.size)
        val mean = counts.values.average()
        assertTrue(counts.values.all { it > mean * 0.85 && it < mean * 1.15 }, "counts $counts")
    }

    @Test
    fun aSmallWallStillGivesAFullOrder() {
        val wall = WallPlan.assign(ring(15), Random(1))
        assertEquals(15, WallPlan.throwOrder(wall, Random(2), 11).size)
    }

    @Test
    fun aMobTheWorldLostIsCountedDeadSoTheCampCannotHang() {
        val rng = Random(9)
        val wall = WallPlan.assign(ring(), rng)
        val spawned = ArrayList<String>()
        val port = object : CampPort { override fun spawn(landing: Landing): String? = "x${spawned.size}".also { spawned += it } }
        val e = CampEngine(home, wall, rng, port, CampConfig(chatCoins = false))
        for (r in 0..4000) { e.step(r, emptySet()); if (e.finished) break }
        assertTrue(e.finished)
        assertNotNull(e.passTick)
    }

    @Test
    fun aMobThatCannotBeSpawnedCountsAsDead() {
        val rng = Random(10)
        val wall = WallPlan.assign(ring(), rng)
        val port = object : CampPort { override fun spawn(landing: Landing): String? = null }
        val e = CampEngine(home, wall, rng, port, CampConfig(chatCoins = false))
        for (r in 0..4000) { e.step(r); if (e.finished) break }
        assertTrue(e.finished)
    }

    @Test
    fun chatCoinsOnlyEverDelay() {
        for (seed in 1..25) {
            val a = run(seed, config = CampConfig(chatCoins = false, wsChance = 0.0)).engine.passTick!!
            val b = run(seed, config = CampConfig(chatCoins = true, wsChance = 0.0)).engine.passTick!!
            assertTrue(b >= a, "seed $seed: $b < $a")
        }
    }

    @Test
    fun headEndIsAStraightLineOfTheRealLength() {
        val stand = Vec3(home.x + 13.0, 75.75, home.z)
        val target = Vec3(home.x, home.y + CampEngine.HEAD_TARGET_RISE, home.z)
        val end = WallPlan.headEnd(stand, target, 16.0)
        assertEquals(16.0, end.dist(stand), 1e-9)
        // an initial head from a 13-block wall lands past the centre, on the far side, not clamped to a radius
        assertTrue(end.x < home.x)
        assertEquals(home.z, end.z, 1e-9)
        val later = WallPlan.headEnd(stand, target, 12.0)
        assertTrue(later.x > home.x)
    }

    @Test
    fun theWatcherSpeaksAndFliesAndHeadsLeaveTheWall() {
        val rng = Random(11)
        val wall = WallPlan.assign(ring(), rng)
        val said = ArrayList<Pair<Int, CampMech.Line>>()
        var moves = 0
        var thrownN = 0
        var flights = 0
        var arrived = 0
        var tick = 0
        val sim = object : CampPort {
            val deaths = HashMap<String, Int>()
            var n = 0
            override fun spawn(landing: Landing): String? = "m${n++}".also { deaths[it] = landing.landTick + 1 }
            override fun say(line: CampMech.Line) { said += tick to line }
            override fun watcherAt(x: Double, y: Double, z: Double, yaw: Float) { moves++ }
            override fun headThrown(station: WallStation) { thrownN++ }
            override fun flyHead(station: WallStation, end: Vec3, ticks: Int) { flights++; assertTrue(ticks == 80 || ticks == 40) }
            override fun headArrived(station: WallStation) { arrived++ }
        }
        val e = CampEngine(home, wall, rng, sim, CampConfig(wsChance = 0.0))
        while (!e.finished && tick < 5000) {
            val alive = sim.deaths.filter { it.value >= tick }.keys
            for ((h, d) in sim.deaths) if (d == tick) e.noteDeath(h, tick)
            e.step(tick, alive.toSet())
            tick++
        }
        assertTrue(e.finished)
        assertEquals(3 to CampMech.Line.INTRO, said.first())
        assertTrue(said.any { it.second == CampMech.Line.HANDLE })
        assertTrue(said.any { it.second == CampMech.Line.SPAWN } && said.any { it.second == CampMech.Line.REACT })
        assertTrue(moves > 50)
        assertEquals(15, thrownN); assertEquals(15, flights)
        assertTrue(arrived in 13..15)
    }

    @Test
    fun everyCountedKillIsReportedOnceOnALoopTickAndTheFifteenthIsTheDefeat() {
        for (seed in 1..30) {
            val ran = run(seed, config = CampConfig(chatCoins = seed % 2 == 0, wsChance = 0.0))
            val e = ran.engine
            assertTrue(e.finished, "seed $seed")
            val totals = ran.sim.counts.map { it.second }
            assertEquals(totals.sorted(), totals, "seed $seed totals only go up")
            assertEquals(totals.toSet().size, totals.size, "seed $seed no total twice")
            assertEquals(15, totals.last(), "seed $seed ends on the 15th")
            assertEquals(15, e.killsCounted)
            assertTrue(ran.sim.counts.all { CampMech.isLoop(it.first) }, "seed $seed counts land on loop ticks")
            // A death is counted on the first loop tick after it.
            val firstDeath = ran.sim.deaths.values.min()
            assertEquals(CampMech.loopTickAfter(firstDeath), ran.sim.counts.first().first, "seed $seed first count")
            // The pass line prints 0 to 20 ticks after the 15th count (the defeat), never before it.
            val defeat = ran.sim.counts.last().first
            assertTrue(e.passTick!! - defeat in 0..20, "seed $seed pass line ${e.passTick} vs defeat $defeat")
        }
    }

    @Test
    fun theWanderingSoulIsNeverReportedAsAKill() {
        val ran = run(5, config = CampConfig(chatCoins = false, wsChance = 1.0), until = 6000)
        assertTrue(ran.engine.finished)
        // 16 mobs came through the room (the extra leg is the Wandering Soul), but the count stops at the 15 that count.
        assertEquals(16, ran.sim.spawned)
        assertEquals(15, ran.sim.counts.last().second)
        assertTrue(ran.sim.counts.none { it.second > 15 })
    }

    @Test
    fun theWanderingSoulIsAnUncountedExtraLegAmongTheLaterThrows() {
        val wall = WallPlan.assign(ring(), Random(5))
        val order = WallPlan.throwOrder(wall, Random(6), 11, wsChance = 1.0)
        assertEquals(16, order.size)
        val soul = order.single { it.cls == WallClass.WANDERING_SOUL }
        assertTrue(order.indexOf(soul) >= 4)
    }

    @Test
    fun aSavedCampPicksUpWhereItStoppedAndFinishesOnTheSameTick() {
        val config = CampConfig(chatCoins = false, wsChance = 0.0, enoughChance = 0.0)
        // A slow policy so mobs are alive across the save point.
        val policy = Policy({ _, add -> add + 60 }, { _, add -> add + 90 })
        for (cut in listOf(150, 430, 700, 900)) {
            val straight = run(12, policy, config)
            val rng = Random(12)
            val wall = WallPlan.assign(ring(), rng)
            val simA = Sim(policy)
            val a = CampEngine(home, wall, rng, simA, config)
            fun drive(e: CampEngine, sim: Sim, from: Int, to: Int) {
                for (r in from..to) {
                    if (e.finished) break
                    sim.now = r
                    val alive = sim.deaths.filter { it.value >= r }.keys.toSet()
                    for ((h, d) in sim.deaths) if (d == r) e.noteDeath(h, r)
                    e.step(r, alive)
                }
            }
            drive(a, simA, 0, cut)
            val saved = a.snapshot()
            // A new engine on the same wall and the saved throw order; the mobs the first one made stay (they are the world's).
            val order = saved.getAsJsonArray("order").map { wall[it.asInt] }
            val simB = Sim(policy).also { it.deaths.putAll(simA.deaths) }
            val b = CampEngine(home, wall, Random(99), simB, config, order)
            assertTrue(b.restore(saved), "cut $cut")
            drive(b, simB, cut + 1, 5000)
            assertTrue(b.finished, "cut $cut")
            assertEquals(straight.engine.passTick, b.passTick, "cut $cut pass tick")
            assertEquals(straight.engine.landings.map { it.throwTick }, b.landings.map { it.throwTick }, "cut $cut throws")
            assertEquals(15, b.killsCounted)
        }
    }

    @Test
    fun aSnapshotThatDoesNotFitTheWallIsRefused() {
        val ran = run(2, until = 200)
        val saved = ran.engine.snapshot()
        val rng = Random(2)
        val other = WallPlan.assign(ring(12), rng)
        val e = CampEngine(home, other, rng, object : CampPort { override fun spawn(landing: Landing): String? = null })
        assertTrue(!e.restore(saved))
        assertEquals(0, e.killsCounted)
    }

    @Test
    fun theChatQueueIsFirstInFirstOut() {
        val lines = CampMech.chatLines(listOf(
            CampMech.Request(10, CampMech.Line.SPAWN), CampMech.Request(10, CampMech.Line.REACT), CampMech.Request(20, CampMech.Line.REACT),
        ), 1000)
        // A reaction asked on the same tick goes first; each line keeps the queue busy for its class's length.
        assertEquals(listOf(10 to CampMech.Line.REACT, 52 to CampMech.Line.SPAWN, 114 to CampMech.Line.REACT), lines.map { it.at to it.line })
        assertEquals(listOf(10, 10, 20), lines.map { it.requested })
    }

    @Test
    fun thePassLineWaitsForABusyQueueOnlyWhenItFreesUpWithinTwentyTicks() {
        val busy = CampMech.chatLines(listOf(CampMech.Request(100, CampMech.Line.SPAWN)), 1000)
        // The queue is busy until 162.
        assertEquals(162, CampMech.passTick(150, busy))
        assertEquals(150 + 0, CampMech.passTick(150, emptyList()))
        // Free after more than 20 ticks: the pass line does not wait for it.
        assertEquals(120, CampMech.passTick(120, busy.map { it }))
        assertEquals(CampMech.LOOP, CampMech.loopTickAfter(0))
        assertEquals(30, CampMech.loopTickAfter(29))
        assertEquals(40, CampMech.loopTickAfter(30))
    }
}
