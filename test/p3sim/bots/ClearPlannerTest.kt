package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.*
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.plugin.BloodAssignment
import com.engineerclient.p3sim.run.plugin.Seat
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ClearPlan on an invented 14-room dungeon: the door schedule, reachability, continuity of the tracks, re-planning and the strategy hook. */
class ClearPlannerTest {
    private val dungeon = BotFixtures.dungeon()
    private val tables = BotFixtures.tables()
    private val five = ClearSim.seatsFor(ClearSim.FIVE)
    private val humanParty = listOf(Seat(0, "HEALER", true), Seat(1, "MAGE", false), Seat(2, "BERSERK", false), Seat(3, "ARCHER", false), Seat(4, "TANK", false))

    private fun plan(seed: Long, seats: List<Seat> = five, assignment: BloodAssignment = BloodAssignment.NOBODY, state: PlanState = PlanState.initial(), t: BotTables = tables): ClearPlan =
        ClearPlanner(PlannerInput(dungeon, t, seats, Random(seed), assignment, state)).build()

    @Test fun theSameSeedGivesTheSamePlan() {
        val a = plan(5); val b = plan(5)
        assertEquals(a.milestones.bloodOpenT, b.milestones.bloodOpenT)
        assertEquals(a.rooms.mapValues { it.value.greenT }, b.rooms.mapValues { it.value.greenT })
        assertEquals(a.tracks.map { it.legs.size }, b.tracks.map { it.legs.size })
        assertTrue((1L..6L).map { plan(it).milestones.bloodOpenT }.toSet().size > 1, "different seeds differ")
    }

    @Test fun paidDoorsFallInOrderWithTheirKeyBefore() {
        for (seed in 1L..40L) {
            val p = plan(seed)
            val paid = dungeon.pathDoors.filter { it.kind == DoorKind.WITHER }
            assertEquals(3, paid.size)
            val plans = paid.map { assertNotNull(p.doors[it.index], "seed $seed door ${it.index} has no plan") }
            for ((i, d) in plans.withIndex()) {
                assertTrue(d.keyT < d.openT, "the key drops before the door falls")
                if (i > 0) assertTrue(d.openT >= plans[i - 1].openT + BotTuning.MIN_DOOR_GAP, "doors fall in order, a gap apart (seed $seed)")
            }
            assertTrue(p.milestones.bloodKeyT > plans.last().openT, "the blood key comes after the last paid door (seed $seed)")
            assertTrue(p.milestones.bloodOpenT > p.milestones.bloodKeyT)
            assertTrue(p.milestones.watcherDoneT > p.milestones.bloodOpenT && p.milestones.maxorT > p.milestones.watcherDoneT)
        }
    }

    @Test fun everyPaidDoorHasAnOpenActAtItsTick() {
        val p = plan(3)
        val acts = p.tracks.flatMap { it.acts }.filterIsInstance<OpenDoor>()
        for (d in p.doors.values) assertTrue(acts.any { it.door == d.door && it.t == d.openT && it.seat == d.seat })
        val blood = dungeon.doors.first { it.kind == DoorKind.BLOOD }
        assertTrue(acts.any { it.door == blood.index && it.t == p.milestones.bloodOpenT }, "the blood door has its act")
        assertEquals(acts.size, acts.map { it.door }.toSet().size, "each door is opened once")
    }

    @Test fun theOpenerStandsBesideTheDoorWhenItFalls() {
        val geo = PlanGeometry(dungeon)
        for (seed in 1L..80L) {
            val p = plan(seed)
            for (d in p.doors.values) {
                val tr = p.track(d.seat)!!
                val act = tr.acts.filterIsInstance<OpenDoor>().single { it.door == d.door }
                assertEquals(d.openT, act.t, "the door plan and its act agree")
                val away = tr.positionAt(act.t).dist(geo.doorPt(d.door))
                assertTrue(away <= BotTuning.OPEN_REACH, "seed $seed door ${d.door}: the opener is $away blocks from it when it falls")
                assertTrue(away >= 0.5, "it stands beside the door, not in the gap")
            }
        }
    }

    @Test fun mostKeysAreTakenByAnotherBotThanTheOpener() {
        var split = 0; var total = 0
        for (seed in 1L..120L) {
            val p = plan(seed)
            for (d in p.doors.values) {
                total++; if (d.keySeat != d.seat) split++
                // The seat the plan names as the key's really is in a room kill act whose last mob dies when the key is due.
                val kill = p.track(d.keySeat)!!.acts.filterIsInstance<ClearMobs>().filter { it.until in d.keyT..d.keyT + 20 }
                assertTrue(kill.isNotEmpty(), "seed $seed door ${d.door}: seat ${d.keySeat} kills at the key tick ${d.keyT}")
            }
        }
        val share = split.toDouble() / total
        assertTrue(share in 0.5..0.95, "the key is taken by another bot than the opener in $share of doors (recorded 0.84)")
    }

    @Test fun aLoneBotDoesBothAndStillGetsThereBeforeTheDoorFalls() {
        val two = listOf(Seat(0, "HEALER", true), Seat(1, "MAGE", false))
        val geo = PlanGeometry(dungeon)
        for (seed in 1L..40L) {
            val p = plan(seed, two)
            for (d in p.doors.values) {
                assertEquals(1, d.seat, "the only bot opens every door")
                val tr = p.track(1)!!
                assertTrue(tr.positionAt(d.openT).dist(geo.doorPt(d.door)) <= BotTuning.OPEN_REACH, "seed $seed door ${d.door}")
                assertTrue(d.openT >= d.keyT + BotTuning.OPEN_SETTLE, "it kills in the key room, then walks to the door")
            }
        }
    }

    @Test fun aKeyRoomAnotherBotKillsIsAnOrdinaryVisitWhoseMobsDieWhenTheKeyIsDue() {
        var seen = 0
        for (seed in 1L..60L) {
            val p = plan(seed)
            val paid = dungeon.pathDoors.filter { it.kind == DoorKind.WITHER }
            val d = p.doors.getValue(paid[0].index)
            val kill = p.tracks.flatMap { tr -> tr.acts.filterIsInstance<ClearMobs>().filter { it.room == 1 }.map { tr.seat to it } }.single()
            if (kill.first == d.seat) continue
            seen++
            assertTrue(kill.second.until in d.keyT..d.keyT + 20, "seed $seed: the last mob dies at the key tick ${d.keyT}, not ${kill.second.until}")
            assertTrue(p.rooms.getValue(1).greenT != null || dungeon.rooms[1].secretsMax == 0 || p.rooms.getValue(1).work == RoomWork.WHITE_ONLY)
        }
        assertTrue(seen > 10)
    }

    @Test fun noRoomIsStartedBeforeItsDoorsCanBePassed() {
        for (seed in 1L..40L) {
            val p = plan(seed)
            val passAt = HashMap<Int, Int>()
            for (d in dungeon.doors) when (d.kind) {
                DoorKind.NORMAL -> passAt[d.index] = 0
                DoorKind.ENTRANCE, DoorKind.FAIRY -> passAt[d.index] = 0
                DoorKind.WITHER -> passAt[d.index] = p.doors.getValue(d.index).openT + BotTuning.DOOR_PASSABLE_AFTER
                DoorKind.BLOOD -> passAt[d.index] = p.milestones.bloodOpenT + BotTuning.DOOR_PASSABLE_AFTER
                else -> {}
            }
            val reach = PlanGeometry(dungeon).reachTimes(0) { passAt[it] ?: 0 }
            for (rp in p.rooms.values) assertTrue(rp.arriveT >= reach[rp.room] - 1, "seed $seed room ${rp.room} arrives ${rp.arriveT} before it is reachable ${reach[rp.room]}")
        }
    }

    @Test fun everyClearRoomIsPlannedOnce() {
        val p = plan(9)
        val expected = dungeon.rooms.filter { it.type != RoomType.ENTRANCE && it.type != RoomType.FAIRY && it.type != RoomType.BLOOD }.map { it.index }.toSet()
        assertEquals(expected, p.rooms.keys)
        for (rp in p.rooms.values) {
            assertTrue(rp.seat in 0..4)
            val g = rp.greenT; val w = rp.whiteT
            if (g != null && w != null) assertTrue(w <= g, "white comes no later than green")
        }
        // A room with secrets ends green after it ends white; a puzzle goes straight to its solve.
        assertEquals(RoomWork.PUZZLE, p.rooms.getValue(9).work)
    }

    @Test fun tracksAreContinuousInTimeAndSpace() {
        for (seed in 1L..30L) {
            val p = plan(seed)
            assertEquals(5, p.tracks.size)
            for (tr in p.tracks) {
                for ((a, b) in tr.legs.zipWithNext()) {
                    assertEquals(a.t1, b.t0, "seed $seed seat ${tr.seat}: a leg starts where the last ended")
                    // A leap to the door opener is the one jump.
                    val leap = p.leaps.any { it.seat == tr.seat && it.t == b.t0 }
                    assertTrue(leap || a.b.dist(b.a) < 1e-6, "seed $seed seat ${tr.seat}: a leg starts where the last ended in space (${a.b} vs ${b.a})")
                }
                assertTrue(tr.legs.first().t0 <= 1)
            }
        }
    }

    @Test fun nobodyCrossesAClosedDoorway() {
        // Between two ticks, a seat that moves across a door's gap line near its centre has gone through the doorway; that must not happen before the door can be passed.
        val geo = PlanGeometry(dungeon)
        for (seed in 1L..30L) {
            val p = plan(seed)
            val limits = HashMap<Int, Int>()
            for (d in dungeon.doors) when (d.kind) {
                DoorKind.WITHER -> limits[d.index] = p.doors.getValue(d.index).openT + BotTuning.DOOR_PASSABLE_AFTER
                DoorKind.BLOOD -> limits[d.index] = p.milestones.bloodOpenT + BotTuning.DOOR_PASSABLE_AFTER
                DoorKind.ENTRANCE -> limits[d.index] = BotTuning.DOOR_PASSABLE_AFTER + 2
                DoorKind.FAIRY -> limits[d.index] = BotTuning.DOOR_PASSABLE_AFTER + 1
                else -> {}
            }
            for (tr in p.tracks) for (t in 1..p.milestones.maxorT) {
                if (p.leaps.any { it.seat == tr.seat && it.t == t }) continue   // a leap goes to the opener, wherever that is
                val a = tr.positionAt(t - 1); val b = tr.positionAt(t)
                for ((door, passable) in limits) {
                    if (t >= passable) continue
                    val spot = geo.doorPt(door)
                    val e = dungeon.doors[door].axis == 'E'
                    val (a1, b1, s1, a2, s2) = if (e) listOf(a.x, b.x, spot.x, a.z, spot.z) else listOf(a.z, b.z, spot.z, a.x, spot.x)
                    val crossed = (a1 < s1 - 0.05 && b1 > s1 + 0.05) || (a1 > s1 + 0.05 && b1 < s1 - 0.05)
                    assertTrue(!(crossed && kotlin.math.abs(a2 - s2) < 2.0), "seed $seed seat ${tr.seat} went through door $door at t=$t before it could be passed ($passable)")
                }
            }
        }
    }

    @Test fun theHumanSeatIsSoftAndHasNoTrack() {
        val p = plan(2, humanParty)
        assertNull(p.track(0))
        assertEquals(4, p.tracks.size)
        assertTrue(p.rooms.values.any { it.soft } || p.rooms.values.none { it.seat == 0 })
        assertTrue(p.rooms.values.filter { it.soft }.all { it.seat == 0 })
        // The opener is never the human.
        assertTrue(p.doors.values.all { it.seat != 0 })
    }

    @Test fun withoutTheHumanSeatBotsTakeEverything() {
        val p = ClearPlanner(PlannerInput(dungeon, tables, humanParty, Random(2), state = PlanState(0, humanSeatActive = false))).build()
        assertTrue(p.rooms.values.none { it.soft || it.seat == 0 })
        val expected = dungeon.rooms.filter { it.type != RoomType.ENTRANCE && it.type != RoomType.FAIRY && it.type != RoomType.BLOOD }.size
        assertEquals(expected, p.rooms.size)
    }

    @Test fun aBloodAssignmentSendsThatSeatToTheBloodRoomUntilTheCampEnds() {
        val p = plan(4, assignment = BloodAssignment(1))
        assertEquals(1, p.milestones.bloodSeat)
        val tr = p.track(1)!!
        val geo = PlanGeometry(dungeon)
        val blood = dungeon.blood!!.index
        val c = geo.centre(blood)
        val mid = (p.milestones.bloodOpenT + p.milestones.watcherDoneT) / 2
        assertTrue(tr.positionAt(mid).dist(c) < 4.0, "the assigned seat stands in the blood room mid-camp")
        assertTrue(tr.positionAt(p.milestones.watcherDoneT).dist(c) < 4.0)
        // Nobody else goes there.
        for (o in p.tracks.filter { it.seat != 1 }) assertTrue(o.positionAt(mid).dist(c) > 4.0, "seat ${o.seat} stays out")
    }

    @Test fun theNeutralStrategySendsNobodyAndAPluginCannotPickTheHuman() {
        val ctx = BotStrategyContext(dungeon, humanParty, Random(1), null, 0)
        assertEquals(BloodAssignment.NOBODY, BotStrategies.assignment(NeutralStrategy, ctx))
        val bad = object : com.engineerclient.p3sim.run.plugin.PartyStrategy {
            override val id = "bad"; override val label = "bad"
            override fun bloodAssignment(ctx: com.engineerclient.p3sim.run.plugin.StrategyContext) = BloodAssignment(0)
        }
        assertEquals(BloodAssignment.NOBODY, BotStrategies.assignment(bad, ctx))
        val boom = object : com.engineerclient.p3sim.run.plugin.PartyStrategy {
            override val id = "boom"; override val label = "boom"
            override fun bloodAssignment(ctx: com.engineerclient.p3sim.run.plugin.StrategyContext): BloodAssignment = error("no")
        }
        assertEquals(BloodAssignment.NOBODY, BotStrategies.assignment(boom, ctx))
        assertEquals(NeutralStrategy, BotStrategies.resolve("no-such-preset"))
        assertEquals(NeutralStrategy, BotStrategies.resolve(null))
    }

    @Test fun aReplanKeepsDoneRoomsAndOpenDoors() {
        val first = plan(11)
        val now = 400
        val whiteRoom = 8      // "Side 2", 5 secrets: its mobs are dead, its secrets are not found
        val doneRoom = 10      // "Side 3": finished
        val paid = dungeon.pathDoors.filter { it.kind == DoorKind.WITHER }
        val open = paid.take(2).associate { it.index to first.doors.getValue(it.index).openT + BotTuning.DOOR_PASSABLE_AFTER }
        val seatPos = five.associate { it.index to PlanGeometry(dungeon).centre(0) }
        val st = PlanState(now, white = setOf(whiteRoom, doneRoom), green = setOf(doneRoom), passableAt = open, seatPos = seatPos, seatFreeAt = five.associate { it.index to now })
        val again = plan(12, state = st)
        assertTrue(doneRoom !in again.rooms.keys, "a green room is not planned again")
        assertEquals(RoomWork.GREEN_ONLY, again.rooms.getValue(whiteRoom).work)
        assertNull(again.doors[paid[0].index], "an open door has no new plan"); assertNull(again.doors[paid[1].index])
        assertNotNull(again.doors[paid[2].index]); assertTrue(again.doors.getValue(paid[2].index).openT > now)
        assertTrue(again.tracks.all { it.legs.first().t0 >= now - 1 }, "tracks start now")
        assertEquals(now, again.builtAt)
    }

    @Test fun roomsTheHumanTookAreLeftAlone() {
        val st = PlanState(0, humanRooms = setOf(8, 9), humanSeatActive = false)
        val p = plan(6, humanParty, state = st)
        assertTrue(8 !in p.rooms.keys && 9 !in p.rooms.keys)
    }

    @Test fun aCarriedCommitmentIsKept() {
        val seats = five
        val commit = Commit(2, 8, RoomWork.FULL, 100, 150, 500)
        val st = PlanState(300, commits = listOf(commit), seatPos = mapOf(2 to PlanGeometry(dungeon).centre(8)), seatFreeAt = seats.associate { it.index to 300 })
        val p = plan(1, state = st)
        assertEquals(2, p.rooms.getValue(8).seat)
        val acts = p.track(2)!!.acts
        assertTrue(acts.any { it is FindSecrets && it.room == 8 && it.until == 500 })
        assertTrue(acts.none { it is ClearMobs && it.room == 8 }, "the mobs are already dead by its white tick 150")
        assertTrue(p.track(2)!!.positionAt(499).dist(PlanGeometry(dungeon).centre(8)) < 1.0, "the seat stays in the room until it is done")
    }

    @Test fun defaultsWorkWithoutAnyPack() {
        val p = plan(8, t = BotTables(null))
        assertTrue(p.milestones.bloodOpenT > 0)
        assertEquals(4, p.doors.size, "three paid doors and the blood door")
    }

    @Test fun theFirstPlanStartsEachBotWhereTheCountdownLeftIt() {
        val geo = PlanGeometry(dungeon)
        val door = geo.doorPt(dungeon.doors.first { it.kind == DoorKind.ENTRANCE }.index)
        val at = five.filter { !it.isHuman }.associate { it.index to Pt(door.x - 2.0, door.z + 0.3 * it.index) }
        val p = ClearPlanner(PlannerInput(dungeon, tables, five, Random(8), startAt = at)).build()
        for ((seat, pt) in at) assertEquals(pt, p.track(seat)!!.positionAt(0), "seat $seat starts at its gate")
        // Without the gates the bots start on the ring round the entrance room's middle, as before.
        val ring = ClearPlanner(PlannerInput(dungeon, tables, five, Random(8))).build()
        assertTrue(at.keys.any { ring.track(it)!!.positionAt(0) != at.getValue(it) })
    }
}
