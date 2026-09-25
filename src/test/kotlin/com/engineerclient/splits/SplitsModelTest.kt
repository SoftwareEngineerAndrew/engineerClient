package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The splits against a real F7 run (recorded 2026-09-23). Ticks stand in for both clocks: one tick
 * is 50ms, so a split's real time and tick time agree.
 */
class SplitsModelTest {

    private fun stamp(tick: Int) = Stamp(tick * 50L, tick)

    private val f7Run = listOf(
        152 to "[NPC] Mort: Here, I found this map when I first entered the dungeon.",
        539 to "The BLOOD DOOR has been opened!",
        542 to "[BOSS] The Watcher: Things feel a little more roomy now, eh?",
        1027 to "[BOSS] The Watcher: Let's see how you can handle this.",
        1941 to "[BOSS] The Watcher: You have proven yourself. You may pass.",
        2010 to "[BOSS] The Watcher: That will be enough for now.",
        2150 to "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!",
        2672 to "[BOSS] Storm: Pathetic Maxor, just like expected.",
        3590 to "[BOSS] Goldor: Who dares trespass into my domain?",
        5770 to "The Core entrance is opening!",
        5976 to "[BOSS] Necron: You went further than any human before, congratulations.",
        6765 to "[BOSS] Necron: All this, for nothing...",
        6852 to "                             > EXTRA STATS <",
    )

    @Test
    fun `a full run splits exactly where EngineerSplits did`() {
        val t = SplitTracker()
        for ((tick, msg) in f7Run) t.onChat(msg, stamp(tick))
        assertEquals(
            listOf(
                "&aOpen 152-542", "&cBlood 542-1941", "&dPortal 1941-2150", "&5Maxor 2150-2672",
                "&bStorm 2672-3590", "&6Terms 3590-5770", "&eGoldor 5770-5976", "&cNecron 5976-6765",
                "&dAnimation 6765-6852",
            ),
            t.splits().map { "${it.label} ${it.start.tick}-${it.stop?.tick}" },
        )
    }

    @Test
    fun `the HUD reads like the original, Pace first and Enter after Portal`() {
        val t = SplitTracker()
        for ((tick, msg) in f7Run.take(7)) t.onChat(msg, stamp(tick))   // up to Maxor starting
        assertEquals(
            listOf(
                "§3Pace §b> §31m 39.9s §8(§71m 39.9s§8)",
                "§aOpen §b> §a19.50s §8(§719.50s§8)",
                "§cBlood §b> §c69.95s §8(§769.95s§8)",
                "§dPortal §b> §d10.45s §8(§710.45s§8)",
                "§9Enter §b> §91m 39.9s §8(§71m 39.9s§8)",
                "§5Maxor §b> §50.00s §8(§70.00s§8)",
            ),
            t.lines(stamp(2150)),
        )
    }

    @Test
    fun `splits appear one at a time as they start`() {
        val t = SplitTracker()
        assertEquals(emptyList(), t.lines(stamp(0)))
        t.onChat(f7Run[0].second, stamp(152))
        assertEquals(listOf("§3Pace", "§aOpen"), t.lines(stamp(200)).map { it.substringBefore(" ") })
    }
}
