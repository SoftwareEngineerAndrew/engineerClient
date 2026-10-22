package com.engineerclient.p3sim.blood

import com.engineerclient.p3sim.clear.blood.BloodExit
import com.engineerclient.p3sim.clear.blood.BloodFlow
import com.engineerclient.p3sim.clear.blood.BloodFlow.Phase
import com.engineerclient.p3sim.clear.blood.BloodFlow.Step
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BloodFlowTest {
    private fun flow(timeout: Int = 0, seed: Int = 1) = BloodFlow(timeout, Random(seed))

    /** Every step answered at [tick] in order, until the flow says nothing more. */
    private fun steps(f: BloodFlow, tick: Int, finished: Boolean = true, contact: Boolean = false): List<Step> {
        val out = ArrayList<Step>()
        while (out.size < 10) { val s = f.step(tick, finished, contact); if (s == Step.NONE) break; out += s }
        return out
    }

    @Test fun aReplaysPortalWaitsForTheRecording() {
        val f = flow().also { it.portalHeld = true }
        f.open(1000)
        f.noteKills(15, 1500)
        assertEquals(listOf(Step.SAY_PASS), steps(f, 1510))
        // Long past the rolled delay: still shut, but the Watcher goes at his own time.
        assertEquals(listOf(Step.REMOVE_WATCHER), steps(f, 1700))
        assertEquals(Phase.PASSED, f.phase)
        f.portalNow()
        assertEquals(listOf(Step.STRIKE, Step.OPEN_PORTAL), steps(f, 1701))
        assertTrue(f.portalOpen)
    }

    @Test fun aReplaysPortalSaysThePassLineFirst() {
        val f = flow().also { it.portalHeld = true }
        f.open(1000)
        assertEquals(Step.NONE, f.step(1200, false, false))
        f.portalNow()
        assertEquals(listOf(Step.SAY_PASS, Step.STRIKE, Step.OPEN_PORTAL), steps(f, 1201, finished = false))
    }

    @Test fun nothingHappensUntilTheDoorOpens() {
        val f = flow()
        assertEquals(Step.NONE, f.step(5, campFinished = true, contact = true))
        assertEquals(Phase.CLOSED, f.phase)
        assertEquals(0, f.ticksSinceOpen(50))
        assertFalse(f.noteKills(15, 5), "kills before the door count for nothing")
    }

    @Test fun thePassLineComesWhenTheCampEnds() {
        val f = flow()
        f.open(1000)
        assertEquals(0, f.ticksSinceOpen(1000))
        assertEquals(Step.NONE, f.step(1000, false, false))
        assertEquals(Step.NONE, f.step(1500, false, false))
        assertEquals(Step.SAY_PASS, f.step(1600, true, false))
        assertEquals(Phase.PASSED, f.phase)
        assertEquals(1600, f.passedAt)
    }

    @Test fun theExitClockStartsAtTheFifteenthKillNotAtThePassLine() {
        val f = flow()
        f.open(0)
        assertFalse(f.noteKills(14, 400))
        assertNull(f.exit)
        assertTrue(f.noteKills(15, 500), "the fifteenth is the defeat")
        val e = assertNotNull(f.exit)
        assertEquals(500, e.defeatTick)
        assertEquals(Step.SAY_PASS, f.step(510, true, false), "the pass line is the camp's own delay, D+10 here")
        // Nothing before the bolt tick, then the bolt, the portal and the removal at the measured ticks.
        assertEquals(emptyList(), steps(f, 510 + 1))
        val strike = e.portalAt - 1
        assertEquals(emptyList(), steps(f, strike - 1).filter { it != Step.REMOVE_WATCHER })
        assertEquals(listOf(Step.STRIKE), steps(f, strike))
        assertEquals(listOf(Step.OPEN_PORTAL), steps(f, e.portalAt))
        assertTrue(f.portalOpen)
    }

    @Test fun theWatcherGoesAtDPlus81WhateverThePortalDelay() {
        for (seed in 1..20) {
            val f = flow(seed = seed)
            f.open(0)
            f.noteKills(15, 1000)
            f.step(1000, true, false)
            assertEquals(emptyList(), steps(f, 1080).filter { it == Step.REMOVE_WATCHER })
            val e = assertNotNull(f.exit)
            val removal = (1081..1100).first { Step.REMOVE_WATCHER in steps(f, it) }
            assertEquals(1081, removal, "D+81 with portalDelay ${e.portalDelay}")
        }
    }

    @Test fun thePortalDelayIsEightyOrNinetyInTheMeasuredShare() {
        val r = Random(11)
        val longs = (1..3600).count { BloodExit.roll(0, r).portalDelay == BloodExit.PORTAL_LONG }
        assertEquals(2400.0, longs.toDouble(), 150.0, "24 of 36 are 90 ticks")
        for (i in 1..200) assertTrue(BloodExit.roll(i, r).portalDelay in listOf(80, 90))
        val e = BloodExit(100, 90)
        assertEquals(190, e.portalAt); assertEquals(189, e.strikeAt); assertEquals(181, e.watcherGoneAt)
    }

    @Test fun theJumpComesOneTickAfterTheFirstContactAndNobodyNeedsToBeNear() {
        val f = flow()
        f.open(0)
        f.noteKills(15, 100)
        steps(f, 100)
        val e = assertNotNull(f.exit)
        steps(f, e.portalAt)
        assertEquals(Phase.PORTAL, f.phase)
        // The portal waits for as long as it takes; no timer sends the party.
        for (t in e.portalAt + 1..e.portalAt + 500) assertTrue(steps(f, t).all { it == Step.REMOVE_WATCHER }, "nothing but the Watcher's removal happens by itself")
        assertEquals(Phase.PORTAL, f.phase)
        val touch = e.portalAt + 600
        assertEquals(Step.NONE, f.step(touch, true, true), "the touch tick starts the lag")
        assertEquals(touch, f.contactAt)
        assertEquals(Step.ENTER, f.step(touch + 1, true, false), "one tick later, even if the body stepped out again")
        assertEquals(Phase.ENTERED, f.phase)
        assertEquals(Step.NONE, f.step(touch + 2, true, true), "the entry happens once")
    }

    @Test fun aBodyAlreadyInThePortalWhenItOpensJumpsOneTickLater() {
        val f = flow()
        f.open(0); f.noteKills(15, 0); steps(f, 0)
        val e = assertNotNull(f.exit)
        steps(f, e.portalAt - 1)
        assertEquals(Step.OPEN_PORTAL, f.step(e.portalAt, true, true))
        assertEquals(Step.NONE, f.step(e.portalAt, true, true))
        assertEquals(Step.ENTER, f.step(e.portalAt + 1, true, true))
    }

    @Test fun aCampThatNeverReportsKillsIsDefeatedWhenItEnds() {
        val f = flow()
        f.open(0)
        assertEquals(Step.SAY_PASS, f.step(700, true, false))
        val e = assertNotNull(f.exit)
        assertEquals(700, e.defeatTick)
        assertEquals(15, f.kills)
    }

    @Test fun aLateCampIsEndedWhenThePortalIsDue() {
        val f = flow()
        f.open(0)
        f.noteKills(15, 100)
        val e = assertNotNull(f.exit)
        assertEquals(Step.NONE, f.step(e.strikeAt - 1, false, false))
        assertEquals(Step.SAY_PASS, f.step(e.strikeAt, false, false), "the camp is over by the time the portal comes")
        assertFalse(f.timedOut)
        assertEquals(Step.STRIKE, f.step(e.strikeAt, false, false))
    }

    @Test fun killsOnlyGoUpAndStopAtFifteen() {
        val f = flow()
        f.open(0)
        f.noteKills(3, 10); f.noteKills(2, 11); f.noteKills(3, 12)
        assertEquals(3, f.kills)
        f.noteKills(99, 13)
        assertEquals(15, f.kills)
        val d = f.exit
        f.noteKills(15, 99)
        assertEquals(d, f.exit, "the defeat is only once")
    }

    @Test fun severalStepsInOneTickComeInOrder() {
        val f = flow()
        f.open(0); f.noteKills(15, 0)
        val e = assertNotNull(f.exit)
        // A lag burst: the flow is asked for the first time long after everything was due.
        val late = e.portalAt + 50
        assertEquals(listOf(Step.SAY_PASS, Step.STRIKE, Step.OPEN_PORTAL, Step.REMOVE_WATCHER), steps(f, late))
    }

    @Test fun aCampThatNeverEndsIsEndedByTheTimeout() {
        val f = flow(timeout = 300)
        f.open(10)
        assertEquals(Step.NONE, f.step(309, false, false))
        assertEquals(Step.SAY_PASS, f.step(310, false, false))
        assertTrue(f.timedOut)
    }

    @Test fun aTimeoutOfZeroMeansNever() {
        val f = flow(timeout = 0)
        f.open(0)
        assertEquals(Step.NONE, f.step(1_000_000, false, true))
        assertFalse(f.timedOut)
    }

    @Test fun expediteSkipsEveryWait() {
        val f = flow()
        f.open(0)
        f.expedite()
        assertEquals(listOf(Step.SAY_PASS, Step.STRIKE, Step.OPEN_PORTAL, Step.REMOVE_WATCHER, Step.ENTER), steps(f, 11, finished = false))
    }

    @Test fun aSnapshotResumesTheTimersAfterTheClockMoved() {
        val f = flow(seed = 3)
        f.open(100)
        f.noteKills(15, 400)
        steps(f, 400)
        val e = assertNotNull(f.exit)
        val s = f.snapshot(450)                  // 350 ticks after the door, 50 after the defeat
        val g = flow(seed = 99)
        g.restore(s, 9000)                       // the absolute tick is now somewhere else
        assertEquals(Phase.PASSED, g.phase)
        assertEquals(350, g.ticksSinceOpen(9000))
        val ge = assertNotNull(g.exit)
        assertEquals(9000 - 50, ge.defeatTick)
        assertEquals(e.portalDelay, ge.portalDelay, "the rolled delay survives")
        assertEquals(15, g.kills)
        assertEquals(listOf(Step.OPEN_PORTAL), steps(g, ge.portalAt).filter { it != Step.STRIKE && it != Step.REMOVE_WATCHER })
    }

    @Test fun aSnapshotKeepsAnOpenPortalAndAContact() {
        val f = flow()
        f.open(0); f.noteKills(15, 10); steps(f, 10)
        val e = assertNotNull(f.exit)
        steps(f, e.portalAt + 5)
        f.step(e.portalAt + 6, true, true)
        val g = flow()
        g.restore(f.snapshot(e.portalAt + 6), 500)
        assertEquals(Phase.PORTAL, g.phase)
        assertTrue(g.portalOpen)
        assertTrue(g.watcherRemoved)
        assertEquals(500, g.contactAt)
        assertEquals(Step.ENTER, g.step(501, true, false))
    }

    @Test fun resetGoesBackToClosed() {
        val f = flow()
        f.open(0); f.noteKills(15, 1); f.step(5, true, true)
        f.reset()
        assertEquals(Phase.CLOSED, f.phase)
        assertEquals(0, f.kills)
        assertNull(f.exit)
        f.open(7)
        assertEquals(Phase.CAMP, f.phase)
    }
}
