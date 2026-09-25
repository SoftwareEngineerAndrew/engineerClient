package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals

/** The 25-step boss breakdown, ported from the team's ChatTriggers module. */
class SubSplitsTest {

    private fun stamp(t: Int) = Stamp(t * 50L, t)

    @Test
    fun `a boss run walks the sequence and files each step under its phase`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", stamp(0))
        s.onChat("[BOSS] Maxor: YOU TRICKED ME!", stamp(100))          // Move -> Stun
        s.onChat("⚠ Maxor is enraged! ⚠", stamp(150))                   // Stun -> Dps
        assertEquals(listOf("&6Move 0-100", "&5Stun 100-150", "&cDps 150--"), shape(s.forSplit(SplitTracker.MAXOR)))

        s.onChat("[BOSS] Storm: Pathetic Maxor, just like expected.", stamp(400))
        s.onChat("[BOSS] Storm: Oof", stamp(600))
        assertEquals(listOf("&aAnimation 400-600", "&6Crush 600--"), shape(s.forSplit(SplitTracker.STORM)))

        // Terminals: a section ends on whichever of the last device and the gate lands second.
        s.onChat("[BOSS] Goldor: Who dares trespass into my domain?", stamp(1000))
        s.onChat("bob activated a terminal! (7/7)", stamp(1100))
        s.onChat("The gate has been destroyed!", stamp(1120))
        assertEquals(listOf("&6S1 1000-1120", "&6S2 1120--"), shape(s.forSplit(SplitTracker.TERMS)))
    }

    @Test
    fun `the gate landing first still closes the section`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", stamp(0))
        s.onChat("[BOSS] Goldor: Who dares trespass into my domain?", stamp(1000))
        s.onChat("The gate has been destroyed!", stamp(1100))
        s.onChat("bob completed a device! (7/7)", stamp(1150))
        assertEquals("&6S1 1000-1150", shape(s.forSplit(SplitTracker.TERMS))[0])
    }

    @Test
    fun `Maxor's shield drop is timed by server ticks, since nothing announces it`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", stamp(0))
        s.onChat("[BOSS] Maxor: DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.", stamp(10))
        repeat(166) { s.onServerTick() }
        assertEquals(listOf("&6Move 0-176", "&5Stun 176--"), shape(s.forSplit(SplitTracker.MAXOR)))
    }

    @Test
    fun `everyone reaching the core ends the leap`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", stamp(0))
        s.onChat("[BOSS] Goldor: Who dares trespass into my domain?", stamp(1000))
        for (i in 0 until 3) {  // S1, S2, S3 done
            s.onChat("bob activated a terminal! (7/7)", stamp(1100 + i * 100))
            s.onChat("The gate has been destroyed!", stamp(1110 + i * 100))
        }
        s.onChat("bob activated a terminal! (7/7)", stamp(1500))  // S4 done -> Leaps
        s.onEveryoneInCore(stamp(1600))
        assertEquals(listOf("&5Leaps 1500-1600", "&cKill 1600--"), shape(s.forSplit(SplitTracker.GOLDOR)))
    }

    private fun shape(splits: List<Split>) = splits.map { "${it.label} ${it.start.tick}-${it.stop?.tick ?: "-"}" }
}
