package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.Countdown
import com.engineerclient.p3sim.clear.bots.Fall
import com.engineerclient.p3sim.clear.bots.Leg
import com.engineerclient.p3sim.clear.bots.Pt
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountdownTest {
    private val spawn = Pt(-56.5, -22.5)
    private val door = Pt(-55.5, -41.5)

    private fun contiguous(legs: List<Leg>, fromT: Int) {
        assertEquals(fromT, legs.first().t0)
        assertEquals(0, legs.last().t1)
        for (i in 1 until legs.size) assertEquals(legs[i - 1].t1, legs[i].t0, "legs join at $i")
    }

    @Test fun theGateIsTwoBlocksInsideTheDoorAndNoMoreThanAStepToTheSide() {
        repeat(200) { seed ->
            val g = Countdown.gate(door, spawn, Random(seed))
            val along = Math.hypot(g.x - door.x, g.z - door.z)
            assertTrue(along in 1.9..3.0, "gate $along blocks from the door spot")
            // The sideways part is the offset along the wall, perpendicular to the line from the door to the spawn.
            val len = spawn.dist(door)
            val px = (spawn.z - door.z) / len; val pz = -(spawn.x - door.x) / len
            val side = Math.abs((g.x - door.x) * px + (g.z - door.z) * pz)
            assertTrue(side <= Countdown.LATERAL + 0.01, "sideways $side")
        }
    }

    @Test fun theWalkFillsTheCountdownAndEndsAtTheGate() {
        repeat(100) { seed ->
            val r = Random(seed)
            val gate = Countdown.gate(door, spawn, r)
            val legs = Countdown.legs(spawn, gate, -120, r)
            contiguous(legs, -120)
            assertEquals(spawn, legs.first().a)
            assertEquals(gate, legs.last().b)
        }
    }

    @Test fun theBotStandsWhileItFallsThenWalksAtAWalkingPace() {
        val r = Random(4)
        val gate = Countdown.gate(door, spawn, r)
        val legs = Countdown.legs(spawn, gate, -150, r)
        assertTrue(legs.first().a == legs.first().b && legs.first().t1 - legs.first().t0 >= Countdown.FALL_TICKS, "stands while it falls")
        for (l in legs) if (l.a != l.b) {
            val v = l.a.dist(l.b) / (l.t1 - l.t0)
            assertTrue(v in 0.15..Countdown.MAX_BPT + 0.001, "speed $v")
        }
    }

    @Test fun aBotMovesInAboutHalfTheTicksOfARealCountdown() {
        var moving = 0; var all = 0
        repeat(200) { seed ->
            val r = Random(seed)
            val gate = Countdown.gate(door, spawn, r)
            for (l in Countdown.legs(spawn, gate, -150, r)) { all += l.t1 - l.t0; if (l.a != l.b) moving += l.t1 - l.t0 }
        }
        val share = moving.toDouble() / all
        assertTrue(share in 0.4..0.65, "moving share $share (recorded 0.53)")
    }

    @Test fun aShortWindowStillGetsThereAndFillsTheTime() {
        for (window in listOf(1, 3, 10, 25, 50)) {
            val r = Random(window)
            val gate = Countdown.gate(door, spawn, r)
            val legs = Countdown.legs(spawn, gate, -window, r)
            contiguous(legs, -window)
            assertEquals(gate, legs.last().b)
        }
    }

    @Test fun noCountdownIsNoLegs() {
        assertTrue(Countdown.legs(spawn, door, 0, Random(1)).isEmpty())
        assertTrue(Countdown.legs(spawn, door, 12, Random(1)).isEmpty())
    }

    @Test fun aBotAtItsGateAlreadyJustStands() {
        val legs = Countdown.legs(spawn, spawn, -100, Random(2))
        assertEquals(1, legs.size)
        assertEquals(-100, legs[0].t0); assertEquals(0, legs[0].t1)
    }

    @Test fun theDropFromTheSpawnHeightLandsOnTheFloorInAboutTwelveTicks() {
        val f = Fall(75.5)
        var ticks = 0
        var last = 75.5
        while (!f.landed && ticks < 60) { val y = f.step(69.0); assertTrue(y <= last && y >= 69.0, "never up, never below the floor: $y"); last = y; ticks++ }
        assertTrue(f.landed)
        assertTrue(ticks in 9..15, "ticks to land $ticks")
        assertEquals(69.0, f.y)
        assertEquals(69.0, f.step(69.0))
    }
}
