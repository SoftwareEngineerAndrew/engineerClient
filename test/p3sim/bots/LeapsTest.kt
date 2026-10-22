package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.*
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.plugin.BloodAssignment
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
            for (l in plan.leaps.filter { it.door != CampLeaps.NO_DOOR }) {
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

    @Test fun campLeapsLandBesideTheCamperBeforeTheProvenLine() {
        val dungeon = BotFixtures.dungeon()
        var seen = 0
        CampLeaps.enabled = true
        try { for (seed in 1L..80L) {
            val plan = ClearPlanner(PlannerInput(dungeon, tables, humanParty, Random(seed), assignment = BloodAssignment(4))).build()
            for (l in plan.leaps.filter { it.door == CampLeaps.NO_DOOR }) {
                seen++
                val ms = plan.milestones
                assertEquals(ms.bloodSeat, l.to)
                assertTrue(l.t > ms.bloodOpenT && l.t < ms.watcherDoneT, "seed $seed: in the camp")
                val me = assertNotNull(plan.track(l.seat)); val camper = assertNotNull(plan.track(l.to))
                assertTrue(me.positionAt(l.t).dist(camper.positionAt(l.t)) < 2.0, "seed $seed: lands beside the camper")
                assertTrue(me.positionAt(l.t - 1).dist(camper.positionAt(l.t)) >= Leaps.MIN_BLOCKS - 0.01, "seed $seed: it was far")
                assertTrue(me.acts.none { it.until >= l.t }, "seed $seed: its work was done")
            }
        } } finally { CampLeaps.enabled = false }
        assertTrue(seen > 0, "the fixture has camp leaps")
    }

    @Test fun botsLeapToAPersonWhoOpenedADoor() {
        val dungeon = BotFixtures.dungeon()
        val geo = PlanGeometry(dungeon)
        val door = dungeon.pathDoors.first { it.kind == DoorKind.WITHER }
        var toPerson = 0
        for (seed in 1L..60L) {
            val first = ClearPlanner(PlannerInput(dungeon, tables, humanParty, Random(seed))).build()
            val t = first.doors.getValue(door.index).openT - 5
            val person = PersonDoor(door.index, 0, t, geo.doorPt(door.index))
            val plan = ClearPlanner(PlannerInput(dungeon, tables, humanParty, Random(seed), people = listOf(person))).build()
            for (l in plan.leaps.filter { it.to == 0 && it.door != CampLeaps.NO_DOOR }) {
                toPerson++
                assertTrue(l.t > t && l.door == door.index)
                assertTrue(assertNotNull(plan.track(l.seat)).positionAt(l.t).dist(person.at) < 2.0, "seed $seed: lands beside the person")
            }
        }
        assertTrue(toPerson > 0, "somebody leaps to the person")
    }

    @Test fun realPackLeapersPerDoorAreAsRecorded() {
        val dir = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts).pool()
            val real = BotTables(set.runstate)
            var doors = 0; var leaps = 0; var runs = 0; var camp = 0
            val gaps = ArrayList<Int>()
            for ((i, l) in layouts.withIndex().take(200)) {
                val dungeon = (DungeonPlan.build(l, i.toLong(), set.rooms, set.runstate) as? DungeonPlan.Result.Ok)?.plan ?: continue
                val plan = ClearPlanner(PlannerInput(dungeon, real, humanParty, Random(i.toLong()), assignment = BloodAssignment(4))).build()
                runs++
                val bloodDoor = dungeon.doors.firstOrNull { it.kind == DoorKind.BLOOD }?.index
                doors += plan.doors.values.count { it.door != bloodDoor }
                val doorLeaps = plan.leaps.filter { it.door != CampLeaps.NO_DOOR }
                leaps += doorLeaps.size; camp += plan.leaps.size - doorLeaps.size
                for (lp in doorLeaps) gaps += lp.t - plan.doors.getValue(lp.door).openT
            }
            val perDoor = leaps.toDouble() / doors
            println("leaps: $runs plans, $doors wither doors, $leaps leaps, ${"%.2f".format(perDoor)} a door, ${"%.2f".format(leaps.toDouble() / runs)} a run, gap median ${gaps.sorted().getOrNull(gaps.size / 2)}")
            println("camp leaps: ${"%.2f".format(camp.toDouble() / runs)} a run")
            assertEquals(0, camp, "camp leaps are off")
            assertTrue(perDoor in 0.1..0.65, "leapers per door ${"%.2f".format(perDoor)} (recorded 0.5; the bots crowd the door more than people, so fewer can leap)")
        } finally { Packs.unload() }
    }
}
