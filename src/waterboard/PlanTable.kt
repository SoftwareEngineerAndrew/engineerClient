package com.engineerclient.waterboard

import com.google.gson.JsonParser

/**
 * The precomputed Water Board plans (assets/engineerclient/waterboard/plans.json): for each
 * of the four boards Hypixel builds, each way the room is turned and each set of three closed gates, the best plan from a fresh
 * board for each [Objective], with the tick it solves on and each flip's window, so a fresh board's plan is only looked up.
 *
 * A board is matched by its rows exactly as [Geometry.parse] reads them: a board someone has already flipped a lever on isn't in
 * the table, and the live planner takes it from there. Plan ticks count from the fresh board's tick 0 (the first flip at 1 or
 * later); [Entry.at] shifts them to start at the first lever press (a fresh board runs the same whenever it starts).
 */
object PlanTable {
    class Entry(private val flips: List<Flip>, private val solvedAt: Int, private val window: List<IntRange>) {
        /** The plan with its first flip at [start] (every flip, and the solve, moved by the same ticks). */
        fun at(start: Int): Plan {
            val d = start - flips[0].tick
            return Plan(flips.map { Flip(it.tick + d, it.lever) }, solvedAt + d, 0.0, window)
        }
    }

    private class Table(val layouts: List<List<String>>, val plans: Map<Int, Entry>)

    private val ROTATIONS = listOf("NORTH", "EAST", "SOUTH", "WEST")

    private val table: Table? by lazy { runCatching { read() }.getOrNull() }

    private fun key(layout: Int, rotation: String, closed: Int, objective: Objective) =
        ((layout * 4 + ROTATIONS.indexOf(rotation)) * 32 + closed) * Objective.entries.size + objective.ordinal

    private fun read(): Table {
        val text = PlanTable::class.java.getResourceAsStream("/assets/engineerclient/waterboard/plans.json")!!.bufferedReader().readText()
        val root = JsonParser.parseString(text).asJsonObject
        val layouts = root["layouts"].asJsonArray.sortedBy { it.asJsonObject["id"].asInt }.map { l -> l.asJsonObject["rows"].asJsonArray.map { it.asString } }
        val plans = HashMap<Int, Entry>()
        for (e in root["plans"].asJsonArray) {
            val o = e.asJsonObject
            val obj = runCatching { Objective.valueOf(o["objective"].asString) }.getOrNull() ?: continue
            val flips = o["flips"].asJsonArray.map { val a = it.asJsonArray; Flip(a[0].asInt, a[1].asInt) }
            val window = o["window"].asJsonArray.map { val a = it.asJsonArray; a[0].asInt..a[1].asInt }
            if (flips.isEmpty() || window.size != flips.size) continue
            plans[key(o["layout"].asInt, o["rotation"].asString, o["closed"].asInt, obj)] = Entry(flips, o["solvedAt"].asInt, window)
        }
        return Table(layouts, plans)
    }

    /** Reads the table now, so the first board doesn't wait for it. */
    fun load() { table }

    /** Which of the four boards [rows] is ([Geometry.parse]'s rows, top first), or -1. */
    fun layoutOf(rows: List<String>): Int = table?.layouts?.indexOfFirst { it == rows } ?: -1

    /** The plan for a fresh board [layoutOf] knows, or null. */
    fun lookup(layout: Int, rotation: String, closed: Int, objective: Objective): Entry? =
        if (layout < 0) null else table?.plans?.get(key(layout, rotation, closed, objective))
}
