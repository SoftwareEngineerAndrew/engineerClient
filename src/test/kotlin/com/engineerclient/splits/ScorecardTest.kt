package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scorecard against a run shaped like the recorded 2026-09-23_13-41-18 (Maxor, Storm and
 * Goldor lines at those ticks). Real time is tick * 50 plus lag where a test needs the clocks apart.
 */
class ScorecardTest {

    private fun stamp(t: Int, lagMs: Long = 0) = Stamp(t * 50L + lagMs, t)
    private fun split(label: String, from: Int, to: Int?, lagMs: Long = 0) = Split(label, stamp(from), to?.let { stamp(it, lagMs) })

    @Test
    fun `blood is Devonian's dialog, move and clear`() {
        // The recorded run 2026-09-28_18-51-20: his first line 843, "handle this" 1310, his first
        // move 45+ ticks after it 1404, "You have proven yourself" 2339.
        val card = Scorecard()
        card.onWatcherMoved(stamp(1083), "seen") // during the dialog: not the move
        card.onChat("[BOSS] The Watcher: Let's see how you can handle this.", stamp(1310))
        card.onWatcherMoved(stamp(1404), "seen")
        card.onWatcherMoved(stamp(1500), "seen") // only the first counts
        val row = card.rows(listOf(split(SplitTracker.BLOOD, 843, 2339)), stamp(2339), emptyList(), true, emptyList())
        assertEquals(listOf("§c74.8\t§723.4\t§54.7\t§c46.8"), row)
    }

    @Test
    fun `blood rush is real time, its rooms ticks, and their average once the rush is over`() {
        val card = Scorecard()
        val splits = listOf(split(SplitTracker.OPEN, 0, 400, lagMs = 500))
        // 20.5 s real (the lag shows), rooms of 120 and 100 ticks.
        assertEquals(listOf("§a20.5\t§c6.0\t§c5.0"), card.rows(splits, stamp(400), listOf(120L, 100L), false, emptyList()))
        assertEquals(listOf("§a20.5\t§c6.0\t§c5.0\t§65.5"), card.rows(splits, stamp(400), listOf(120L, 100L), true, emptyList()))
    }

    @Test
    fun `portal is when it opened, then how long until the boss`() {
        val card = Scorecard()
        card.onPortal(stamp(2389))
        val rows = card.rows(listOf(split(SplitTracker.PORTAL, 2310, 2391)), stamp(2391), emptyList(), true, emptyList())
        assertEquals(listOf("§d4.1\t§54.0\t§60.1"), rows)
    }

    @Test
    fun `maxor is crystals placed, stunned, crystals placed again, on ticks`() {
        val card = Scorecard()
        for ((t, line) in listOf(
            2460 to "1/2 Energy Crystals are now active!", 2560 to "1/2 Energy Crystals are now active!",
            2600 to "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!",
            2690 to "1/2 Energy Crystals are now active!", 2700 to "1/2 Energy Crystals are now active!",
        )) card.onChat(line, stamp(t))
        card.onMaxorGone(stamp(2928))
        val rows = card.rows(listOf(split(SplitTracker.MAXOR, 2390, 2950, lagMs = 900)), stamp(2950), emptyList(), true, emptyList())
        // 28.0 on ticks even with 0.9 s of lag; both crystals at 2560, stun 2 s later, second pair 5 s
        // after; Maxor dead 26.9 s after he started.
        assertEquals(listOf("§528.0\t§38.5\t§62.0\t§35.0\t§c26.9"), rows)
    }

    @Test
    fun `storm is move to crush, dps, to the next crush, and the last dps to his death`() {
        val card = Scorecard()
        card.onChat("[BOSS] Storm: ENERGY HEED MY CALL!", stamp(3500))
        card.onChat("[BOSS] Storm: Ouch, that hurt!", stamp(3650))
        card.onStormMoved(stamp(3700))
        card.onChat("[BOSS] Storm: Oof", stamp(3750))
        card.onChat("[BOSS] Storm: I should have known that I stood no chance.", stamp(3760))
        val rows = card.rows(listOf(split(SplitTracker.STORM, 2950, 3870)), stamp(3870), emptyList(), true, emptyList())
        assertEquals(listOf("§b46.0\t§67.5\t§c2.5\t§62.5\t§c0.5"), rows)
    }

    @Test
    fun `a third crush turns the last storm split dark red`() {
        val card = Scorecard()
        card.onChat("[BOSS] Storm: ENERGY HEED MY CALL!", stamp(3500))
        card.onChat("[BOSS] Storm: Oof", stamp(3600))
        card.onChat("⚠ Storm is enraged! ⚠", stamp(3700))
        card.onChat("[BOSS] Storm: Oof", stamp(3800))
        card.onChat("[BOSS] Storm: Oof", stamp(4000))
        card.onChat("[BOSS] Storm: I should have known that I stood no chance.", stamp(4010))
        val row = card.rows(listOf(split(SplitTracker.STORM, 2950, 4100)), stamp(4100), emptyList(), true, emptyList())[0]
        assertEquals("§410.5", row.split('\t').last())
    }

    @Test
    fun `terms are real time sections, goldor ticks`() {
        val card = Scorecard()
        val terms = listOf(split("&6S1", 3868, 4100, 300), split("&6S2", 4100, 4300), split("&6S3", 4300, 4500), split("&6S4", 4500, 4700))
        card.onEveryoneInCore(stamp(4720), "test")
        card.onGoldorHit(stamp(4760), "test")
        card.onChat("[BOSS] Goldor: ....", stamp(4880))
        val rows = card.rows(listOf(split(SplitTracker.TERMS, 3868, 4700), split(SplitTracker.GOLDOR, 4700, 4880)), stamp(4880), emptyList(), true, terms)
        assertEquals("§641.6\t§811.9\t§810.0\t§810.0\t§810.0", rows[0])
        assertEquals("§e9.0\t§51.0\t§32.0\t§c6.0", rows[1])
    }

    @Test
    fun `a moment not seen leaves its cell empty so the columns stay in line`() {
        val card = Scorecard()
        card.onChat("[BOSS] Storm: Oof", stamp(3650)) // no lightning seen: when he started moving is unknown
        card.onStormMoved(stamp(3700))
        val row = card.rows(listOf(split(SplitTracker.STORM, 2950, null)), stamp(3710), emptyList(), true, emptyList())[0]
        assertEquals(listOf("§b38.0", "", "§c2.5"), row.split('\t'))
    }

    @Test
    fun `necron is from leaving mid to first back on it`() {
        val card = Scorecard()
        card.onNecronOffMid(stamp(5390))
        card.onNecronBackAtMid(stamp(5430))
        card.onNecronBackAtMid(stamp(5600)) // only the first return counts
        val rows = card.rows(listOf(split(SplitTracker.NECRON, 5229, 5835)), stamp(5835), emptyList(), true, emptyList())
        assertEquals(listOf("§c30.3\t§a2.0"), rows)
    }

    @Test
    fun `a boss split's graded cells replace the scorecard's own`() {
        val card = Scorecard()
        val storm = split(SplitTracker.STORM, 2950, 4100)
        val rows = card.rows(listOf(storm), stamp(4100), emptyList(), true, emptyList()) { s ->
            if (s.label == SplitTracker.STORM) listOf("§c46.40", "§734.35", "§20.60") else null
        }
        assertEquals("§c46.40\t§734.35\t§20.60", rows[0])
    }
}
