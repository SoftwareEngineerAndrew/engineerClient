package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `Move ends when Maxor is seen starting to move`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", stamp(0))
        assertTrue(!s.waitingForMaxorMove) // not before his intro is over
        s.onChat("[BOSS] Maxor: DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.", stamp(125))
        repeat(47) { s.onServerTick() }
        s.onMaxorMoved(stamp(172))
        assertEquals(listOf("&6Move 0-172", "&5Stun 172--"), shape(s.forSplit(SplitTracker.MAXOR)))
        assertEquals(listOf("his wither seen starting to move", "running"), s.endSources(SplitTracker.MAXOR))
        repeat(40) { s.onServerTick() } // the count does not fire as well
        assertEquals(2, s.forSplit(SplitTracker.MAXOR).size)
    }

    @Test
    fun `not seen moving, Move is counted 46 ticks after his intro`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", stamp(0))
        s.onChat("[BOSS] Maxor: DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.", stamp(125))
        repeat(60) { s.onServerTick() }
        assertEquals(listOf("&6Move 0-171", "&5Stun 171--"), shape(s.forSplit(SplitTracker.MAXOR)))
        assertEquals(listOf("46 server ticks after \"DON'T DISAPPOINT ME, I HAVEN'T...\" - he wasn't seen moving, so counted", "running"), s.endSources(SplitTracker.MAXOR))
    }

    @Test
    fun `a step ended by a later split's line says the moments between were missed`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", stamp(0))
        s.onChat("[BOSS] Storm: Pathetic Maxor, just like expected.", stamp(900))
        assertEquals(listOf("\"Pathetic Maxor, just like expe...\" - the steps between were never seen"), s.endSources(SplitTracker.MAXOR))
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
        s.onEveryoneInCore(stamp(1600), "every teammate seen inside the core")
        assertEquals(listOf("&5Leaps 1500-1600", "&cKill 1600--"), shape(s.forSplit(SplitTracker.GOLDOR)))
        assertEquals(listOf("every teammate seen inside the core", "running"), s.endSources(SplitTracker.GOLDOR))
        assertEquals("the gate destroyed, after the last device", s.endSources(SplitTracker.TERMS).first())
    }

    private fun shape(splits: List<Split>) = splits.map { "${it.label} ${it.start.tick}-${it.stop?.tick ?: "-"}" }
}
