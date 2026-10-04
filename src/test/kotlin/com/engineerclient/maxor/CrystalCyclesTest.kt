package com.engineerclient.maxor

import com.engineerclient.maxor.CrystalCycles.From
import com.engineerclient.maxor.CrystalCycles.Side.E
import com.engineerclient.maxor.CrystalCycles.Side.W
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Two real alpha Maxor phases (Boss Recorder files of 2026-10-03, ticks after Maxor's first line on
 * the corrected server clock, tools/boss-mechanics/maxor/bossrec) and one with both crystals in at
 * once, which should make the +70 check.
 */
class CrystalCyclesTest {

    private class Run {
        val cycles = ArrayList<CrystalCycles.Report>()
        var phase: CrystalCycles.Phase? = null
        val m = CrystalCycles({ cycles += it }, { phase = it })
    }

    /** 00-24-45: 264 ticks. Both carriers on the +50 check in cycle 2, kill 3 ticks after the hit. */
    @Test
    fun bestRunOfTheFirstNight() {
        val r = Run()
        with(r.m) {
            start(0)
            picked("AscentPvP", 58); picked("_Cynapse_", 58)
            placed(E, 79); placed(W, 79)
            slotCleared(80)
            beacon(119); hit(119); hit(123) // a flicker inside the same stun
            crystalsBack(159); picked("AscentPvP", 159); picked("_Cynapse_", 159)
            placed(E, 170); placed(W, 170)
            hit(199); kill(202)
            crystalsBack(239)
            end(264)
        }
        assertEquals(2, r.cycles.size)
        val c1 = r.cycles[0]
        assertEquals(From.PYLONS_OPEN, c1.from)
        assertEquals(79, c1.refTick)
        assertEquals(mapOf(E to 0, W to 0), c1.placed)
        assertEquals(0, c1.readyFor)  // charged long before the beacon
        assertEquals(0, c1.hitAt)     // and hit on it
        assertEquals(11, c1.best)
        val c2 = r.cycles[1]
        assertEquals(From.CRYSTALS_BACK, c2.from)
        assertEquals(mapOf(E to 11, W to 11), c2.placed)
        assertEquals(listOf("AscentPvP" to 0, "_Cynapse_" to 0), c2.picked)
        assertEquals(80, c2.readyFor)
        assertEquals(80, c2.hitAt)
        assertEquals(1, c2.best)   // +70 needed both by the tick after the crystals came back
        assertEquals(11, c2.next)  // +80: both by +11 - made it on the last tick
        assertEquals(CrystalCycles.Phase(264, 0, listOf(80), 3, 62), r.phase)
    }

    /** 02-48-38: west placed on the tick after the crystals came back, east 6 later: still +80. */
    @Test
    fun oneCarrierFastOneNot() {
        val r = Run()
        with(r.m) {
            start(0)
            placed(W, 79); placed(E, 79); slotCleared(80)
            beacon(119); hit(119)
            crystalsBack(158); picked("_Cynapse_", 159); picked("AscentPvP", 159)
            placed(W, 159); placed(E, 165)
            hit(200); kill(201)
            end(278)
        }
        val c2 = r.cycles[1]
        assertEquals(mapOf(W to 1, E to 7), c2.placed)
        assertEquals(2, c2.best)
        assertEquals(80, c2.readyFor)
        assertEquals(81, c2.hitAt)
        assertEquals(77, r.phase!!.stormAfterKill) // nobody had dropped into Storm's arena yet
    }

    /** Both crystals in by the hit + 41: charged for the +70 check. */
    @Test
    fun bothInAtOnceMakesSeventy() {
        val r = Run()
        with(r.m) {
            start(0)
            placed(W, 79); placed(E, 79); slotCleared(80)
            beacon(119); hit(119)
            crystalsBack(159); placed(W, 159); placed(E, 160)
            hit(189)
            end(null)
        }
        assertEquals(70, r.cycles[1].readyFor)
        assertEquals(70, r.cycles[1].hitAt)
        assertNull(r.phase) // no Storm line: no phase summary
    }

    /** Late first-cycle crystal: the laser is not ready for the beacon. */
    @Test
    fun lateFirstCycle() {
        val r = Run()
        with(r.m) {
            start(0)
            slotCleared(80); placed(W, 79); placed(E, 92)
            beacon(119)
            hit(129)
            end(null)
        }
        val c1 = r.cycles[0]
        assertEquals(mapOf(W to 0, E to 13), c1.placed)
        assertEquals(10, c1.readyFor) // 92 + 29 = 121 -> the 129 check, 10 after the beacon
        assertEquals(10, c1.hitAt)
    }

    /** A run reset before the hit still reports the crystals placed. */
    @Test
    fun resetBeforeTheHit() {
        val r = Run()
        with(r.m) {
            start(0); slotCleared(80); placed(W, 85)
            end(null)
        }
        assertEquals(1, r.cycles.size)
        assertNull(r.cycles[0].readyFor)
        assertNull(r.cycles[0].hitAt)
    }
}
