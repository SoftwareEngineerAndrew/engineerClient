package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.*
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotEffectsTest {
    @Test fun aStandingBotMakesNoStepSounds() {
        val c = StepClock(Random(1))
        assertTrue((0 until 500).none { c.due(0, it, false) })
    }

    @Test fun aWalkingBotStepsOnceEveryFourteenTicksOrSo() {
        val c = StepClock(Random(2))
        val steps = (0 until 1200).count { c.due(0, it, true) }
        // 1200 ticks / 14 = 86 a minute of walking; a teammate walks in three ticks of four, which is the recorded 65 a minute.
        assertTrue(steps in 76..96, "steps in a minute of walking: $steps")
        assertEquals(14, StepClock.MEAN_GAP_TICKS)
    }

    @Test fun theStepSoundFollowsTheFloorAndIsGlassOtherwise() {
        assertEquals(StepSound.GRAVEL, StepSounds.forFloor("gravel"))
        assertEquals(StepSound.WOOL, StepSounds.forFloor("wool"))
        assertEquals(StepSound.WOOD, StepSounds.forFloor("wood"))
        assertEquals(StepSound.GRASS, StepSounds.forFloor("grass"))
        assertEquals(StepSound.GLASS, StepSounds.forFloor("stone"))
        assertEquals(StepSound.GLASS, StepSounds.forFloor(""))
        assertEquals("block.glass.place", StepSound.GLASS.sound)
    }

    @Test fun stoppingRestartsTheClockSoNoStepFiresTheTickWalkingResumes() {
        val c = StepClock(Random(3))
        for (t in 0 until 100) c.due(0, t, true)
        c.due(0, 100, false)
        assertFalse(c.due(0, 101, true))
    }

    @Test fun teamDeathCountsFollowTheRecordedClears() {
        val r = Random(5)
        val counts = IntArray(4)
        repeat(40000) { counts[TeamDeaths.drawCount(r)]++ }
        assertTrue(Math.abs(counts[0] / 40000.0 - 409 / 433.0) < 0.01, "none: ${counts[0]}")
        assertTrue(Math.abs(counts[1] / 40000.0 - 19 / 433.0) < 0.01, "one: ${counts[1]}")
        assertTrue(counts[2] > 0 && counts[3] == 0, "two at most")
        val mean = (counts[1] + 2 * counts[2]) / 40000.0
        assertTrue(mean in 0.04..0.1, "mean $mean (recorded 0.067 a clear)")
    }

    @Test fun deathsAreSortedAndInsideTheClear() {
        repeat(300) { seed ->
            val s = TeamDeaths.schedule(Random(seed), 1500)
            assertEquals(s.sorted(), s)
            assertTrue(s.all { it in TeamDeaths.EARLIEST_TICK until 1500 })
        }
    }

    @Test fun deathTicksFollowTheRecordedQuantiles() {
        val r = Random(9)
        val ticks = ArrayList<Int>()
        repeat(60000) { ticks += TeamDeaths.schedule(r, 2000) }
        ticks.sort()
        fun q(p: Double) = ticks[(ticks.size * p).toInt()]
        assertEquals(456.0, q(0.25).toDouble(), 70.0); assertEquals(806.0, q(0.5).toDouble(), 70.0); assertEquals(1151.0, q(0.75).toDouble(), 80.0); assertEquals(1748.0, q(0.95).toDouble(), 90.0)
    }

    private class Fight(val inFight: List<Int>, val everyone: List<Int>) : TeamDeath.World {
        val hits = ArrayList<Pair<Int, Double>>(); val kills = ArrayList<Int>(); val hitTicks = ArrayList<Int>(); var now = 0
        val hp = HashMap<Int, Double>(); val dead = HashSet<Int>()
        override fun inFight(random: Random) = inFight.filter { it !in dead }.randomOrNull(random)
        override fun anyone(random: Random) = everyone.filter { it !in dead }.randomOrNull(random)
        override fun alive(seat: Int) = seat !in dead
        override fun hpPercent(seat: Int) = hp.getOrDefault(seat, 100.0)
        override fun hit(seat: Int, percent: Double) { hits += seat to percent; hitTicks += now; hp[seat] = hpPercent(seat) - percent }
        override fun kill(seat: Int) { kills += seat; dead += seat }
        val drops = ArrayList<Int>()
        override fun disconnect(seat: Int) { drops += seat; dead += seat }
    }

    @Test fun aDroppedConnectionHappensAtItsTimeWithNoRunUpAndNoMobToBlame() {
        val w = Fight(listOf(2, 3), listOf(1, 2, 3, 4))
        val d = TeamDeath(300, Random(4), disconnectShare = 1.0)
        for (t in 0 until 300) { w.now = t; d.step(t, w) }
        assertTrue(w.drops.isEmpty() && w.kills.isEmpty() && w.hits.isEmpty(), "nothing before its time")
        w.now = 300; d.step(300, w)
        assertEquals(1, w.drops.size); assertTrue(w.kills.isEmpty() && w.hits.isEmpty()); assertTrue(d.finished)
    }

    @Test fun aboutAFifthOfDeathsAreDroppedConnections() {
        var drops = 0
        val n = 4000
        repeat(n) { seed ->
            val w = Fight(listOf(2), listOf(1, 2))
            val d = TeamDeath(100, Random(seed))
            for (t in 0..700) { w.now = t; d.step(t, w) }
            drops += w.drops.size
        }
        assertEquals(BotHealth.DISCONNECT_SHARE, drops.toDouble() / n, 0.03)
    }

    @Test fun aDeathHitsItsVictimTwoToFourTimesBeforeKillingIt() {
        repeat(80) { seed ->
            val w = Fight(listOf(2, 3), listOf(1, 2, 3, 4))
            val d = TeamDeath(500, Random(seed), disconnectShare = 0.0)
            var killedAt = -1
            for (t in 0..800) { w.now = t; d.step(t, w); if (w.kills.isNotEmpty() && killedAt < 0) killedAt = t }
            assertEquals(1, w.kills.size, "seed $seed")
            assertTrue(killedAt >= 500, "not before its time (seed $seed, tick $killedAt)")
            assertTrue(w.hits.size in 2..4, "hits ${w.hits.size}")
            assertTrue(w.hits.all { it.first == w.kills.single() }, "the hits and the death are one teammate's")
            assertTrue(w.hitTicks.all { it in killedAt - 40 until killedAt }, "hits inside the last 40 ticks: ${w.hitTicks} before $killedAt")
            assertTrue(w.hitTicks.zipWithNext().all { (a, b) -> b - a >= BotHealth.RUN_UP_GAP }, "hits at least 10 ticks apart (the ledger ignores closer ones)")
            assertTrue((w.hp[w.kills.single()] ?: 100.0) > 0.0, "the run-up hits do not kill by themselves")
            assertTrue(d.finished)
        }
    }

    @Test fun aDeathWaitsForAFightAndThenGivesUpAndJustHappens() {
        val w = Fight(emptyList(), listOf(1, 2))
        val d = TeamDeath(300, Random(1), disconnectShare = 0.0)
        for (t in 0 until 300 + BotHealth.VICTIM_PATIENCE - 1) { w.now = t; d.step(t, w) }
        assertTrue(w.kills.isEmpty() && w.hits.isEmpty(), "nobody is fighting: it waits")
        w.now = 300 + BotHealth.VICTIM_PATIENCE; d.step(w.now, w)
        assertEquals(1, w.kills.size); assertTrue(w.hits.isEmpty(), "no run-up without a mob to blame"); assertTrue(d.finished)
    }

    @Test fun aDeathWhoseVictimDiesElsewhereEndsQuietly() {
        val w = Fight(listOf(2), listOf(2))
        val d = TeamDeath(100, Random(2), disconnectShare = 0.0)
        for (t in 60..80) { w.now = t; d.step(t, w) }
        w.dead += 2
        for (t in 81..300) { w.now = t; d.step(t, w) }
        assertTrue(d.finished); assertTrue(w.kills.isEmpty())
    }
}
