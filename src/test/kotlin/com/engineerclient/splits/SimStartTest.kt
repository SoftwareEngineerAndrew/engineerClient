package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals

class SimStartTest {
    private val before = { l: String -> SplitPace.ref(l)!!.let { SplitTracker.Clock(it.ms, it.ticks.toInt()) } }

    @Test
    fun `a P3 start fills the clear, Maxor and Storm in at pace`() {
        val t = SplitTracker()
        t.startAt(SplitTracker.TERMS, before)
        t.onChat("[BOSS] Storm: Pathetic Maxor, just like expected.", Stamp(1_000, 10))
        assertEquals(emptyList(), t.splits())
        t.onChat("[BOSS] Goldor: Who dares trespass into my domain?", Stamp(100_000, 2000))
        val s = t.splits()
        assertEquals(listOf(SplitTracker.OPEN, SplitTracker.BLOOD, SplitTracker.PORTAL, SplitTracker.MAXOR, SplitTracker.STORM, SplitTracker.TERMS), s.map { it.label })
        val storm = s.first { it.label == SplitTracker.STORM }
        assertEquals(906, storm.stop!!.tick - storm.start.tick)
        assertEquals(10_650, s[0].stop!!.realMs - s[0].start.realMs)
        // The next phase still splits on its line.
        t.onChat("The Core entrance is opening!", Stamp(140_000, 2800))
        assertEquals(800, t.split(SplitTracker.TERMS)!!.stop!!.tick - 2000)
    }

    @Test
    fun `an S3 start is already two sections into terms`() {
        val t = SplitTracker()
        t.startAt(SplitTracker.TERMS, before, Stamp(50_000, 1000), SplitTracker.Clock(18_500, 370))
        assertEquals(31_500, t.split(SplitTracker.TERMS)!!.start.realMs)
    }
}
