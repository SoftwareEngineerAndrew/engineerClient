package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The splits are checked against a real F7 run — the one recorded on 2026-09-23 that the Better PF
 * viewer replays — so the sections here start and stop on exactly the ticks the viewer's own splits
 * do. Ticks stand in for both clocks: one tick is 50ms, so a section's tick count and its real time
 * agree and either can be read off the assertions.
 */
class SplitsModelTest {

    private fun stamp(tick: Int) = Stamp(tick * 50L, tick)

    /** Every line of that run that starts or stops a split, on the tick it arrived. */
    private val f7Run = listOf(
        152 to "[NPC] Mort: Here, I found this map when I first entered the dungeon.",
        539 to "The BLOOD DOOR has been opened!",
        542 to "[BOSS] The Watcher: Things feel a little more roomy now, eh?",
        956 to "[BOSS] The Watcher: Go and live again!",
        1027 to "[BOSS] The Watcher: Let's see how you can handle this.",
        1099 to "[BOSS] The Watcher: Not bad.",
        1728 to "[BOSS] The Watcher: Go, fight!",
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

    private fun feed(tracker: SplitTracker, lines: List<Pair<Int, String>>) {
        for ((tick, msg) in lines) tracker.onChat(msg, stamp(tick))
    }

    /** "label start-stop", with "-" for a section still running. */
    private fun shape(splits: List<Split>) = splits.map {
        "${it.label} ${it.start.tick}-${it.stop?.tick ?: "-"}"
    }

    @Test
    fun `the clear is timed from Mort to the boss`() {
        val tracker = SplitTracker()
        feed(tracker, f7Run.filter { it.first < 2150 })
        assertEquals(
            listOf(
                "&4Blood 152-539",
                "&cWatcher Dialog 539-1027",
                "&cWatcher 539-1941",
                "&dPortal Enter 1941--",
                "&9Boss Entry 152--",
            ),
            shape(tracker.runSplits()),
        )
    }

    @Test
    fun `the clear HUD stands down once the boss starts`() {
        val tracker = SplitTracker()
        feed(tracker, f7Run)
        assertEquals(emptyList(), shape(tracker.runSplits()))
        assertEquals(emptyList(), shape(tracker.watcherSplits()))
    }

    @Test
    fun `the Watcher fight is timed from the blood door`() {
        val tracker = SplitTracker()
        // Seen moving during the fight, well before it lets you pass.
        feed(tracker, f7Run.filter { it.first <= 1027 })
        tracker.onWatcherMove(stamp(1100))
        feed(tracker, f7Run.filter { it.first in 1028 until 2150 })
        assertEquals(
            listOf("&cWatcher Dialog 539-1027", "&cWatcher Move 539-1100", "&cWatcher 539-1941"),
            shape(tracker.watcherSplits()),
        )
    }

    @Test
    fun `the Watcher move split falls back to the blessing when it was never seen moving`() {
        val tracker = SplitTracker()
        feed(tracker, f7Run.filter { it.first < 2150 })
        assertEquals("&cWatcher Move 539-1941", shape(tracker.watcherSplits())[1])
    }

    @Test
    fun `F7 phases run from one boss line to the next`() {
        val tracker = SplitTracker()
        feed(tracker, f7Run)
        assertEquals(
            listOf(
                "&5Maxor 2150-2672",
                "&9Storm 2672-3590",
                "&6Terminals 3590-5770",
                "&eS1 3590--",
                "&8Goldor 5770-5976",
                "&4Necron 5976-6765",
                "&4Boss 2150-6852",
            ),
            shape(tracker.bossSplits()),
        )
    }

    @Test
    fun `Wither King is a master-only split`() {
        val normal = SplitTracker().also { feed(it, f7Run) }
        assertTrue(normal.bossSplits().none { it.label == "&0Wither King" })

        val master = SplitTracker().also { it.master = true; feed(it, f7Run) }
        assertEquals("&0Wither King 6765-6852", shape(master.bossSplits()).first { it.startsWith("&0") })
    }

    @Test
    fun `a running split has no stop and the finished ones keep theirs`() {
        val tracker = SplitTracker()
        feed(tracker, f7Run.filter { it.first <= 2672 })
        val boss = tracker.bossSplits()
        assertEquals("&5Maxor 2150-2672", shape(boss)[0])
        assertEquals("&9Storm 2672--", shape(boss)[1])
        assertEquals("&4Boss 2150--", shape(boss).last())
    }

    @Test
    fun `terminal sections close on the gate and hand over to the next`() {
        val tracker = SplitTracker()
        feed(tracker, f7Run.filter { it.first < 3590 })
        // A whole S1: four terminals, both levers, the device, then the gate.
        feed(tracker, listOf(
            3600 to "alice activated a terminal! (1/4)",
            3640 to "bob activated a terminal! (2/4)",
            3700 to "carol activated a terminal! (3/4)",
            3760 to "dave activated a terminal! (4/4)",
            3800 to "alice activated a lever! (1/2)",
            3820 to "bob activated a lever! (2/2)",
            3860 to "carol completed a device! (1/1)",
            3900 to "The gate has been destroyed!",
            3960 to "dave activated a terminal! (1/5)",
        ))
        val sections = tracker.bossSplits().filter { it.sub }
        assertEquals(listOf("&eS1 3600-3900", "&eS2 3900--"), shape(sections))
        // Terminals itself is still running: Goldor has not opened the core yet.
        assertEquals("&6Terminals 3600--", shape(tracker.bossSplits()).first { it.startsWith("&6") })
    }

    @Test
    fun `Devonian's time format, including its truncated fraction`() {
        assertEquals("9.35s", SplitFormat.time(9350, false))
        assertEquals("1m 27.01s", SplitFormat.time(87010, false))
        assertEquals("1h 02m 03.45s", SplitFormat.time(3723450, false))
        assertEquals("31.24s", SplitFormat.time(31240, true))
        // The fraction is rounded on its own and never carries into the seconds.
        assertEquals("12.00s", SplitFormat.time(12996, false))
        assertEquals("-2.50s", SplitFormat.time(-2500, false))
    }

    @Test
    fun `a split line shows both clocks, the tick one lagging`() {
        val split = Split("&5Maxor", false, Stamp(0, 0), Stamp(10_000, 180))
        assertEquals("§5Maxor§r§f: §a10.00s §7(§b9.00s§7)", SplitFormat.line(split, Stamp(0, 0), SplitClock.BOTH))
        assertEquals("§5Maxor§r§f: §a10.00s", SplitFormat.line(split, Stamp(0, 0), SplitClock.REAL))
        assertEquals("§5Maxor§r§f: §b9.00s", SplitFormat.line(split, Stamp(0, 0), SplitClock.TICKS))
    }

    @Test
    fun `a running split counts up to now`() {
        val split = Split("&4Blood", true, Stamp(1_000, 20), null)
        assertEquals("§4Blood§r§f: §a4.00s §7(§b4.00s§7)", SplitFormat.line(split, Stamp(5_000, 100), SplitClock.BOTH))
    }

    @Test
    fun `an unrelated floor's boss line is ignored`() {
        val tracker = SplitTracker()
        feed(tracker, listOf(
            100 to "[NPC] Mort: Here, I found this map when I first entered the dungeon.",
            200 to "Party > [MVP+] someone: bonzo is dead lol",
            300 to "[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable.",
        ))
        assertEquals("&cFirst Phase 300--", shape(tracker.bossSplits())[0])
    }
}
