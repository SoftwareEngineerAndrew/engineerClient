package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.*
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.plugin.Seat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Teammates leaping to the door opener: the pass keeps every act's time, lands the leaper by the opener, and (real pack) leaps as often as recorded. */
class LeapsTest {
    private val tables = BotFixtures.tables()
    private val humanParty = listOf(Seat(0, "HEALER", true), Seat(1, "MAGE", false), Seat(2, "BERSERK", false), Seat(3, "ARCHER", false), Seat(4, "TANK", false))

    @Test fun theLinesAreSpelledAsRecorded() {
        assertEquals("Leaped to Bob!", Leaps.line("Bob", 0.1))
        assertEquals("Leaping to Bob", Leaps.line("Bob", 0.85))
        assertEquals("Leaped to Bob", Leaps.line("Bob", 0.95))
        assertEquals(17, Leaps.gap(0.5))
        assertEquals(6, Leaps.gap(0.1))
    }

    @Test fun aLeapLandsByTheOpenerAndRejoinsTheTrackWithTheSameActs() {
        val dungeon = BotFixtures.dungeon()
        var seen = 0
        for (seed in 1L..60L) {
            val plan = ClearPlanner(PlannerInput(dungeon, tables, humanParty, Random(seed))).build()
            for (l in plan.leaps) {
                seen++
                assertTrue(l.seat != 0 && l.to != 0, "people are never moved or leapt to here")
                val me = assertNotNull(plan.track(l.seat)); val them = assertNotNull(plan.track(l.to))
                assertTrue(me.positionAt(l.t).dist(them.positionAt(l.t)) < 2.0, "seed $seed: lands beside the opener (${me.positionAt(l.t)} ${them.positionAt(l.t)} ${me.positionAt(l.t + 1)} legs ${me.legs.filter { it.t1 >= l.t - 2 && it.t0 <= l.t + 2 }.map { "${it.t0}-${it.t1} ${it.a}>${it.b}" }})")
                assertTrue(me.positionAt(l.t - 1).dist(them.positionAt(l.t)) >= Leaps.MIN_BLOCKS - 0.01, "seed $seed: it was far")
                val door = assertNotNull(plan.doors[l.door])
                assertTrue(l.t > door.openT && l.seat != door.seat && l.to == door.seat)
                assertTrue(dungeon.doors[l.door].kind != DoorKind.BLOOD)
            }
            for (tr in plan.tracks) for (i in 1 until tr.legs.size) assertTrue(tr.legs[i].t0 == tr.legs[i - 1].t1, "seed $seed seat ${tr.seat}: legs follow on")
        }
        assertTrue(seen > 0, "the fixture has leaps")
    }

    @Test fun realPackLeapersPerDoorAreAsRecorded() {
        val dir = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts).pool()
            val real = BotTables(set.runstate)
            var doors = 0; var leaps = 0; var runs = 0
            val gaps = ArrayList<Int>()
            for ((i, l) in layouts.withIndex().take(200)) {
                val dungeon = (DungeonPlan.build(l, i.toLong(), set.rooms, set.runstate) as? DungeonPlan.Result.Ok)?.plan ?: continue
                val plan = ClearPlanner(PlannerInput(dungeon, real, humanParty, Random(i.toLong()))).build()
                runs++
                val bloodDoor = dungeon.doors.firstOrNull { it.kind == DoorKind.BLOOD }?.index
                doors += plan.doors.values.count { it.door != bloodDoor }
                leaps += plan.leaps.size
                for (lp in plan.leaps) gaps += lp.t - plan.doors.getValue(lp.door).openT
            }
            val perDoor = leaps.toDouble() / doors
            println("leaps: $runs plans, $doors wither doors, $leaps leaps, ${"%.2f".format(perDoor)} a door, ${"%.2f".format(leaps.toDouble() / runs)} a run, gap median ${gaps.sorted().getOrNull(gaps.size / 2)}")
            assertTrue(perDoor in 0.1..0.65, "leapers per door ${"%.2f".format(perDoor)} (recorded 0.5; the bots crowd the door more than people, so fewer can leap)")
        } finally { Packs.unload() }
    }
}
