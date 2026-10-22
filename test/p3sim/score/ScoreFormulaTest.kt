package com.engineerclient.p3sim.score

import com.engineerclient.p3sim.clear.score.EndScreenText
import com.engineerclient.p3sim.clear.score.Grades
import com.engineerclient.p3sim.clear.score.ScoreFormula
import com.engineerclient.p3sim.clear.score.ScoreInputs
import com.engineerclient.p3sim.clear.score.ScoreSchedule
import com.engineerclient.p3sim.pack.runstate.ScoreModel
import com.engineerclient.p3sim.run.api.EndScreen
import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScoreFormulaTest {
    /** An invented model with the SHAPE of a fitted one: 7 points a tile, a small jump at the end, the pack's term weights. */
    private fun model(puzzle: Double? = -9.0, speed: List<Pair<Double, Double>> = listOf(600.0 to 0.0, 900.0 to -28.0)) = ScoreModel(
        200, 220, "lastSidebar", 40.0, 1, DoubleArray(37) { c -> if (c < 35) 7.0 * c else if (c == 35) 250.0 else 264.0 }, "linear", "floor", "max(0, 3*d - 2)", puzzle,
        1.0, 5, 2.0, 1.0, 1.0, true, speed, false, 0.0, JsonObject(), 1.0, 20, null,
    )

    private fun inp(c: Int = 36, found: Int = 40, total: Int = 50, crypts: Int = 0, deaths: Int = 0, unsolved: Int = 0, mimic: Boolean = false, prince: Boolean = false, bat: Boolean = false, sec: Double = 100.0) =
        ScoreInputs(c, found, total, crypts, mimic, prince, bat, deaths, unsolved, sec)

    @Test fun theFittedFormulaAddsItsTermsAndFloors() {
        // 36 tiles: 264. Secrets 40/50 = 80.0%: floor(40 * 80 / 100) = 32. Crypts 7 count 5. Mimic 2, prince 1, bat 1.
        val b = ScoreFormula.fitted(model(), inp(crypts = 7, mimic = true, prince = true, bat = true))
        assertEquals(264.0, b.rooms); assertEquals(32.0, b.secrets); assertEquals(5.0, b.crypts); assertEquals(4.0, b.bonus)
        assertEquals(264 + 32 + 5 + 4, b.total)
    }

    @Test fun theSecretPercentIsRoundedToOneDecimalBeforeTheFloor() {
        assertEquals(66.7, ScoreFormula.secretsPct(2, 3))
        assertEquals(0.0, ScoreFormula.secretsPct(0, 0))
        // 2/3: 66.7% -> floor(26.68) = 26.
        assertEquals(26.0, ScoreFormula.fitted(model(), inp(found = 2, total = 3)).secrets)
    }

    @Test fun deathsCostOneThenTwoEachAfter() {
        assertEquals(0, ScoreFormula.deathPenalty(0)); assertEquals(1, ScoreFormula.deathPenalty(1))
        // max(0, 2d - 1): 1, 3, 5, 7.
        val base = ScoreFormula.fitted(model(), inp()).total
        assertEquals(base - 1, ScoreFormula.fitted(model(), inp(deaths = 1)).total)
        assertEquals(base - 3, ScoreFormula.fitted(model(), inp(deaths = 2)).total)
        assertEquals(base - 5, ScoreFormula.fitted(model(), inp(deaths = 3)).total)
        assertEquals(7, ScoreFormula.deathPenalty(4))
    }

    @Test fun puzzlesCostWhatThePackFittedOrTheUnmeasuredTen() {
        val base = ScoreFormula.fitted(model(), inp()).total
        assertEquals(base - 18, ScoreFormula.fitted(model(-9.0), inp(unsolved = 2)).total)
        assertEquals(base - 20, ScoreFormula.fitted(model(null), inp(unsolved = 2)).total)
    }

    @Test fun theRoomsTableIsInterpolatedBetweenEntries() {
        val m = model()
        assertEquals(3.5, ScoreFormula.interp(m.roomsTable, 0.5))
        assertEquals(264.0, ScoreFormula.interp(m.roomsTable, 40.0))
        assertEquals(0.0, ScoreFormula.interp(m.roomsTable, -2.0))
        assertEquals(7.0 * 10, ScoreFormula.interp(m.roomsTable, 10.0))
    }

    @Test fun theSpeedTermIsZeroUnderTenMinutesAndFallsAfter() {
        val pts = listOf(600.0 to 0.0, 900.0 to -28.0, 2670.0 to -44.0)
        assertEquals(0.0, ScoreFormula.speed(pts, 300.0))
        assertEquals(0.0, ScoreFormula.speed(pts, 600.0))
        assertEquals(-14.0, ScoreFormula.speed(pts, 750.0), 1e-9)
        assertEquals(-28.0, ScoreFormula.speed(pts, 900.0), 1e-9)
        assertEquals(-44.0, ScoreFormula.speed(pts, 5000.0))
        assertEquals(0.0, ScoreFormula.speed(emptyList(), 5000.0))
    }

    @Test fun theScoreNeverGoesBelowZero() {
        assertEquals(0, ScoreFormula.fitted(model(), inp(c = 0, found = 0, total = 50, deaths = 30, unsolved = 5)).total)
    }

    @Test fun withoutAPackThePublishedFormulaIsUsed() {
        val b = ScoreFormula.evaluate(null, inp(c = 36, found = 50, total = 50, crypts = 5, mimic = true, prince = true, bat = true))
        // 40 + 60 + 100 (skill, capped) + 5 + 4 + 100 = 309.
        assertEquals(309, b.total)
        assertEquals(100 + 60 + 100 + 40 + 5 + 4 - 100 + 100, 309)
        assertEquals(ScoreFormula.published(inp()).total, ScoreFormula.evaluate(null, inp()).total)
        // A pack whose table is unusable falls back too.
        val broken = ScoreModel(200, 220, null, 40.0, 1, DoubleArray(0), null, null, null, null, 1.0, 5, 2.0, 1.0, 1.0, false, emptyList(), false, 0.0, JsonObject())
        assertEquals(ScoreFormula.published(inp()).total, ScoreFormula.evaluate(broken, inp()).total)
    }

    /**
     * A synthetic run: rooms done linearly over four minutes, secrets found in proportion, a couple of crypts late. The curve the
     * sidebar would show must rise step by step, start at zero, end in the S+ range and never jump by more than the end-of-run step.
     */
    @Test fun aSyntheticRunGivesARisingCurveEndingInTheSPlusBand() {
        val m = model()
        val curve = ArrayList<Int>()
        val sched = ScoreSchedule(20, 200)
        var last = 0
        for (t in 0..5000) {
            if (!sched.poll(t)) continue
            val c = minOf(36, (t / 130.0).toInt())
            val found = minOf(45, (t / 100.0).toInt())
            val crypts = minOf(5, t / 1000)
            val s = ScoreFormula.fitted(m, inp(c = c, found = found, total = 50, crypts = crypts, unsolved = if (t < 1500) 3 else 0, sec = t / 20.0)).total
            curve += s
            assertTrue(s >= last - 30, "no collapse: $last -> $s at $t")
            last = s
        }
        assertEquals(24, curve.size, "a step every 200 ticks from tick 220 to 4820")
        assertTrue(curve.first() < 100, "starts low: ${curve.first()}")
        assertTrue(curve.last() in 290..320, "ends in the S+ band: ${curve.last()}")
        assertEquals("S+", Grades.letter(curve.last()))
    }

    @Test fun lettersFollowTheThresholds() {
        assertEquals("S+", Grades.letter(300)); assertEquals("S", Grades.letter(299)); assertEquals("S", Grades.letter(270))
        assertEquals("A", Grades.letter(269)); assertEquals("A", Grades.letter(230)); assertEquals("B", Grades.letter(229)); assertEquals("B", Grades.letter(160))
        assertEquals("C", Grades.letter(159)); assertEquals("C", Grades.letter(100)); assertEquals("D", Grades.letter(99)); assertEquals("D", Grades.letter(0))
    }

    // ---- the schedule ---------------------------------------------------------------------------------------------------

    @Test fun theFirstStepIsTwoHundredAndTwentyTicksAfterTheMortLine() {
        val s = ScoreSchedule(20, 200)
        assertEquals(220, s.nextT)
        assertTrue(!s.poll(0) && !s.poll(219))
        assertTrue(s.poll(220))
        assertTrue(!s.poll(220) && !s.poll(419))
        assertTrue(s.poll(420))
        assertEquals(620, s.nextT)
    }

    @Test fun aStallThatSkipsStepsGivesOneRecomputeAndTheGridCarriesOn() {
        val s = ScoreSchedule(20, 200)
        assertTrue(s.poll(220))
        assertTrue(s.poll(700))       // skipped 420 and 620
        assertTrue(!s.poll(700))
        assertEquals(820, s.nextT)
        assertTrue(s.poll(820))
    }

    @Test fun aRestoreMovesTheGridToTheNextStepAfterTheRestoredClock() {
        val s = ScoreSchedule(20, 200)
        s.poll(220); s.poll(420); s.poll(620)
        s.resetTo(300)
        assertEquals(420, s.nextT)
        s.resetTo(5)
        assertEquals(220, s.nextT)
    }

    // ---- the end screen ------------------------------------------------------------------------------------------------

    private fun plain(s: String) = s.replace(Regex("§."), "")

    @Test fun theEndScreenMatchesTheRegexesOdinReads() {
        // These are Odin's own (features/impl/boss/ExtraStats.kt, dungeon/Croesus.kt), applied to the text with the colour codes removed.
        val extra = Regex(" {29}> EXTRA STATS <")
        val team = Regex("^\\s*Team Score: (\\d+) \\((.{1,2})\\)\\s?(\\(NEW RECORD!\\))?$")
        val defeated = Regex("^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$")
        val lines = EndScreenText.lines(EndScreen(304, "S+", 100, 44, 53, 6, 1, 250 * 20), killed = 127).map(::plain)
        assertTrue(lines.any { extra.containsMatchIn(it) }, lines.toString())
        val t = lines.mapNotNull { team.matchEntire(it) }
        assertTrue(t.isNotEmpty() && t.all { it.groupValues[1] == "304" && it.groupValues[2] == "S+" })
        val d = lines.mapNotNull { defeated.matchEntire(it) }
        assertTrue(d.isNotEmpty())
        assertEquals("Maxor, Storm, Goldor, and Necron", d.first().groupValues[1])
        assertEquals("4m 10s", d.first().groupValues[2], "Odin drops the leading zero of 04m 10s")
        assertTrue(lines.any { it.trim() == "Deaths: 1" } && lines.any { it.trim() == "Secrets Found: 44" } && lines.any { it.trim() == "Enemies Killed: 127" })
        assertEquals(64, plain(EndScreenText.RULE).length)
    }

    @Test fun theEndScreenIsColouredAndCentredAsRecorded() {
        val me = com.engineerclient.p3sim.clear.score.EndScreenPlayer("Berserk", 49_476.0, 60, 344_774_384, 4_095, 181, 7)
        val lines = EndScreenText.lines(EndScreen(310, "S+", 100, 44, 53, 6, 0, (3 * 60 + 23) * 20), me = me)
        assertEquals("§a§l" + "▬".repeat(64), lines.first())
        assertTrue("§f" + " ".repeat(24) + "§r§cThe Catacombs §r§8- §r§eFloor VII" in lines, "title")
        assertTrue("§f" + " ".repeat(27) + "Team Score: §r§a310 §r§f(§r§b§lS+§r§f)" in lines, "score line")
        assertTrue("§f" + " ".repeat(5) + "§r§c☠ §r§eDefeated §r§cMaxor, Storm, Goldor, and Necron §r§ein §r§a03m 23s" in lines, "defeated")
        assertTrue("§f" + " ".repeat(29) + "§6> §e§lEXTRA STATS §6<" in lines)
        assertTrue(lines.any { it.endsWith("§r§8+§r§349,476 Catacombs Experience") })
        assertTrue(lines.any { it.endsWith("§r§8+§r§337,601.8 Berserk Experience") })
        assertEquals(4, lines.count { it.endsWith("Experience §r§b(Team Bonus)") })
        assertTrue(lines.any { it.endsWith("Total Damage as Berserk: §r§a344,774,384") })
        assertTrue(lines.any { it.endsWith("Ally Healing: §r§a4,095") })
        assertTrue(lines.any { it.endsWith("Enemies Killed: §r§a181") } && lines.any { it.endsWith("Deaths: §r§c0") } && lines.any { it.endsWith("Secrets Found: §r§b7") })
        // Odin's own regex still reads the personal copy.
        assertTrue(lines.map(::plain).any { Regex("^\\s*Team Score: (\\d+) \\((.{1,2})\\)").containsMatchIn(it) })
    }

    @Test fun gradeLettersHaveTheirColours() {
        assertEquals("§b§l", EndScreenText.letterColour("S+")); assertEquals("§b", EndScreenText.letterColour("S")); assertEquals("§c", EndScreenText.letterColour("D"))
    }

    @Test fun timesPrintAsTheClockDoes() {
        assertEquals("10s", EndScreenText.time(200)); assertEquals("01m 05s", EndScreenText.time(65 * 20)); assertEquals("12m 00s", EndScreenText.time(720 * 20))
        assertNotNull(EndScreenText.lines(EndScreen(0, "D", 0, 0, 0, 0, 0, 0)))
    }
}
