package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelicsDragonsTest {
    private val necronStart = "[BOSS] Necron: You went further than any human before, congratulations."
    private val extraStats = "                             > EXTRA STATS <"
    private fun at(tick: Int) = Stamp(tick * 50L, tick)

    @Test fun m7NecronRunsToTheRelicsLineAndDragonsToTheEnd() {
        val t = SplitTracker()
        t.onChat(SplitTracker.MORT, at(0))
        t.onChat(necronStart, at(1000))
        t.onChat(SplitTracker.RELICS_PLACED, at(1533))
        t.onChat(extraStats, at(2099))
        assertEquals(at(1533), t.split(SplitTracker.NECRON)!!.stop)
        val dragons = t.split(SplitTracker.DRAGONS)!!
        assertEquals(at(1533), dragons.start)
        assertEquals(at(2099), dragons.stop)
    }

    @Test fun f7HasNoDragons() {
        val t = SplitTracker()
        t.onChat(SplitTracker.MORT, at(0))
        t.onChat(necronStart, at(1000))
        t.onChat(extraStats, at(1411))
        assertEquals(at(1411), t.split(SplitTracker.NECRON)!!.stop)
        assertNull(t.split(SplitTracker.DRAGONS))
    }

    @Test fun relicsStepRunsFromNecronsDeathToTheRelicsLine() {
        val s = SubSplitTracker()
        s.onChat(necronStart, at(1000))
        s.onChat("[BOSS] Necron: ARGH!", at(1270))
        s.onNecronDeath(at(1411))
        s.onChat(SplitTracker.RELICS_PLACED, at(1533))
        val steps = s.forSplit(SplitTracker.NECRON)
        assertEquals(listOf("&dIntro", "&cKill", "&3Relics"), steps.map { it.label })
        val relics = steps.last()
        assertEquals(at(1411), relics.start)
        assertEquals(at(1533), relics.stop)
        assertEquals(at(1411), steps[1].stop)
        assertEquals("necron.relics", s.idsForSplit(SplitTracker.NECRON).last())
    }

    @Test fun m7BandsGradeTheRelicsAndDragons() {
        // Relics: 89 up, p5 101, p25 111, median 121, p75 150, p90 212.
        assertEquals("§2", SubSplitGrades.colour("necron.relics", 100, true, null, "M7", "§3"))
        assertEquals("§e", SubSplitGrades.colour("necron.relics", 121, true, null, "M7", "§3"))
        assertEquals("§0", SubSplitGrades.colour("necron.relics", 300, true, null, "M7", "§3"))
        assertEquals("§a", SubSplitGrades.colour("split.dragons", 540, true, null, "M7", "§d"))
        // Necron's kill on M7: a band per 20-tick check.
        assertEquals("§2", SubSplitGrades.colour("necron.kill", 101, true, null, "M7", "§c"))
        assertEquals("§e", SubSplitGrades.colour("necron.kill", 141, true, null, "M7", "§c"))
        // F7 keeps its own bands; a step M7 alone has isn't graded there.
        assertEquals("§2", SubSplitGrades.colour("split.necron", 420, true, null, "F7", "§c"))
        assertEquals("§c", SubSplitGrades.colour("split.necron", 560, true, null, "M7", "§c"))
    }

    @Test fun m7NecronBestsNeedTheRelicsInThem() {
        // A best from before the change (first line to his death, ~370-450) is under M7's floor now.
        assertEquals(false, SubSplitGrades.canBeBest("split.necron", 420, "M7"))
        assertEquals(true, SubSplitGrades.canBeBest("split.necron", 520, "M7"))
        assertEquals(true, SubSplitGrades.canBeBest("split.necron", 420, "F7"))
    }
}
