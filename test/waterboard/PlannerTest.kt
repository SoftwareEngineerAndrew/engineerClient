package com.engineerclient.waterboard

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the planner's plans do on the board, played back on the simulator. */
class PlannerTest {
    private fun json(name: String): JsonObject =
        JsonParser.parseString(javaClass.getResourceAsStream("/waterboard/$name")!!.readAllBytes().toString(Charsets.UTF_8)).asJsonObject

    private fun closedOf(key: String): Int { var c = 0; for (ch in key) c = c or (1 shl (ch - '0')); return c }

    /** From the solve on, every gate stays open [Planner.HOLD] ticks: the chest needs them open, not open for an instant. */
    @Test fun plansKeepEveryGateOpenForTheChest() {
        val layouts = json("layouts.json")
        for ((v, key) in listOf(0 to "012", 1 to "134", 2 to "024", 3 to "234")) {
            val (geo, inn) = Geometry.parse(layouts.getAsJsonArray(v.toString()).map { it.asString }, "NORTH")
            val board = WaterSim.fresh(geo, inn, closedOf(key))
            val p = Planner().plan(board, emptyList(), 2000, Random(v * 31 + 7))
            assertTrue(p.solved, "v$v $key: $p")
            // played back on a plain board (gates as the outlets set them, no hold): open from the solve, all the hold through
            val s = board.copy()
            val at = HashMap<Int, Int>()
            for (f in p.flips) at.merge(f.tick, 1 shl f.lever) { a, b -> a xor b }
            while (s.time < p.solvedAt + Planner.HOLD - 1) {
                s.step(at[s.time + 1] ?: 0)
                if (s.time >= p.solvedAt) assertEquals(0, s.closed, "v$v $key: a gate closed ${s.time - p.solvedAt} ticks after the solve, $p")
            }
        }
    }

    /** Every gate open now and water still moving: a plan keeps them open (none needed when they stay, as here at rest). */
    @Test fun openGatesThatStayOpenNeedNoFlips() {
        val layouts = json("layouts.json")
        val (geo, inn) = Geometry.parse(layouts.getAsJsonArray("0").map { it.asString }, "NORTH")
        val board = WaterSim.fresh(geo, inn, 0)
        val p = Planner().plan(board, emptyList(), 200, Random(1))
        assertTrue(p.solved && p.flips.isEmpty(), "$p")
    }
}
