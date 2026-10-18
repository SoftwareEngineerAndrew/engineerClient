package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.Excursion
import com.engineerclient.p3sim.clear.bots.Pt
import com.engineerclient.p3sim.clear.bots.StandDuty
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotExcursionTest {
    @Test fun aTripWalksOutWaitsAndWalksBackWithoutEverJumping() {
        val e = Excursion(Pt(5.0, 0.0), holdTicks = 4, speed = 1.0)
        var cur = Pt(0.0, 0.0); val home = Pt(0.0, 1.0)
        var held = 0; var ticks = 0
        while (true) {
            val next = e.step(cur, home) ?: break
            assertTrue(next.dist(cur) <= 1.0 + 1e-9, "no step longer than the walking speed")
            if (next.dist(Pt(5.0, 0.0)) < 1e-9 && e.waiting) held++
            cur = next; ticks++
            assertTrue(ticks < 100)
        }
        assertTrue(held in 3..5, "waits about the hold: $held")
        assertTrue(cur.dist(home) <= 1.0 + 1e-9, "back within a step of where the plan has it")
        assertTrue(ticks in 12..16, "five out, four waiting, five back: $ticks")
    }

    @Test fun releaseSendsTheBotBackAtOnce() {
        val e = Excursion(Pt(2.0, 0.0), holdTicks = 100, speed = 1.0)
        var cur = Pt(0.0, 0.0)
        repeat(3) { cur = assertNotNull(e.step(cur, Pt(0.0, 0.0))) }
        assertTrue(e.waiting)
        e.release()
        var n = 0
        while (e.step(cur, Pt(0.0, 0.0))?.also { cur = it } != null) { n++; assertTrue(n < 20) }
        assertTrue(n <= 3, "back in a few ticks, not after the 100-tick wait: $n")
    }

    @Test fun aBotAlreadyAtTheStandStillWaitsTheHold() {
        val e = Excursion(Pt(1.0, 1.0), holdTicks = 2, speed = 1.0)
        var cur = Pt(1.0, 1.0)
        assertNotNull(e.step(cur, Pt(1.0, 1.0)))
        var n = 0
        while (e.step(cur, Pt(1.0, 1.0)) != null) { n++; assertTrue(n < 10) }
        assertNull(e.step(cur, Pt(1.0, 1.0)))
    }

    @Test fun theNearestBotWithinReachTakesTheStandAndNobodyIsPulledFromFarAway() {
        val bots = mapOf(1 to Pt(0.0, 0.0), 2 to Pt(30.0, 0.0), 3 to Pt(9.0, 4.0))
        assertEquals(3, StandDuty.nearest(bots, Pt(10.0, 0.0), 12.0))
        assertNull(StandDuty.nearest(bots, Pt(100.0, 100.0), 12.0))
        assertEquals(3, StandDuty.nearest(bots, Pt(10.0, 0.0), 6.0), "the one that is near steps on at once")
        assertNull(StandDuty.nearest(bots, Pt(20.0, 0.0), 6.0))
    }

    @Test fun aHumanAtTheStandIsEnoughSoNoBotIsSent() {
        assertTrue(StandDuty.someoneThere(listOf(Pt(5.0, 5.0), Pt(40.0, 40.0)), Pt(6.0, 6.0)))
        assertTrue(!StandDuty.someoneThere(listOf(Pt(40.0, 40.0)), Pt(6.0, 6.0)))
    }
}
