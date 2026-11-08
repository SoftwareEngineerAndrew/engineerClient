package com.engineerclient.waterboard

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

/**
 * How good the planner is, measured: fresh boards planned from nothing, and play with sloppy flips re-planned after each one.
 * Slow (minutes): runs only with WB_EVAL set. Prints a table; asserts only that it solves.
 */
class PlannerEval {
    private fun json(name: String): JsonObject =
        JsonParser.parseString(javaClass.getResourceAsStream("/waterboard/$name")!!.readAllBytes().toString(Charsets.UTF_8)).asJsonObject

    private val combos = listOf("012", "013", "014", "023", "024", "034", "123", "124", "134", "234")

    private fun closedOf(key: String): Int { var c = 0; for (ch in key) c = c or (1 shl (ch - '0')); return c }

    @Test fun freshBoards() {
        assumeTrue(System.getenv("WB_EVAL") != null, "WB_EVAL not set")
        val budget = (System.getenv("WB_BUDGET") ?: "3000").toLong()
        val layouts = json("layouts.json")
        val table = File("libs/odin/assets/odin/puzzles/water-solutions.json").takeIf { it.exists() }?.let { JsonParser.parseString(it.readText()).asJsonObject }
        val expected = json("table-expected.json")
        var solved = 0; var sumPlan = 0L; var sumTable = 0L; var n = 0
        for (v in 0..3) for (key in combos) {
            val rows = layouts.getAsJsonArray(v.toString()).map { it.asString }
            val (geo, inn) = Geometry.parse(rows, "NORTH")
            val sim = WaterSim.fresh(geo, inn, closedOf(key))
            val p = Planner().plan(sim, emptyList(), budget, Random(v * 100 + key.toInt()))
            val tab = expected.getAsJsonArray("false/$v/$key/NORTH")[0].takeIf { !it.isJsonNull }?.asInt
            // the table's clock starts at its water flip, with its setup flips before; ours starts now: compare from the water flip
            val water = p.flips.firstOrNull { it.lever == WaterSim.WATER_LEVER }?.tick
            val fromWater = if (p.solved && water != null) p.solvedAt - water else null
            println("v$v $key plan ${if (p.solved) p.solvedAt else "-"} fromWater $fromWater table $tab flips ${p.flips.size} windows ${p.window.map { "${it.first}..${it.last}" }} $p")
            n++
            if (p.solved) { solved++; sumPlan += fromWater ?: 0; sumTable += tab ?: 0 }
        }
        println("solved $solved/$n  mean from water flip: plan ${sumPlan / maxOf(1, solved)} vs table ${sumTable / maxOf(1, solved)}")
    }

    /** A sloppy player's flip: late by a few ticks, sometimes much later, now and then the wrong lever or none at all. */
    private class Hand(seed: Long) {
        val r = Random(seed)
        fun lateness(): Int = if (r.nextDouble() < 0.08) 10 + r.nextInt(31) else Math.round(2 + 3 * gauss()).toInt().coerceAtLeast(-3)
        private fun gauss(): Double { var s = 0.0; repeat(6) { s += r.nextDouble() }; return (s - 3.0) * 1.41 }
        /** -1: skipped; else the lever actually flipped. */
        fun lever(want: Int, levers: IntArray): Int = when {
            r.nextDouble() < 0.03 -> -1
            r.nextDouble() < 0.04 -> levers.filter { it != want }.random(r)
            else -> want
        }
    }

    /** Plays a plan with a sloppy hand; [adapt]: re-plan after every flip (else keep following the first plan). */
    private fun play(start: WaterSim, first: Plan, adapt: Boolean, seed: Long, replanMs: Long, lead: Int = 8, limit: Int = 1400): Pair<Int, Int> {
        val hand = Hand(seed)
        val planner = Planner()
        val levers = start.geo.slotLever.distinct().sorted().toIntArray() + WaterSim.WATER_LEVER
        val s = start.copy()
        var todo = first.flips.toMutableList()
        var done = 0
        while (s.time < limit && s.solvedAt == WaterSim.NONE) {
            val next = todo.removeFirstOrNull()
            if (next == null) { s.runTo(limit, stopWhenSolved = true); break }
            val lever = hand.lever(next.lever, levers)
            if (lever < 0) continue
            val at = maxOf(s.time + 1, next.tick + hand.lateness())
            if (adapt) {
                // the rest of the old plan is late by as much, as the player sees it on screen
            }
            s.runTo(at - 1, stopWhenSolved = true)
            if (s.solvedAt != WaterSim.NONE) break
            s.step(1 shl lever); done++
            if (adapt && s.solvedAt == WaterSim.NONE) {
                // re-plan from what really happened; flips due before the player could react stay as they were
                val keep = todo.filter { it.tick <= s.time + lead }
                val base = s.copy()
                val at2 = HashMap<Int, Int>(); for (f in keep) at2.merge(f.tick, 1 shl f.lever) { a, b -> a xor b }
                while (base.time < s.time + lead) base.step(at2[base.time + 1] ?: 0)
                val p = planner.plan(base, listOf(todo.filter { it.tick > s.time + lead }), replanMs, Random(seed + done))
                todo = (keep + p.flips).sortedBy { it.tick }.toMutableList()
            }
        }
        return (if (s.solvedAt == WaterSim.NONE) -1 else s.solvedAt) to done
    }

    @Test fun sloppyPlay() {
        assumeTrue(System.getenv("WB_EVAL") != null, "WB_EVAL not set")
        val budget = (System.getenv("WB_BUDGET") ?: "3000").toLong()
        val replan = (System.getenv("WB_REPLAN") ?: "400").toLong()
        val trials = (System.getenv("WB_TRIALS") ?: "3").toInt()
        val layouts = json("layouts.json")
        var nA = 0; var okA = 0; var sumA = 0L; var okB = 0; var sumB = 0L; var both = 0; var sumBothA = 0L; var sumBothB = 0L; var exact = 0L; var nE = 0
        for (v in 0..3) for (key in combos) {
            val rows = layouts.getAsJsonArray(v.toString()).map { it.asString }
            val (geo, inn) = Geometry.parse(rows, "NORTH")
            val sim = WaterSim.fresh(geo, inn, closedOf(key))
            val p = Planner().plan(sim, emptyList(), budget, Random(v * 100 + key.toInt()))
            if (p.solved) { exact += p.solvedAt; nE++ }
            for (t in 0 until trials) {
                val seed = (v * 1000 + key.toInt()) * 10L + t
                val (a, fa) = play(sim, p, adapt = true, seed = seed, replanMs = replan)
                val (b, fb) = play(sim, p, adapt = false, seed = seed, replanMs = replan)
                nA++
                if (a >= 0) { okA++; sumA += a }
                if (b >= 0) { okB++; sumB += b }
                if (a >= 0 && b >= 0) { both++; sumBothA += a; sumBothB += b }
                println("v$v $key trial $t exact ${p.solvedAt} adaptive ${if (a >= 0) a else "FAIL"} ($fa flips) fixed ${if (b >= 0) b else "FAIL"} ($fb flips)")
            }
        }
        println("exact plans: mean ${exact / maxOf(1, nE)} ticks")
        println("adaptive: solved $okA/$nA mean ${sumA / maxOf(1, okA)}; fixed plan: solved $okB/$nA mean ${sumB / maxOf(1, okB)}; where both solved: adaptive ${sumBothA / maxOf(1, both)} fixed ${sumBothB / maxOf(1, both)}")
    }
}
