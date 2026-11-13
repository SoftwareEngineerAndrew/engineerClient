package com.engineerclient.waterboard

import com.google.gson.JsonParser
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The shipped plan table ([PlanTable], made by [PlanTableGen]) and recovering from a plan gone wrong. */
class PlanTableTest {
    private val recorded = JsonParser.parseString(javaClass.getResource("/waterboard-recorded.json")!!.readText()).asJsonObject
    private val rotations = listOf("NORTH", "EAST", "SOUTH", "WEST")
    private val combos = (0 until 32).filter { Integer.bitCount(it) == 3 }

    private fun layouts() = JsonParser.parseString(javaClass.getResource("/waterboard-layouts.json")!!.readText())
        .asJsonObject["layouts"].asJsonArray.map { l -> l.asJsonObject["rows"].asJsonArray.map { it.asString } }

    @Test fun everyBoardTurnAndGateSetHasEveryObjective() {
        for (rows in layouts()) for (rot in rotations) for (closed in combos) for (obj in Objective.entries)
            assertNotNull(PlanTable.lookup(rows, rot, closed, obj), "${PlanTable.layoutOf(rows)} $rot $closed $obj")
    }

    @Test fun everyPlanSolvesWhenItSays() {
        // the table's ticks played on the simulation solve on the tick it says (it is the same simulation)
        for (rows in layouts()) for (rot in rotations) for (closed in combos) for (obj in Objective.entries) {
            val e = PlanTable.lookup(rows, rot, closed, obj)!!
            if (e.solvedAt == WaterSim.NONE) continue
            val (geo, inn) = Geometry.parse(rows, rot)
            val p = Planner().score(WaterSim.fresh(geo, inn, closed), e.flips)
            assertEquals(e.solvedAt, p.solvedAt, "${e.layout} $rot $closed $obj")
        }
    }

    @Test fun safestIsNeverLessReliableThanFastest() {
        for (rows in layouts()) for (rot in rotations) for (closed in combos) {
            val safe = PlanTable.lookup(rows, rot, closed, Objective.SAFEST)!!
            val fast = PlanTable.lookup(rows, rot, closed, Objective.FASTEST)!!
            assertTrue(safe.solveRate >= fast.solveRate - 0.02, "${safe.layout} $rot $closed: safest ${safe.solveRate} fastest ${fast.solveRate}")
        }
    }

    @Test fun recordedBoardsAreInTheTable() {
        // the boards the recorder saw built (before any lever): found by their rows, whichever way the room was turned
        for ((name, run) in listOf("a18b44" to recorded["a18b44"].asJsonObject, "a88e9b" to recorded["a88e9b"].asJsonObject) +
                recorded["blind"].asJsonObject.entrySet().map { it.key to it.value.asJsonObject }) {
            val rows = run["rows"].asJsonArray.map { it.asString }
            assertTrue(PlanTable.layoutOf(rows) >= 0, "$name: board not in the table")
            assertNotNull(PlanTable.lookup(rows, run["rotation"].asString, run["closed"].asInt, Objective.SAFEST), name)
        }
    }

    @Test fun aPlanStartsWhenThePlayerCan() {
        val rows = layouts()[2]
        val e = PlanTable.lookup(rows, "SOUTH", 0b11100, Objective.SAFEST)!!
        val moved = e.at(40)
        assertEquals(40, moved[0].tick)
        assertEquals(e.flips.map { it.tick - e.flips[0].tick }, moved.map { it.tick - 40 })
        // on a fresh board a plan shifted in time solves as many ticks later
        val (geo, inn) = Geometry.parse(rows, "SOUTH")
        val p = Planner().score(WaterSim.fresh(geo, inn, 0b11100), moved)
        assertEquals(e.solvedAt + 40 - e.flips[0].tick, p.solvedAt)
    }

    @Test fun everyPlanSolvesAsLongAfterItsFirstFlipWheneverItStarts() {
        // the solver holds a plan until the first lever press and starts it there: exact only if a fresh board, whatever its
        // clock reads (the tracker's tick when you walk in), solves the same ticks after the first flip however long it waited
        fun solvedAfterFirst(board: WaterSim, flips: List<Flip>): Int {
            val s = board.copy(); s.hold = Planner.HOLD; s.solvedAt = WaterSim.NONE
            val bits = HashMap<Int, Int>(); for (f in flips) bits.merge(f.tick, 1 shl f.lever) { a, b -> a xor b }
            val end = flips[0].tick + 700
            while (s.time < end && s.solvedAt == WaterSim.NONE) s.step(bits[s.time + 1] ?: 0)
            return if (s.solvedAt == WaterSim.NONE) WaterSim.NONE else s.solvedAt - flips[0].tick
        }
        var n = 0
        for (rows in layouts()) for (rot in rotations) for (closed in combos) for (obj in Objective.entries) {
            val e = PlanTable.lookup(rows, rot, closed, obj)!!
            if (e.flips.isEmpty()) continue
            val (geo, inn) = Geometry.parse(rows, rot)
            val want = solvedAfterFirst(WaterSim.fresh(geo, inn, closed), e.flips)
            assertEquals(if (e.solvedAt == WaterSim.NONE) WaterSim.NONE else e.solvedAt - e.flips[0].tick, want, "${e.layout} $rot $closed $obj: as the table says")
            for (clock in intArrayOf(0, 1237)) for (wait in intArrayOf(1, 6, 163)) {
                val got = solvedAfterFirst(WaterSim.fresh(geo, inn, closed, clock), e.at(clock + wait))
                assertEquals(want, got, "${e.layout} $rot $closed $obj: board clock $clock, first flip $wait ticks in")
            }
            n++
        }
        assertEquals(640, n)
    }

    @Test fun aLateFlipIsRecoveredFrom() {
        // follow the table plan, but one flip comes 12 ticks late: from the board as it then is, the live planner still solves
        val rows = layouts()[2]
        for (obj in listOf(Objective.FASTEST, Objective.SAFEST)) {
            val e = PlanTable.lookup(rows, "SOUTH", 0b11100, obj)!!
            val (geo, inn) = Geometry.parse(rows, "SOUTH")
            val s = WaterSim.fresh(geo, inn, 0b11100)
            val late = e.flips[1].let { Flip(it.tick + 12, it.lever) }
            val done = listOf(e.flips[0], late)
            val bits = HashMap<Int, Int>(); for (f in done) bits.merge(f.tick, 1 shl f.lever) { a, b -> a xor b }
            while (s.time < late.tick + 2) s.step(bits[s.time + 1] ?: 0)
            val plan = Planner().plan(s, listOf(e.flips.drop(2)), 1500, Random(3), objective = obj)
            assertTrue(plan.solved, "$obj: no plan after a late flip")
        }
    }
}
