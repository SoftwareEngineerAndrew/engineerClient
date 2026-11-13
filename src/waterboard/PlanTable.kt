package com.engineerclient.waterboard

import com.google.gson.JsonParser

/**
 * The precomputed Water Board plans (assets/engineerclient/waterboard/plans.json, made by test/waterboard/PlanTableGen): for each
 * of the four boards Hypixel builds, each way the room is turned and each set of three closed gates, the best plan from a fresh
 * board for each [Objective], with how it held up over 1000 Monte Carlo trials (random ticks, clicks a tick or two off).
 *
 * A board is matched by its rows exactly as [Geometry.parse] reads them: a board someone has already flipped a lever on isn't in
 * the table, and the live planner takes it from there. Plan ticks count from the fresh board's tick 0 (the first flip at 1 or
 * later); [Entry.at] shifts them to start at the first lever press (a fresh board runs the same whenever it starts).
 */
object PlanTable {
    class Entry(val layout: Int, val flips: List<Flip>, val solvedAt: Int, val solveRate: Double, val median: Int) {
        /** The plan with its first flip at [start] (every flip moved by the same ticks). */
        fun at(start: Int): List<Flip> {
            if (flips.isEmpty()) return flips
            val d = start - flips[0].tick
            return flips.map { Flip(it.tick + d, it.lever) }
        }
    }

    private class Table(val layouts: List<List<String>>, val plans: Map<String, Entry>)

    private val table: Table? by lazy { runCatching { load() }.getOrNull() }

    private fun key(layout: Int, rotation: String, closed: Int, objective: Objective) = "$layout|$rotation|$closed|${objective.name}"

    private fun load(): Table {
        val text = PlanTable::class.java.getResourceAsStream("/assets/engineerclient/waterboard/plans.json")!!.bufferedReader().readText()
        val root = JsonParser.parseString(text).asJsonObject
        val layouts = root["layouts"].asJsonArray.sortedBy { it.asJsonObject["id"].asInt }.map { l -> l.asJsonObject["rows"].asJsonArray.map { it.asString } }
        val plans = HashMap<String, Entry>()
        for (e in root["plans"].asJsonArray) {
            val o = e.asJsonObject
            val obj = runCatching { Objective.valueOf(o["objective"].asString) }.getOrNull() ?: continue
            val flips = o["flips"].asJsonArray.map { val a = it.asJsonArray; Flip(a[0].asInt, a[1].asInt) }
            plans[key(o["layout"].asInt, o["rotation"].asString, o["closed"].asInt, obj)] =
                Entry(o["layout"].asInt, flips, o["solvedAt"].asInt, o["solveRate"].asDouble, o["median"].asInt)
        }
        return Table(layouts, plans)
    }

    /** Which of the four boards [rows] is ([Geometry.parse]'s rows, top first), or -1. */
    fun layoutOf(rows: List<String>): Int = table?.layouts?.indexOfFirst { it == rows } ?: -1

    /** The plan for a fresh board, or null (not one of the four as built, or no table). */
    fun lookup(rows: List<String>, rotation: String, closed: Int, objective: Objective): Entry? {
        val t = table ?: return null
        val layout = t.layouts.indexOfFirst { it == rows }
        if (layout < 0) return null
        return t.plans[key(layout, rotation, closed, objective)]
    }
}
