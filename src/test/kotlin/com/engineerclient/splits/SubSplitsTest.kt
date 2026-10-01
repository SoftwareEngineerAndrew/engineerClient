package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals

/** The boss sub splits (docs/mechanics/sub-splits.md), fed the moments a live client sees. */
class SubSplitsTest {

    private fun stamp(t: Int) = Stamp(t * 50L, t)

    @Test
    fun `the Watcher's camp - dialogue, wait, camp, clear`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] The Watcher: Things feel a little more roomy now, eh?", stamp(2))
        s.onChat("[BOSS] The Watcher: Let's see how you can handle this.", stamp(468))
        s.onWatcherMoved(stamp(640))
        repeat(18) { s.onBloodMobSpawn(stamp(700 + it * 30)) }
        s.onBloodMobSpawn(stamp(1371))   // the 19th: the last
        s.onChat("[BOSS] The Watcher: You have proven yourself. You may pass.", stamp(1413))
        assertEquals(listOf("&7Dialogue 2-468", "&5Wait 468-640", "&cCamp 640-1371", "&aClear 1371-1413"), shape(s.forSplit(SplitTracker.BLOOD)))
    }

    @Test
    fun `Maxor - crystals, lure, cooldown, kill on the bedrock, animation`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", stamp(0))
        s.onChat("The Energy Laser is charging up!", stamp(195))
        s.onChat("[BOSS] Maxor: YOU TRICKED ME!", stamp(206))
        s.onChat("⚠ Maxor is enraged! ⚠", stamp(220))                 // changes nothing
        s.onTopCrystal(stamp(247))                                       // the same hit, 41 later
        s.onChat("The Energy Laser is charging up!", stamp(300))        // the second charge: nothing
        s.onChat("[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!", stamp(406))
        s.onMaxorKilled(stamp(408))
        s.onChat("[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!", stamp(488)) // a timer, not the death
        s.onChat("[BOSS] Storm: Pathetic Maxor, just like expected.", stamp(510))
        assertEquals(listOf("&dCrystals 0-195", "&6Lure 195-206", "&5Cooldown 206-406", "&cKill 406-408", "&dAnimation 408-510"),
            shape(s.forSplit(SplitTracker.MAXOR)))
    }

    @Test
    fun `a hit an ability keeps quiet shows by the crystals coming back on top`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", stamp(0))
        s.onTopCrystal(stamp(5))                                         // the fresh pair: nothing
        s.onChat("The Energy Laser is charging up!", stamp(195))
        s.onChat("[BOSS] Maxor: YOU TRICKED ME!", stamp(206))
        s.onTopCrystal(stamp(247))                                       // back after hit 1: the same hit
        s.onTopCrystal(stamp(447))                                       // hit 2 at 406, line held back
        s.onChat("[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!", stamp(460)) // the held line: same hit
        s.onMaxorDead(stamp(490))                                       // no beacon seen: kill = 410
        assertEquals(listOf("&dCrystals 0-195", "&6Lure 195-206", "&5Cooldown 206-406", "&cKill 406-410", "&dAnimation 410--"),
            shape(s.forSplit(SplitTracker.MAXOR)))
    }

    @Test
    fun `Storm - opening, crush, pin, flight, crush, kill, animation`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Storm: Pathetic Maxor, just like expected.", stamp(1000))
        s.onStormPosition(stamp(1100), 90.0, 190.0, 60.0)               // still flying his route: not leaving
        s.onChat("[BOSS] Storm: ENERGY HEED MY CALL!", stamp(1548))
        repeat(139) { s.onServerTick() }                                 // not seen leaving: counted
        s.onChat("[BOSS] Storm: THAT WAS ONLY IN MY WAY!", stamp(1690)) // a taunt: nothing
        s.onChat("[BOSS] Storm: Oof", stamp(1699))
        s.onChat("⚠ Storm is enraged! ⚠", stamp(1709))
        s.onStormPosition(stamp(1740), 60.0, 175.0, 65.0)
        s.onStormPosition(stamp(1772), 47.5, 173.0, 65.5)               // within 2.4 of Yellow
        s.onChat("[BOSS] Storm: BEGONE PILLAR!", stamp(1780))           // a taunt: nothing
        s.onChat("[BOSS] Storm: Ouch, that hurt!", stamp(1799))
        s.onChat("[BOSS] Storm: I should have known that I stood no chance.", stamp(1805))
        s.onChat("[BOSS] Goldor: Who dares trespass into my domain?", stamp(1907))
        assertEquals(listOf("&aOpening 1000-1687", "&6Crush 1687-1699", "&cPin 1699-1709", "&bFlight 1709-1772",
            "&6Crush 1772-1799", "&cKill 1799-1805", "&aAnimation 1805-1907"), shape(s.forSplit(SplitTracker.STORM)))
    }

    @Test
    fun `Storm seen leaving his spot, and a death with no second crush line`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Storm: Pathetic Maxor, just like expected.", stamp(0))
        s.onChat("[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!", stamp(548))
        s.onStormPosition(stamp(600), 102.375, 183.0, 52.375)           // parked
        s.onStormPosition(stamp(687), 101.5, 183.0, 52.8)               // gone
        s.onChat("[BOSS] Storm: Oof", stamp(719))
        s.onChat("⚠ Storm is enraged! ⚠", stamp(725))
        s.onChat("[BOSS] Storm: I should have known that I stood no chance.", stamp(830))
        assertEquals(listOf("&aOpening 0-687", "&6Crush 687-719", "&cPin 719-725", "&bFlight 725-830", "&aAnimation 830--"),
            shape(s.forSplit(SplitTracker.STORM)))
        assertEquals("Storm seen leaving his spot", s.endSources(SplitTracker.STORM)[0])
    }

    @Test
    fun `a section ends once its last completion and its gate are both in`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Goldor: Who dares trespass into my domain?", stamp(1000))
        s.onChat("bob activated a terminal! (7/7)", stamp(1100))
        s.onChat("The gate has been destroyed!", stamp(1120))           // gate second
        s.onChat("The gate has been destroyed!", stamp(1200))           // S2's gate first...
        s.onChat("bob activated a lever! (8/8)", stamp(1300))           // ...then its last completion
        assertEquals(listOf("&6S1 1000-1120", "&6S2 1120-1300", "&6S3 1300--"), shape(s.forSplit(SplitTracker.TERMS)))
    }

    @Test
    fun `a section's door opening ends it even when chat hides the lines`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Goldor: Who dares trespass into my domain?", stamp(1000))
        s.onSectionDoor(stamp(1250), 1)
        s.onSectionDoor(stamp(1251), 1)                                  // the same door again: nothing
        s.onChat("bob activated a terminal! (8/8)", stamp(1400))        // no gate yet: still S2
        s.onSectionDoor(stamp(1460), 2)                                  // the gate opened by itself
        s.onSectionDoor(stamp(1600), 3)
        s.onChat("The Core entrance is opening!", stamp(1700))
        s.onEveryoneInCore(stamp(1720), "every teammate seen inside the core")
        assertEquals(listOf("&6S1 1000-1250", "&6S2 1250-1460", "&6S3 1460-1600", "&6S4 1600-1700"), shape(s.forSplit(SplitTracker.TERMS)))
        assertEquals(listOf("&5Leaps 1700-1720", "&cKill 1720--"), shape(s.forSplit(SplitTracker.GOLDOR)))
    }

    @Test
    fun `Necron - intro, two trips and locks, animation`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Necron: You went further than any human before, congratulations.", stamp(0))
        s.onNecronPosition(stamp(100), 0.0)
        s.onNecronPosition(stamp(159), 0.25)                             // the sidestep starts
        s.onNecronPosition(stamp(170), 3.0)
        s.onNecronPosition(stamp(177), 0.0)                              // teleported back
        s.onChat("[BOSS] Necron: ARGH!", stamp(330))
        s.onNecronPosition(stamp(399), 0.7)
        s.onNecronPosition(stamp(404), 0.0)
        s.onChat("[BOSS] Necron: ARGH!", stamp(545))
        s.onChat("[BOSS] Necron: All this, for nothing...", stamp(607))
        assertEquals(listOf("&dIntro 0-170", "&cTrip 170-177", "&aLock 177-330", "&dSpace 330-399", "&cTrip 399-404",
            "&aLock 404-545", "&dAnimation 545-607"), shape(s.forSplit(SplitTracker.NECRON)))
    }

    @Test
    fun `a step ended by a moment further on says the steps between were missed`() {
        val s = SubSplitTracker()
        s.onChat("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", stamp(0))
        s.onChat("[BOSS] Maxor: YOU TRICKED ME!", stamp(206))            // charging line hidden
        assertEquals(listOf("\"YOU TRICKED ME!\" - the steps between were never seen", "running"), s.endSources(SplitTracker.MAXOR))
    }

    private fun shape(splits: List<Split>) = splits.map { "${it.label} ${it.start.tick}-${it.stop?.tick ?: "-"}" }
}
