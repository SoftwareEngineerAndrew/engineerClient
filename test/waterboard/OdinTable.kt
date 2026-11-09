package com.engineerclient.waterboard

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** Odin's Water Board tables (its own water-solutions.json, from libs/odin) as flips, for the tests. */
internal object OdinTable {
    private val names = mapOf("gold_block" to 1, "quartz_block" to 0, "coal_block" to 2, "diamond_block" to 3, "emerald_block" to 4, "hardened_clay" to 5, "water" to 6)

    /**
     * One table entry: [opt] "false" (Desco1's one-flow table) or "true" (Odin's optimized one), board variant [v], the gates
     * closed [key]; [plan]: (tick, lever), ticks after the water lever, the block levers Odin says to flip before it at -20 on.
     */
    class Case(val opt: String, val v: Int, val key: String, val closed: Int, val plan: List<Pair<Int, Int>>) {
        /** Per tick, the levers flipped then (a bit each). */
        fun bits(shift: Int = 0): Map<Int, Int> {
            val at = HashMap<Int, Int>()
            for ((t, id) in plan) at.merge(t + shift, 1 shl id) { a, b -> a xor b }
            return at
        }
    }

    fun load(): JsonObject? {
        val f = File("libs/odin/assets/odin/puzzles/water-solutions.json")
        return if (f.exists()) JsonParser.parseString(f.readText()).asJsonObject else null
    }

    fun cases(tab: JsonObject): List<Case> {
        val out = ArrayList<Case>()
        for (opt in listOf("false", "true")) for (v in 0..3) {
            for ((key, entry) in tab.getAsJsonObject(opt).getAsJsonObject(v.toString()).entrySet()) {
                var closed = 0
                for (ch in key) closed = closed or (1 shl (ch - '0'))
                // Odin's times: seconds after the water lever; 0.0 for a block lever means before it
                val plan = ArrayList<Pair<Int, Int>>()
                for ((lever, times) in entry.asJsonObject.entrySet()) {
                    val id = names[lever]!!
                    for (t in times.asJsonArray.map { it.asDouble }) plan += (if (id == 6) Math.round(t * 20).toInt() else if (t == 0.0) -20 + plan.size else Math.round(t * 20).toInt()) to id
                }
                out += Case(opt, v, key, closed, plan)
            }
        }
        return out
    }
}
