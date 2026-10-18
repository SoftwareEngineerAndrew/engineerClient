package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.*
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.run.DungeonPlan
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The headless gate. On invented tables it checks the machinery (the milestone chain reproduces the tables it is drawn from, the report reads
 * well); with ADS_REAL_PACKS set it runs 500 plans over the real layouts against the recorded splits, the real acceptance check.
 */
class ClearSimTest {
    @Test fun milestonesFollowTheTablesTheyAreDrawnFrom() {
        val tables = BotFixtures.tables()
        val report = ClearSim.gate(listOf(BotFixtures.dungeon()), tables, runs = 500, seed = 3)
        val blood = report.rows.first { it.name == "blood door open" }
        assertNotNull(blood.recorded)
        assertTrue(blood.pass, report.lines().joinToString("\n"))
        val watcher = report.rows.first { it.name == "Watcher done" }
        assertTrue(watcher.pass, report.lines().joinToString("\n"))
        val maxor = report.rows.first { it.name == "Maxor entry" }
        assertTrue(maxor.pass, report.lines().joinToString("\n"))
    }

    @Test fun theReportListsEveryRowAndSaysIfItPassed() {
        val report = ClearSim.gate(listOf(BotFixtures.dungeon()), BotFixtures.tables(), runs = 40, seed = 1)
        val lines = report.lines()
        assertTrue(lines.first().contains("40 plans"))
        assertEquals(5, report.rows.size)
        assertTrue(lines.last().contains("tolerance"))
    }

    @Test fun withNoPackAtAllTheGateStillRuns() {
        val report = ClearSim.gate(listOf(BotFixtures.dungeon()), BotTables(null), runs = 30, seed = 2)
        assertTrue(report.rows.all { it.recorded == null }, "nothing recorded to compare with")
        assertTrue(report.passed)
        assertEquals(30, report.runs)
    }

    @Test fun percentilesAreInterpolated() {
        val p = ClearSim.pctOf(DoubleArray(101) { it.toDouble() })
        assertEquals(listOf(5.0, 25.0, 50.0, 75.0, 95.0), p.toList())
    }

    @Test fun realPackFiveHundredPlansReproduceTheRecordedSplits() {
        val dir = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts).pool()
            val dungeons = layouts.mapIndexedNotNull { i, l -> (DungeonPlan.build(l, i.toLong(), set.rooms, set.runstate) as? DungeonPlan.Result.Ok)?.plan }
            assertTrue(dungeons.size > 50, "only ${dungeons.size} layouts are buildable")
            val scale = System.getenv("ADS_BOT_SCALE")?.toDoubleOrNull() ?: BotTuning.ROOM_WORK_SCALE
            val report = ClearSim.gate(dungeons, BotTables(set.runstate), runs = 500, seed = 11, workScale = scale, areas = PackRoomAreas(set.rooms))
            val text = report.lines().joinToString("\n")
            System.getenv("ADS_REAL_REPORT")?.let { Files.writeString(Path.of(it), text + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND) }
            assertTrue(report.passed, text)
        } finally { Packs.unload() }
    }

    @Test fun realPackOpenersStandBesideTheirDoorsAndAnotherBotUsuallyTakesTheKey() {
        val dir = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts).pool()
            val tables = BotTables(set.runstate)
            val seats = ClearSim.seatsFor(ClearSim.FIVE)
            var doors = 0; var far = 0; var split = 0
            val dists = ArrayList<Double>()
            for ((i, l) in layouts.withIndex().take(150)) {
                val dungeon = (DungeonPlan.build(l, i.toLong(), set.rooms, set.runstate) as? DungeonPlan.Result.Ok)?.plan ?: continue
                val plan = ClearPlanner(PlannerInput(dungeon, tables, seats, Random(i.toLong()))).build()
                val geo = PlanGeometry(dungeon)
                for (d in plan.doors.values) {
                    val act = plan.track(d.seat)!!.acts.filterIsInstance<OpenDoor>().single { it.door == d.door }
                    val away = plan.track(d.seat)!!.positionAt(act.t).dist(geo.doorPt(d.door))
                    doors++; dists += away
                    if (away > BotTuning.OPEN_REACH) far++
                    if (d.keySeat != d.seat) split++
                }
            }
            dists.sort()
            println("openers: $doors doors, distance p5/p50/p95 = ${dists[doors / 20]}, ${dists[doors / 2]}, ${dists[doors * 19 / 20]}; key taken by another bot: ${split.toDouble() / doors}")
            assertEquals(0, far, "no door falls with its opener out of reach")
            assertTrue(split.toDouble() / doors in 0.5..0.9, "recorded: another player takes the key in 77% of doors")
        } finally { Packs.unload() }
    }
}
