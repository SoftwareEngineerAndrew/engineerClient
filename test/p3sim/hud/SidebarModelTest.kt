package com.engineerclient.p3sim.clear.hud

import com.engineerclient.p3sim.run.CheckMark
import com.engineerclient.p3sim.run.RunStage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sidebar lines against the parsers that read them. The regexes are the ones Odin (floor, cleared percent, time) and Devonian (the
 * lobby party lines) apply to prefix + suffix with the codes stripped.
 */
class SidebarModelTest {
    private val floorRegex = Regex("The Catacombs \\((\\w+)\\)$")
    private val clearedRegex = Regex("^Cleared: (\\d+)% \\(\\d+\\)$")
    private val timeRegex = Regex("^Time Elapsed: (.+)$")
    private val lobbyRegex = Regex("^\\[([ABMHT])] (\\w{1,16}) \\[Lv\\d+]$")

    /** What a client sees for one line: prefix + suffix, colour codes stripped. */
    private fun seen(line: String): String { val (p, s) = HudFormat.splitLine(line); return HudFormat.strip(p + s) }
    private fun visible(lines: List<String>) = lines.map { seen(it) }

    private fun running(): Pair<com.engineerclient.p3sim.run.RunState, HudContext> {
        val st = HudFixtures.state(); HudFixtures.party(st)
        return st to HudFixtures.ctx(st)
    }

    @Test fun runningSidebarHasTheRecordedShapeAndIsParsedByOdin() {
        val (st, ctx) = running()
        val lines = SidebarModel.lines(ctx, HudFixtures.me)
        assertTrue(lines.size <= SidebarModel.MAX_LINES)
        val v = visible(lines)
        assertNotNull(v.firstNotNullOfOrNull { floorRegex.find(it) }, "floor line: $v")
        assertEquals("F7", v.firstNotNullOf { floorRegex.find(it) }.groupValues[1])
        assertTrue(v.any { clearedRegex.matches(it) }, "cleared line: $v")
        assertTrue(v.contains("Time Elapsed: 20s"), v.toString())
        assertTrue(v.last().startsWith("www.hypixel.net"))
        assertEquals("§7${ctx.dateText} §8${HudConstants.LOBBY_SERVER} f7", lines.first())
        assertEquals("  ", lines[1])
        assertEquals(14, lines.size, "date, blank, season, clock, floor, blank, keys, time, cleared, blank, two teammates, blank, footer")
    }

    @Test fun devoniansPatternsReadTheSidebarToo() {
        val (_, ctx) = running()
        val v = visible(SidebarModel.lines(ctx, HudFixtures.me))
        val floor = Regex("^ * . The Catacombs \\((\\w+)\\)$")
        val time = Regex("^Time Elapsed: (?:(\\d+)h)? ?(?:(\\d+)m)? ?(\\d+)s$")
        assertEquals("F7", v.firstNotNullOf { floor.find(it) }.groupValues[1])
        assertEquals("20", v.firstNotNullOf { time.find(it) }.groupValues[3])
        val long = visible(listOf(SidebarModel.lines(HudFixtures.ctx(ctx.state, t = 250 * 20 + 3), HudFixtures.me).first { it.startsWith("Time Elapsed") }))
        assertEquals(listOf("04", "10"), time.find(long[0])!!.groupValues.let { listOf(it[2], it[3]) })
        // Devonian only accepts team names of the form team_N.
        assertTrue((1..15).all { Regex("^team_(\\d+)$").matches(SidebarModel.teamName(it)) })
    }

    @Test fun clearedLineShowsPercentAndTheScoreEstimate() {
        val (st, ctx) = running()
        st.counters.completed = 11
        st.score.score = 53
        assertEquals("Cleared: §c31% §8(53)", SidebarModel.clearedLine(ctx))
        st.counters.completed = 22
        assertEquals("Cleared: §661% §8(53)", SidebarModel.clearedLine(ctx))
        st.counters.completed = 36
        assertEquals("Cleared: §a100% §8(53)", SidebarModel.clearedLine(ctx))
    }

    @Test fun teammatesExcludeTheViewerAndShowHealthOrDead() {
        val (st, ctx) = running()
        st.players[HudFixtures.a]!!.hpPercent = 50.0
        st.players[HudFixtures.b]!!.ghost = true
        val lines = SidebarModel.lines(ctx, HudFixtures.me)
        val v = visible(lines)
        assertFalse(v.any { it.contains("Alpha") }, "the viewer is not listed")
        assertTrue(v.any { it.startsWith("[B] BetaBetaBeta") && it.endsWith("${HudFormat.commas(ctx.hpOf(st.players[HudFixtures.a]!!))}❤") || it.startsWith("[B] BetaBetaBeta") }, v.toString())
        assertTrue(v.any { it == "[H] Gamma DEAD" }, v.toString())
        // Each teammate line fits prefix + suffix.
        for (l in lines) { val (p, s) = HudFormat.splitLine(l); assertTrue(p.length <= 16 && s.length <= 16, l) }
    }

    @Test fun soloShowsNoTeammates() {
        val st = HudFixtures.state()
        HudFixtures.player(st, HudFixtures.me, "Alpha", "Mage")
        val v = visible(SidebarModel.lines(HudFixtures.ctx(st), HudFixtures.me))
        assertTrue(v.contains("Solo"), v.toString())
    }

    @Test fun keysLineStaysUntilTheBossEntryAndTheTickGoesBackToACrossAfterUse() {
        val (st, ctx) = running()
        val l = SidebarModel.lines(ctx, HudFixtures.me)
        assertTrue(l.any { it.startsWith("Keys:") })
        assertEquals("Keys: §c■ §c✗ §8§8■ §a0x", SidebarModel.keysLine(ctx))
        st.keys.witherHeld = 2
        assertTrue(SidebarModel.keysLine(ctx).endsWith("§a2x"))
        st.keys.bloodHeld = true
        assertTrue(SidebarModel.keysLine(ctx).startsWith("Keys: §c■ §a✓"))
        st.milestones.bloodOpenT = 900
        st.keys.bloodHeld = false; st.keys.bloodUsed = true
        val c2 = HudFixtures.ctx(st, RunStage.BLOOD, 900)
        val kept = SidebarModel.lines(c2, HudFixtures.me).first { it.startsWith("Keys:") }
        assertTrue(kept.startsWith("Keys: §c■ §c✗"), "the blood door is open and the key used: the cross is back: $kept")
        assertFalse(SidebarModel.lines(HudFixtures.ctx(st, RunStage.BOSS, 1000), HudFixtures.me).any { it.startsWith("Keys:") })
    }

    @Test fun aKeyCountChangesOnlyOnTheSidebarRefresh() {
        val before = mapOf(8 to "Keys: §c■ §c✗ §8§8■ §a0x", 7 to "Time Elapsed: §a10s")
        val after = mapOf(8 to "Keys: §c■ §c✗ §8§8■ §a1x", 7 to "Time Elapsed: §a11s")
        val held = SidebarModel.holdKeys(before, after, refresh = false)
        assertEquals(before[8], held[8])
        assertEquals(after[7], held[7], "other lines are not held")
        assertEquals(after, SidebarModel.holdKeys(before, after, refresh = true))
        assertEquals(after, SidebarModel.holdKeys(null, after, refresh = false))
    }

    @Test fun countdownShowsTheLobbyLayoutWithAllMembers() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        val ctx = HudFixtures.ctx(st, RunStage.COUNTDOWN, -81)
        val lines = SidebarModel.lines(ctx, HudFixtures.me)
        val v = visible(lines)
        assertEquals(13, lines.size, "two lines fewer than a party of five")
        assertTrue(v.contains("Auto-closing in: 1:56") || v.any { it.startsWith("Auto-closing in: ") }, v.toString())
        assertTrue(lines.none { it.startsWith("Starting in") }, "Starting only shows for the last four seconds")
        val starting = visible(SidebarModel.lines(HudFixtures.ctx(st, RunStage.COUNTDOWN, -61), HudFixtures.me))
        assertTrue(starting.contains("Starting in: 0:04"), starting.toString())
        assertTrue(SidebarModel.lines(HudFixtures.ctx(st, RunStage.COUNTDOWN, -61), HudFixtures.me).filter { lobbyRegex.matches(HudFormat.strip(it)) }.all { it.startsWith("§a[") })
        assertTrue(SidebarModel.lines(ctx, HudFixtures.me).filter { lobbyRegex.matches(HudFormat.strip(it)) }.all { it.startsWith("§c[") })
        for ((left, shown) in listOf(80 to "0:04", 60 to "0:03", 41 to "0:03", 40 to "0:02", 20 to "0:01", 1 to "0:01"))
            assertTrue(visible(SidebarModel.lines(HudFixtures.ctx(st, RunStage.COUNTDOWN, -left), HudFixtures.me)).contains("Starting in: $shown"), "$left ticks left reads $shown")
        assertEquals(3, v.count { lobbyRegex.matches(it) }, v.toString())
        assertNotNull(v.firstNotNullOfOrNull { floorRegex.find(it) })
        assertTrue(v[0].matches(Regex("\\d\\d/\\d\\d/\\d\\d m100AV")), v[0])
    }

    @Test fun elapsedTimeFreezesWhenTheBossIsDead() {
        val (st, _) = running()
        st.milestones.endT = 5000
        val ctx = HudFixtures.ctx(st, RunStage.BOSS, 9000)
        assertEquals(5000, ctx.elapsedTicks)
        assertTrue(SidebarModel.lines(ctx, HudFixtures.me).any { it == "Time Elapsed: §a04m 10s" })
    }

    @Test fun clockLineStartsAtSixTenAndAdvances() {
        assertEquals(" §76:10pm", SidebarModel.clockLine(0))
        assertEquals(" §76:20pm", SidebarModel.clockLine(HudConstants.CLOCK_TICKS_PER_STEP))
        assertEquals(" §77:10pm", SidebarModel.clockLine(HudConstants.CLOCK_TICKS_PER_STEP * 6))
    }

    @Test fun scoreHoldersAndTeamNamesAreUnique() {
        val holders = (1..15).map { SidebarModel.holder(it) }
        assertEquals(15, holders.toSet().size)
        assertEquals("team_8", SidebarModel.teamName(8))
        assertTrue(Regex("^team_(\\d+)$").matches(SidebarModel.teamName(15)))
    }

    @Test fun diffSendsOnlyWhatChanged() {
        val a = SidebarDiff.scored(listOf("top", "mid", "low"))
        assertEquals(mapOf(3 to "top", 2 to "mid", 1 to "low"), a)
        assertTrue(SidebarDiff.diff(a, a).isEmpty)
        val b = SidebarDiff.scored(listOf("top", "mid2", "low"))
        val d = SidebarDiff.diff(a, b)
        assertEquals(listOf(2 to "mid2"), d.change)
        assertTrue(d.add.isEmpty() && d.remove.isEmpty())
        // A line removed from the middle shifts the ones above it down one score.
        val c = SidebarDiff.scored(listOf("top", "low"))
        val e = SidebarDiff.diff(a, c)
        assertEquals(listOf(3), e.remove)
        assertEquals(listOf(2 to "top"), e.change)
        // A new first line adds a score.
        val f = SidebarDiff.diff(c, a)
        assertEquals(listOf(3 to "top"), f.add)
        assertEquals(listOf(2 to "mid"), f.change)
    }

    @Test fun markChangesTheClearedPercentLine() {
        val (st, ctx) = running()
        st.setCheck(1, CheckMark.WHITE)
        st.counters.completed = 2
        assertTrue(visible(SidebarModel.lines(ctx, HudFixtures.me)).contains("Cleared: 6% (0)"))
    }
}
