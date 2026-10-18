package com.engineerclient.p3sim.clear.hud

import com.engineerclient.p3sim.run.PuzzleRecord
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.RunStage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tab rows against the regexes Odin applies to them (`DungeonListener`, `DungeonUtils`). */
class TabModelTest {
    private val member = Regex("^\\[(\\d+)] (?:\\[\\w+] )*(\\w+) .*?\\((\\w+)(?: (\\w+))*\\)$")
    private val puzzle = Regex("^ (\\w+(?: \\w+)*|\\?\\?\\?): \\[([✖✔✦])] ?(?:\\((\\w+)\\))?$")
    private val secretPct = Regex("^ Secrets Found: ([\\d.]+)%$")
    private val completed = Regex("^ Completed Rooms: (\\d+)$")
    private val secretCount = Regex("^ Secrets Found: (\\d+)$")
    private val opened = Regex("^ Opened Rooms: (\\d+)$")
    private val puzzleCount = Regex("^Puzzles: \\((\\d+)\\)$")
    private val deaths = Regex("^Team Deaths: (\\d+)$")
    private val crypts = Regex("^ Crypts: (\\d+)$")
    private val time = Regex("^ Time: ((?:\\d+h ?)?(?:\\d+m ?)?\\d+s)$")

    private fun rows(): Pair<com.engineerclient.p3sim.run.RunState, List<String>> {
        val st = HudFixtures.state(); HudFixtures.party(st)
        st.puzzles += PuzzleRecord(1, "BOULDER", "Boulder").also { it.revealed = true; it.status = PuzzleStatus.SOLVED; it.solvedBy = HudFixtures.a }
        st.puzzles += PuzzleRecord(1, "BEAMS", "Creeper Beams").also { it.revealed = true; it.status = PuzzleStatus.FAILED }
        st.puzzles += PuzzleRecord(1, "QUIZ", "Quiz")
        st.counters.opened = 7; st.counters.completed = 5
        st.secrets.teamMax = 40; st.secrets.teamFound = 17; st.secrets.crypts = 3
        st.deaths.teamDeaths = 2
        st.players[HudFixtures.me]!!.secretsFound = 9
        return st to TabModel.rows(HudFixtures.ctx(st, t = 77 * 20), HudFixtures.me)
    }

    @Test fun thereAreEightyRowsAndNoneCarriesAColourCode() {
        val (_, r) = rows()
        assertEquals(80, r.size)
        assertTrue(r.none { it.contains('§') }, "rows are plain text")
    }

    @Test fun aBotsGlyphReplacesTheSkullAndAnEmptyOneLeavesNone() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        st.players[HudFixtures.a]!!.extras["glyph"] = "§6➶"
        st.players[HudFixtures.b]!!.extras["glyph"] = ""
        val r = TabModel.rows(HudFixtures.ctx(st, t = 77 * 20), HudFixtures.me)
        assertTrue(r.any { it == "[250] Alpha ☠ (Mage L)" }, "no glyph on the record: the skull")
        assertTrue(r.any { it.startsWith("[250] BetaBetaBetaBeta ➶ (") }, "the colour code is dropped and the glyph stays: " + r.filter { it.contains("Beta") })
        assertTrue(r.any { it.startsWith("[250] Gamma (") }, "an empty glyph: nothing between the name and the class")
        val parsed = r.mapNotNull { member.find(it)?.destructured }
        assertEquals(listOf("Alpha", "BetaBetaBetaBeta", "Gamma"), parsed.map { it.component2() }, "Odin still reads all three")
    }

    @Test fun profileNamesFillFourColumnsInOrder() {
        assertEquals("!A-a", TabModel.profileName(0))
        assertEquals("!A-t", TabModel.profileName(19))
        assertEquals("!B-a", TabModel.profileName(20))
        assertEquals("!D-t", TabModel.profileName(79))
        assertEquals(80, (0 until 80).map { TabModel.profileName(it) }.toSet().size)
        assertEquals(80, (0 until 80).map { TabModel.profileId(it) }.toSet().size)
        assertTrue((0 until 80).all { TabModel.profileId(it).version() != 4 }, "fake rows are never version 4 UUIDs")
    }

    @Test fun membersParseAsOdinReadsThem() {
        val (_, r) = rows()
        assertEquals("         Party (3)", r[0])
        val parsed = r.mapNotNull { member.find(it)?.destructured }
        assertEquals(listOf("Alpha", "BetaBetaBetaBeta", "Gamma"), parsed.map { it.component2() })
        assertEquals(listOf("Mage", "Berserk", "Healer"), parsed.map { it.component3() })
        assertEquals("L", parsed[0].component4())
        assertEquals("[250] Alpha ☠ (Mage L)", r[1])
        assertEquals(" Revive Stones: 0", r[3])
        assertEquals("", r[4])
    }

    @Test fun devoniansPatternsReadTheTabToo() {
        val (_, r) = rows()
        val info = Regex("^\\[\\d+] (?:\\[YOUTUBE] )?(\\w+)(?:.+?)? \\((\\w+) ?([0IVXLCDM]+)?\\)$")
        val m = r.mapNotNull { info.find(it)?.groupValues }
        assertEquals(listOf("Alpha", "BetaBetaBetaBeta", "Gamma"), m.map { it[1] })
        assertEquals(listOf("Mage", "Berserk", "Healer"), m.map { it[2] })
        assertEquals("L", m[0][3])
        assertTrue(r.any { Regex("^Discoveries: \\d+$").matches(it) })
        assertTrue(r.any { it.startsWith(" Your Milestone: \u2620") })
    }

    @Test fun theViewerIsFirstWhoeverTheyAre() {
        val (st, _) = rows()
        val r = TabModel.rows(HudFixtures.ctx(st), HudFixtures.b)
        assertTrue(r[1].contains("Gamma"), r[1])
        assertTrue(r[5].contains("Alpha"), r[5])
    }

    @Test fun deadMemberReadsDeadAndAnUnknownClassReadsEmpty() {
        val (st, _) = rows()
        st.players[HudFixtures.b]!!.ghost = true
        HudFixtures.player(st, java.util.UUID(0, 9), "Delta", null)
        val r = TabModel.rows(HudFixtures.ctx(st), HudFixtures.me)
        assertTrue(r.any { it.endsWith("(DEAD)") && it.contains("Gamma") })
        assertTrue(r.any { it.endsWith("(EMPTY)") && it.contains("Delta") })
        assertTrue(r.filter { member.matches(it) }.size == 4)
    }

    @Test fun statRowsParseAsOdinReadsThem() {
        val (_, r) = rows()
        fun num(rx: Regex) = r.firstNotNullOf { rx.find(it)?.groupValues?.get(1) }
        assertEquals("7", num(opened)); assertEquals("5", num(completed)); assertEquals("9", num(secretCount))
        assertEquals("42.5", num(secretPct)); assertEquals("3", num(puzzleCount)); assertEquals("2", num(deaths)); assertEquals("3", num(crypts))
        assertEquals("01m 17s", num(time))
        assertTrue(r.contains("Dungeon: Catacombs"))
        assertEquals(1, r.count { time.matches(it) }, "the downed row is N/A so only one row reads as a time")
    }

    @Test fun puzzlesFollowTheHeaderAsTheRecordedRows() {
        val (_, r) = rows()
        val at = r.indexOf("Puzzles: (3)")
        assertEquals(" Boulder: [✔] ", r[at + 1], "a solved puzzle never shows a name")
        assertEquals(" Creeper Beams: [✖] ", r[at + 2])
        assertEquals(" ???: [✦]", r[at + 3], "an unrevealed row has no trailing space")
        for (i in 1..3) assertTrue(puzzle.matches(r[at + i]), r[at + i])
        assertEquals(" Quiz: [✦] ", TabModel.puzzleRow(true, "Quiz", PuzzleStatus.UNSOLVED, null))
        assertEquals(" Quiz: [✖] (Zed)", TabModel.puzzleRow(true, "Quiz", PuzzleStatus.FAILED, "Zed"), "the failed row carries the one who failed")
    }

    @Test fun blocksStartAtTheirRecordedRows() {
        val (_, r) = rows()
        assertEquals(listOf(0, 20, 25, 40, 47, 60), listOf("         Party (3)", "       Player Stats", "Team Deaths: 2", "       Dungeon Stats", "Puzzles: (3)", "       Account Info").map { r.indexOf(it) })
        assertEquals(listOf(1, 5, 9), r.indices.filter { member.matches(r[it]) })
        assertEquals(" Crypts: 3", r[32])
        assertEquals("Discoveries: 10", r[30])
        assertEquals("", r[4])
        assertEquals("", r[46])
        assertEquals(" Time: 01m 17s", r[45])
    }

    @Test fun percentsDropATrailingZeroDecimal() {
        assertEquals("0%", TabModel.percent(0.0))
        assertEquals("2%", TabModel.percent(2.0))
        assertEquals("12.5%", TabModel.percent(12.5))
        assertEquals("1.8%", TabModel.percent(1.8))
    }

    @Test fun everyMemberReadsEmptyUntilTheRunStarts() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        val before = TabModel.rows(HudFixtures.ctx(st, RunStage.COUNTDOWN, -50), HudFixtures.me)
        assertEquals(listOf(1, 5, 9), before.indices.filter { before[it].endsWith("(EMPTY)") })
    }

    @Test fun ultimateReadsNotAvailableUntilT0AndReadyFromThatTick() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        fun ults(t: Int, stage: RunStage = RunStage.CLEAR) = TabModel.rows(HudFixtures.ctx(st, stage, t), HudFixtures.me).filter { it.startsWith(" Ultimate:") }
        assertEquals(List(3) { " Ultimate: N/A" }, ults(-1, RunStage.COUNTDOWN), "the countdown")
        assertEquals(List(3) { " Ultimate: Ready" }, ults(0, RunStage.CLEAR), "Ready on the first tick of the run, with the class names, not 22 seconds later")
        assertEquals(List(3) { " Ultimate: Ready" }, ults(20 * 600))
    }

    @Test fun ultimateCooldownShowsAClockThatEndsAtReady() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        val me = st.players[HudFixtures.me]!!
        val ctx = HudFixtures.ctx(st, t = 5000)
        fun row() = TabModel.ultimateRow(ctx, me)
        me.extras[TabModel.ULTIMATE_COOLDOWN_KEY] = 254 * 20
        assertEquals(" Ultimate: 04m 14s", row())
        me.extras[TabModel.ULTIMATE_COOLDOWN_KEY] = 42 * 20
        assertEquals(" Ultimate: 42s", row())
        me.extras[TabModel.ULTIMATE_COOLDOWN_KEY] = 19
        assertEquals(" Ultimate: 00s", row(), "the clock reads 00s for the last second, as recorded")
        me.extras[TabModel.ULTIMATE_COOLDOWN_KEY] = 0
        assertEquals(" Ultimate: Ready", row())
        assertTrue(Regex("^ Ultimate: (?:\\d+m ?)?\\d+s$").matches(" Ultimate: 04m 14s"))
    }

    @Test fun reviveStonesCountWhatTheHudCountedForHumansAndTheRecordForBots() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        fun stones(ctx: HudContext) = TabModel.rows(ctx, HudFixtures.me).filter { it.startsWith(" Revive Stones:") }
        assertEquals(List(3) { " Revive Stones: 0" }, stones(HudFixtures.ctx(st)), "everyone starts at 0")
        st.players[HudFixtures.a]!!.extras[TabModel.REVIVE_STONES_KEY] = 2
        st.players[HudFixtures.me]!!.extras[TabModel.REVIVE_STONES_KEY] = 5
        val counted = HudContext(st, RunStage.CLEAR, "F7", 400, true, 550, reviveStones = mapOf(HudFixtures.me to 1))
        assertEquals(listOf(" Revive Stones: 1", " Revive Stones: 2", " Revive Stones: 0"), stones(counted), "the inventory count wins for the human, the record counts for the bot")
        st.players[HudFixtures.b]!!.extras[TabModel.REVIVE_STONES_KEY] = -3
        assertEquals(" Revive Stones: 0", TabModel.rows(counted, HudFixtures.me)[11], "a negative count never prints")
    }

    @Test fun aFailedPuzzleRowNamesWhoFailedItOnlyWhileItStaysFailed() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        st.puzzles += PuzzleRecord(1, "QUIZ", "Quiz").also { it.revealed = true; it.status = PuzzleStatus.FAILED }
        st.puzzles += PuzzleRecord(1, "BLAZE", "Higher Or Lower").also { it.revealed = true; it.status = PuzzleStatus.FAILED }
        st.puzzles += PuzzleRecord(1, "BOULDER", "Boulder").also { it.revealed = true; it.status = PuzzleStatus.SOLVED }
        val ctx = HudContext(st, RunStage.CLEAR, "F7", 400, true, 550, failedBy = mapOf(1 to "Gamma", 2 to "Gamma"))
        val r = TabModel.rows(ctx, HudFixtures.me)
        val at = r.indexOf("Puzzles: (3)")
        assertEquals(" Quiz: [✖] ", r[at + 1], "no name was noted for this one")
        assertEquals(" Higher Or Lower: [✖] (Gamma)", r[at + 2])
        assertEquals(" Boulder: [✔] ", r[at + 3], "a solved puzzle never shows a name")
        for (i in 1..3) assertTrue(puzzle.matches(r[at + i]), r[at + i])
        assertEquals("Gamma", puzzle.find(r[at + 2])!!.groupValues[3], "Odin reads the name")
        st.puzzles[1].status = PuzzleStatus.UNSOLVED
        assertEquals(" Higher Or Lower: [✦] ", TabModel.rows(ctx, HudFixtures.me)[at + 2], "a reset puzzle is Incomplete again, without the name")
    }

    @Test fun preStartTimeReadsSoonAndStatsStillParse() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        val r = TabModel.rows(HudFixtures.ctx(st, RunStage.COUNTDOWN, -50), HudFixtures.me)
        assertTrue(r.contains(" Time: Soon!"))
        assertEquals(80, r.size)
    }

    @Test fun footerListsBlessingsForOdin() {
        val (st, _) = rows()
        val blessing = Regex("Blessing of Power (X{0,3}(IX|IV|V?I{0,3}))")
        assertTrue(blessing.find(TabModel.footer(HudFixtures.ctx(st))) == null)
        assertTrue(TabModel.footer(HudFixtures.ctx(st)).contains("No Buffs active"))
        st.secrets.blessings["power"] = 5
        st.secrets.blessings["Life"] = 2
        val f = TabModel.footer(HudFixtures.ctx(st))
        assertEquals("V", blessing.find(HudFormat.strip(f))!!.groupValues[1])
        assertTrue(HudFormat.strip(f).contains("Blessing of Life II"))
    }

    @Test fun extraRowsAreAdded() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        val r = TabModel.rows(HudContext(st, RunStage.CLEAR, "F7", 10, true, 10, extraTab = listOf(" Extra: 1")), HudFixtures.me)
        assertTrue(" Extra: 1" in r)
    }
}
