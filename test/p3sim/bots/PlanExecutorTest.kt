package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.*
import com.engineerclient.p3sim.run.plugin.Seat
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The executor against a recording fake world: acts happen on schedule, retry when they must, defer to the human, and are never done twice. */
class PlanExecutorTest {
    private val dungeon = BotFixtures.dungeon()
    private val tables = BotFixtures.tables()
    private val seats = ClearSim.seatsFor(ClearSim.FIVE)

    private class FakeWorld : BotWorld {
        val log = ArrayList<String>()
        var keys = true
        var human: Set<Int> = emptySet()
        val whiteRooms = HashSet<Int>(); val greenRooms = HashSet<Int>()
        val killFraction = HashMap<Int, Double>()
        var now = 0
        override fun openDoor(seat: Int, door: Int): Boolean { if (!keys) return false; log += "open $door by $seat at $now"; return true }
        override fun killRequired(seat: Int, room: Int, fraction: Double): Boolean {
            killFraction[room] = maxOf(killFraction[room] ?: 0.0, fraction)
            if (fraction >= 1.0) { whiteRooms += room; log += "white $room at $now"; return true }
            return false
        }
        override fun findSecrets(seat: Int, room: Int, fraction: Double): Boolean {
            if (fraction >= 1.0) { greenRooms += room; log += "green $room at $now"; return true }
            return false
        }
        override fun solvePuzzle(seat: Int, room: Int): Boolean { greenRooms += room; log += "puzzle $room at $now"; return true }
        override fun roomDone(room: Int, green: Boolean) = if (green) room in greenRooms else room in whiteRooms || room in greenRooms
        override fun humanInside(room: Int) = room in human
    }

    private fun plan(seed: Long = 5) = ClearPlanner(PlannerInput(dungeon, tables, seats, Random(seed))).build()

    @Test fun aSeatIsWorkingOnlyInsideTheWindowOfItsRoomWork() {
        val p = plan()
        val ex = PlanExecutor(p, FakeWorld())
        val act = p.tracks.flatMap { it.acts }.filter { it !is OpenDoor && it.until > it.t + 4 }.first()
        assertEquals(act, ex.workOf(act.seat, act.t + 2))
        assertEquals(act, ex.workOf(act.seat, act.until))
        assertEquals(null, ex.workOf(act.seat, act.t - 1).takeIf { it === act })
        val door = p.tracks.flatMap { it.acts }.filterIsInstance<OpenDoor>().first()
        assertTrue(ex.workOf(door.seat, door.t).let { it == null || it !is OpenDoor }, "a door is not room work")
    }

    @Test fun doorsOpenOnThePlannedTickAndRoomsFinishOnTheirs() {
        val p = plan()
        val w = FakeWorld(); val ex = PlanExecutor(p, w)
        for (t in 0..p.milestones.maxorT + 400) { w.now = t; ex.tick(t) }
        for (d in p.doors.values) assertTrue("open ${d.door} by ${d.seat} at ${d.openT}" in w.log, "door ${d.door} should open at ${d.openT}: ${w.log.filter { it.startsWith("open") }}")
        for (r in p.rooms.values) {
            val g = r.greenT
            if (g != null && r.work != RoomWork.PUZZLE && r.work != RoomWork.WHITE_ONLY && dungeon.rooms[r.room].secretsMax > 0) assertTrue("green ${r.room} at $g" in w.log, "room ${r.room} green at $g: ${w.log.filter { it.contains(" ${r.room} ") }}")
        }
        assertTrue(ex.pending().isEmpty(), "everything is done: ${ex.pending()}")
    }

    @Test fun aDoorWithoutAKeyIsRetriedNotSpammed() {
        val p = plan()
        val w = FakeWorld(); w.keys = false
        val ex = PlanExecutor(p, w)
        val door = p.doors.values.minBy { it.openT }
        val theDoor = door.door
        var calls = 0
        val counting = object : BotWorld by w {
            override fun openDoor(seat: Int, door: Int): Boolean { if (door == theDoor) calls++; return w.openDoor(seat, door) }
        }
        val ex2 = PlanExecutor(p, counting)
        for (t in 0..door.openT + 100) ex2.tick(t)
        assertTrue(calls in 1..30, "asked $calls times in 100 ticks, not every tick")
        w.keys = true
        ex2.tick(door.openT + 105)
        assertTrue(w.log.any { it.startsWith("open ${door.door}") })
        assertTrue(ex.pending().isNotEmpty())
    }

    /** One bot walking from (0,0) to (60,0) over 100 ticks, with a door at the far end that it is to open at tick 30. */
    private fun walkerPlan(openAt: Int = 30): ClearPlan {
        val seat = com.engineerclient.p3sim.run.plugin.Seat(1, "MAGE", false)
        val door = Pt(60.0, 0.0)
        val track = SeatTrack(1, listOf(Leg(0, 100, Pt(0.0, 0.0), door, hop = false), Leg(100, 200, door, Pt(60.0, 60.0))), listOf(OpenDoor(1, openAt, 7, door)))
        val ms = PlanMilestones(0, 0, 0, 0, null)
        return ClearPlan(listOf(seat), listOf(track), emptyMap(), emptyMap(), ms, 0, com.engineerclient.p3sim.run.plugin.BloodAssignment.NOBODY)
    }

    @Test fun aDoorWaitsForItsOpenerToWalkUpToIt() {
        val w = FakeWorld()
        val ex = PlanExecutor(walkerPlan(), w)
        for (t in 0..120) { w.now = t; ex.tick(t) }
        val at = w.log.single { it.startsWith("open 7") }.substringAfterLast(" ").toInt()
        assertTrue(at in 90..100, "the door fell when the walker was within ${BotTuning.OPEN_REACH} blocks of it (tick $at)")
    }

    @Test fun anOpenerThatWaitedForTheKeyOpensWhenItComesEvenFromAfar() {
        val w = FakeWorld(); w.keys = false
        val ex = PlanExecutor(walkerPlan(), w)
        for (t in 0..150) { w.now = t; ex.tick(t) }
        assertTrue(w.log.isEmpty(), "no key yet")
        w.keys = true
        for (t in 151..160) { w.now = t; ex.tick(t) }
        assertTrue(w.log.any { it.startsWith("open 7") }, "the key came while the opener was already beside the door earlier and has moved on: it opens")
    }

    @Test fun aPlanThatNeverBringsTheOpenerDoesNotLockTheDoorForever() {
        val seat = com.engineerclient.p3sim.run.plugin.Seat(1, "MAGE", false)
        val track = SeatTrack(1, listOf(Leg(0, 10, Pt(0.0, 0.0), Pt(1.0, 0.0))), listOf(OpenDoor(1, 5, 7, Pt(80.0, 0.0))))
        val plan = ClearPlan(listOf(seat), listOf(track), emptyMap(), emptyMap(), PlanMilestones(0, 0, 0, 0, null), 0, com.engineerclient.p3sim.run.plugin.BloodAssignment.NOBODY)
        val w = FakeWorld(); val ex = PlanExecutor(plan, w)
        for (t in 0..5 + PlanExecutor.OPEN_GATE_MAX + 10) { w.now = t; ex.tick(t) }
        assertTrue(w.log.any { it.startsWith("open 7") })
    }

    @Test fun theHumanInARoomHoldsBackTheLastKillButOnlyForTheGrace() {
        val p = plan()
        val room = p.tracks.flatMap { it.acts }.filterIsInstance<ClearMobs>().first { it.room !in setOf(1, 2, 5) }
        val w = FakeWorld(); w.human = setOf(room.room)
        val ex = PlanExecutor(p, w, graceTicks = 100)
        for (t in 0..room.until + 99) { w.now = t; ex.tick(t) }
        assertTrue(room.room !in w.whiteRooms, "held back while the human stands in the room")
        assertTrue((w.killFraction[room.room] ?: 0.0) < 1.0)
        for (t in room.until + 100..room.until + 120) { w.now = t; ex.tick(t) }
        assertTrue(room.room in w.whiteRooms, "the grace ran out")
    }

    @Test fun aRoomSomeoneElseFinishedNeedsNoWork() {
        val p = plan()
        val room = p.tracks.flatMap { it.acts }.filterIsInstance<ClearMobs>().first { it.room !in setOf(1, 2, 5) }
        val w = FakeWorld(); w.whiteRooms += room.room
        val ex = PlanExecutor(p, w)
        for (t in 0..room.until + 5) { w.now = t; ex.tick(t) }
        assertTrue(w.log.none { it.startsWith("white ${room.room} ") }, "the bot did not kill what was already dead")
    }

    @Test fun aReplanDoesNotRepeatFinishedActs() {
        val p = plan()
        val w = FakeWorld(); val ex = PlanExecutor(p, w)
        val firstDoor = p.doors.values.minBy { it.openT }
        for (t in 0..firstDoor.openT + 2) { w.now = t; ex.tick(t) }
        assertEquals(1, w.log.count { it.startsWith("open ${firstDoor.door} ") })
        ex.replace(plan(6))
        for (t in firstDoor.openT + 3..firstDoor.openT + 400) { w.now = t; ex.tick(t) }
        assertEquals(1, w.log.count { it.startsWith("open ${firstDoor.door} ") }, "that door is not opened a second time")
    }

    @Test fun positionsFollowTheTracks() {
        val p = plan()
        val ex = PlanExecutor(p, FakeWorld())
        for (s in p.tracks) assertEquals(s.positionAt(100), ex.positionOf(s.seat, 100))
        assertEquals(null, ex.positionOf(99, 0))
    }

    @Test fun killsSpreadOverTheWindow() {
        val p = plan()
        val act = p.tracks.flatMap { it.acts }.filterIsInstance<ClearMobs>().first { it.until - it.t >= 20 }
        val w = FakeWorld(); val ex = PlanExecutor(p, w)
        for (t in 0..act.t + (act.until - act.t) / 2) { w.now = t; ex.tick(t) }
        val f = w.killFraction[act.room] ?: 0.0
        assertTrue(f in 0.3..0.7, "half way through the window about half is killed ($f)")
    }
}
