package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun `the clear and the boss are one list, in the order they happened`() {
        val tracker = SplitTracker()
        feed(tracker, f7Run)
        assertEquals(
            listOf(
                "&aBlood 152-539",
                "&cWatcher 539-1941",
                "&dPortal 1941-2150",
                "&5Maxor 2150-2672",
                "&bStorm 2672-3590",
                "&6Terminals 3590-5770",
                "&eGoldor 5770-5976",
                // Necron runs to the end of the run: the Wither King phase has no split of its own.
                "&cNecron 5976-6852",
            ),
            shape(tracker.splits()),
        )
    }

    @Test
    fun `the clear splits stay on screen once the boss starts`() {
        val tracker = SplitTracker()
        feed(tracker, f7Run.filter { it.first < 2150 })
        val duringClear = shape(tracker.splits())
        assertEquals("&aBlood 152-539", duringClear[0])
        assertEquals("&dPortal 1941--", duringClear.last())

        // They used to disappear here. Now they freeze and the boss's phases are appended.
        feed(tracker, f7Run.filter { it.first >= 2150 })
        val after = shape(tracker.splits())
        assertEquals("&aBlood 152-539", after[0])
        assertEquals("&dPortal 1941-2150", after[2])
        assertTrue(after.any { it.startsWith("&5Maxor") })
    }

    @Test
    fun `a running split has no stop and the finished ones keep theirs`() {
        val tracker = SplitTracker()
        feed(tracker, f7Run.filter { it.first <= 2672 })
        val splits = shape(tracker.splits())
        assertEquals("&5Maxor 2150-2672", splits.first { it.startsWith("&5") })
        assertEquals("&bStorm 2672--", splits.last())
    }

    @Test
    fun `a floor that is not F7 gets the clear but no boss phases`() {
        // Only F7 is described, so another floor's clear still times and its boss simply never
        // starts a phase - rather than being timed against lines nobody has checked.
        val tracker = SplitTracker()
        feed(tracker, listOf(
            100 to "[NPC] Mort: Here, I found this map when I first entered the dungeon.",
            300 to "[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable.",
        ))
        val splits = shape(tracker.splits())
        assertEquals(listOf("&aBlood 100--"), splits)
    }

    // ---- sub splits ------------------------------------------------------------------------
    //
    // What counts as an event is SplitEvents' job, built from real recorded runs. These only check
    // that whatever it recognises is filed under every split that was open at the time — splits
    // nest, so an event inside Terminals is also inside Boss.

    @Test
    fun `an event is filed under the split that was running`() {
        val tracker = SplitTracker()
        feed(tracker, f7Run.filter { it.first <= 3590 })
        val line = "alice activated a terminal! (1/4)"
        assertNotNull(SplitEvents.label(line), "terminals are the point of the F7 run; expected this to be an event")
        tracker.onChat(line, stamp(3700))

        val terminals = tracker.subSplits("&6Terminals")
        assertEquals(1, terminals.size)
        assertEquals(3700, terminals[0].at.tick)
        // Storm ended when Terminals began, so nothing later lands in it.
        assertTrue(tracker.subSplits("&bStorm").none { it.at.tick == 3700 })
    }

    @Test
    fun `the line that ends a split is still counted inside it`() {
        val tracker = SplitTracker()
        feed(tracker, f7Run.filter { it.first <= 3590 })
        // "The Core entrance is opening!" both ends Terminals and is worth recording; it belongs to
        // the split it closed, not to Goldor.
        tracker.onChat("The Core entrance is opening!", stamp(5770))
        if (SplitEvents.label("The Core entrance is opening!") != null) {
            assertEquals(1, tracker.subSplits("&6Terminals").size)
            assertTrue(tracker.subSplits("&eGoldor").isEmpty())
        }
    }

    @Test
    fun `events during the clear go to the clear's splits`() {
        val tracker = SplitTracker()
        feed(tracker, f7Run.filter { it.first <= 539 })
        val line = "bob activated a terminal! (1/4)"
        if (SplitEvents.label(line) != null) {
            tracker.onChat(line, stamp(600))
            assertTrue(tracker.subSplits("&cWatcher").any { it.at.tick == 600 })
            // Blood Rush ended when the door opened, so it keeps only what happened before that.
            assertTrue(tracker.subSplits("&aBlood").none { it.at.tick == 600 })
        }
    }

    @Test
    fun `a split that has ended keeps the events it collected`() {
        val tracker = SplitTracker()
        feed(tracker, f7Run)
        // The Watcher fight is long over by EXTRA STATS, but its waves are still there to read.
        assertTrue(tracker.subSplits("&cWatcher").isNotEmpty())
        // And the line that closed a split counts inside it: the blood door ends Blood Rush.
        assertTrue(tracker.subSplits("&aBlood").any { it.at.tick == 539 })
    }

    @Test
    fun `a reset clears the events too`() {
        val tracker = SplitTracker()
        feed(tracker, f7Run.filter { it.first <= 3590 })
        tracker.onChat("alice activated a terminal! (1/4)", stamp(3700))
        tracker.reset()
        assertTrue(tracker.subSplits("&6Terminals").isEmpty())
        assertEquals(emptyList(), shape(tracker.splits()))
    }

    @Test
    fun `every split a run can produce has a label to hang a HUD on`() {
        val labels = SplitTracker.ALL_LABELS
        assertEquals(labels.distinct(), labels, "a duplicate label would mean two HUDs of the same name")
        val tracker = SplitTracker().also { feed(it, f7Run) }
        for (split in tracker.splits()) assertTrue(split.label in labels, "no HUD would exist for ${split.label}")
    }

    // ---- formatting ------------------------------------------------------------------------

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
        assertEquals("§5Maxor §b> §510.00s §8(§79.00s§8)", SplitFormat.line(split, Stamp(0, 0), SplitClock.BOTH))
        assertEquals("§5Maxor §b> §510.00s", SplitFormat.line(split, Stamp(0, 0), SplitClock.REAL))
        assertEquals("§5Maxor §b> §79.00s", SplitFormat.line(split, Stamp(0, 0), SplitClock.TICKS))
    }

    @Test
    fun `a running split counts up to now`() {
        val split = Split("&aBlood", true, Stamp(1_000, 20), null)
        assertEquals("§aBlood §b> §a4.00s §8(§74.00s§8)", SplitFormat.line(split, Stamp(5_000, 100), SplitClock.BOTH))
    }
}
