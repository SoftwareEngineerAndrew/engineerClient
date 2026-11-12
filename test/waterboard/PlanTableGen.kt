package com.engineerclient.waterboard

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * Builds the Water Board plan table (src/resources/assets/engineerclient/waterboard/plans.json): for each of the four boards
 * (waterboard-layouts.json, every one seen in the Dungeon Recorder's rooms), each way the room can be turned and each set of
 * three closed gates, the best plan for each [Objective] from a fresh board, with how it holds up over 1000 Monte Carlo trials.
 *
 * Slow (minutes per board): only when asked. `WB_GEN=1 gradle test --tests '*PlanTableGen*'` (WB_BUDGET: ms per plan,
 * WB_OUT: where to write, WB_ONLY: "layout" or "layout,rotation,closed" to do part of it).
 *
 * Plan ticks count from the fresh board's tick 0: the first flip is at tick 1 or later, and a plan is used by shifting all
 * its ticks so that its first flip is as soon as the player can do it.
 */
class PlanTableGen {
    /** Odin's own Water Board table (its jar: assets/odin/puzzles/water-solutions.json), as seeds and to compare with. */
    private val odinTable = System.getenv("WB_ODIN")?.let { JsonParser.parseString(File(it).readText()).asJsonObject }

    /** Our layout id to Odin's pattern (Odin tells them apart by a block behind the board: WaterSolver.scan). */
    private val odinPattern = mapOf(0 to "1", 1 to "3", 2 to "0", 3 to "2")
    private val odinLever = mapOf("quartz_block" to 0, "gold_block" to 1, "coal_block" to 2, "diamond_block" to 3,
        "emerald_block" to 4, "hardened_clay" to 5, "water" to 6)

    /** Odin's plans for a board (both its tables), its seconds from the water lever made ticks with the water lever at 1. */
    private fun odinPlans(layout: Int, closed: Int): Map<String, List<Flip>> {
        val t = odinTable ?: return emptyMap()
        val key = (0 until 5).filter { closed shr it and 1 != 0 }.joinToString("")
        return t.keySet().associateWith { variant ->
            val sol = t[variant].asJsonObject[odinPattern[layout]].asJsonObject[key]?.asJsonObject ?: return@associateWith emptyList<Flip>()
            sol.entrySet().flatMap { (block, times) -> times.asJsonArray.map { Flip(1 + Math.round(it.asDouble * 20).toInt(), odinLever[block]!!) } }
                .sortedWith(compareBy({ it.tick }, { if (it.lever == WaterSim.WATER_LEVER) 0 else 1 }))
        }.filterValues { it.isNotEmpty() }
    }

    @Test fun generate() {
        if (System.getenv("WB_GEN") == null) return
        val budget = (System.getenv("WB_BUDGET") ?: "6000").toLong()
        val out = File(System.getenv("WB_OUT") ?: "src/resources/assets/engineerclient/waterboard/plans.json")
        val only = System.getenv("WB_ONLY")?.takeIf { it.isNotBlank() }?.split(",")
        val layouts = JsonParser.parseString(javaClass.getResource("/waterboard-layouts.json")!!.readText()).asJsonObject["layouts"].asJsonArray
        val planner = Planner()
        val plans = JsonArray()
        val odinPlansOut = JsonArray()
        val combos = (0 until 32).filter { Integer.bitCount(it) == 3 }
        val rotations = listOf("NORTH", "EAST", "SOUTH", "WEST")
        class Key(val id: Int, val rows: List<String>, val rot: String, val closed: Int)
        val keys = ArrayList<Key>()
        for (lj in layouts) {
            val layout = lj.asJsonObject
            val id = layout["id"].asInt
            if (only != null && only[0].toInt() != id) continue
            val rows = layout["rows"].asJsonArray.map { it.asString }
            for (rot in rotations) for (closed in combos) {
                if (only != null && only.size == 3 && (only[1] != rot || only[2].toInt() != closed)) continue
                keys += Key(id, rows, rot, closed)
            }
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(maxOf(1, Runtime.getRuntime().availableProcessors()))
        val results = keys.map { k -> pool.submit<List<JsonObject>> {
            val (geo, inn) = Geometry.parse(k.rows, k.rot)
            val base = WaterSim.fresh(geo, inn, k.closed)
            val found = LinkedHashMap<Objective, Plan>()
            val odin = odinPlans(k.id, k.closed)
            // two rounds: the cheap objectives first, every objective seeded with Odin's plans and with what any objective has
            // found so far (one search finds what another missed); the Monte Carlo ones get twice the time
            for (round in 0 until 2) for (obj in listOf(Objective.FASTEST, Objective.EASIEST, Objective.SAFEST, Objective.BALANCED)) {
                val trials = if (obj.trials) Planner.TABLE_TRIALS else Planner.LIVE_TRIALS
                val p = planner.plan(base, odin.values.toList() + found.values.map { it.flips }, if (obj.trials) 2 * budget else budget,
                    Random(1000L * k.id + k.closed + 100L * obj.ordinal + 7L * round), threads = 1, objective = obj, trials = trials)
                val old = found[obj]
                found[obj] = if (old == null || planner.checkCost(base, p.flips, obj) <= planner.checkCost(base, old.flips, obj)) p else old
            }
            // last: each objective takes the best plan any of them found, judged on trials none of the searches saw
            val all = found.values.toList()
            for (obj in found.keys.toList()) found[obj] = all.minByOrNull { planner.checkCost(base, it.flips, obj) }!!
            val odinOut = odin.map { (name, flips) ->
                val sp = planner.score(base, flips)
                val r = planner.robustness(base, flips, 1000, seed = 99L)
                println("layout ${k.id} ${k.rot} ${Integer.toBinaryString(k.closed).padStart(5, '0')} ${("odin-" + name).padEnd(8)} " +
                    "${flips.size} flips, solved ${sp.solvedAt} | trials: ${"%.1f".format(r.solveRate * 100)}% solve, median ${r.median}, p90 ${r.p90}")
                JsonObject().apply {
                    addProperty("layout", k.id); addProperty("rotation", k.rot); addProperty("closed", k.closed)
                    addProperty("objective", "ODIN_" + name.uppercase())
                    add("flips", JsonArray().apply { flips.forEach { f -> add(JsonArray().apply { add(f.tick); add(f.lever) }) } })
                    addProperty("solvedAt", sp.solvedAt)
                    addProperty("solveRate", Math.round(r.solveRate * 1000) / 1000.0)
                    addProperty("median", r.median); addProperty("p90", r.p90)
                }
            }
            odinOut + found.map { (obj, p) ->
                val r = planner.robustness(base, p.flips, 1000, seed = 99L)
                println("layout ${k.id} ${k.rot} ${Integer.toBinaryString(k.closed).padStart(5, '0')} ${obj.name.padEnd(8)} " +
                    "${p.flips.size} flips, solved ${p.solvedAt} | trials: ${"%.1f".format(r.solveRate * 100)}% solve, median ${r.median}, p90 ${r.p90}")
                JsonObject().apply {
                    addProperty("layout", k.id); addProperty("rotation", k.rot); addProperty("closed", k.closed)
                    addProperty("objective", obj.name)
                    add("flips", JsonArray().apply { p.flips.forEach { f -> add(JsonArray().apply { add(f.tick); add(f.lever) }) } })
                    addProperty("solvedAt", p.solvedAt)
                    addProperty("solveRate", Math.round(r.solveRate * 1000) / 1000.0)
                    addProperty("median", r.median); addProperty("p90", r.p90)
                }
            }
        } }
        for (f in results) f.get().forEach { if (!it["objective"].asString.startsWith("ODIN_")) plans.add(it) else odinPlansOut.add(it) }
        pool.shutdown()
        val root = JsonObject()
        root.addProperty("version", 1)
        root.addProperty("randomRate", Planner.RANDOM_RATE); root.addProperty("jitter", Planner.JITTER)
        root.add("layouts", JsonArray().apply { layouts.forEach { l -> add(JsonObject().apply { add("rows", l.asJsonObject["rows"]); addProperty("id", l.asJsonObject["id"].asInt) }) } })
        root.add("plans", plans)
        out.parentFile.mkdirs()
        File(out.parentFile, "odin-benchmark.json").writeText(GsonBuilder().disableHtmlEscaping().create().toJson(odinPlansOut))
        out.writeText(GsonBuilder().disableHtmlEscaping().create().toJson(root))
        println("wrote ${plans.size()} plans to $out")
    }
}
