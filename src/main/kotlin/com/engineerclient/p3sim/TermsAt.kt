package com.engineerclient.p3sim

import kotlin.math.roundToInt

/**
 * A P3 start part way through: [n] server ticks after Goldor's first line, with everything your plan
 * has done by then done (yours too). Each job is done at its plan time (seconds into the section
 * it's done in); a section ends with its last terminal/device/lever, its door opens then if the
 * gate is down, else when the gate goes (5 s at the latest, as on Hypixel).
 */
class TermsAt(val n: Int) {
    /** n each section started at (5 = the core open). */
    val sectionN = IntArray(6)
    /** n each section's last station was done at. */
    val endN = IntArray(6) { -1 }
    /** n each gate went at (-1: not by [n]). */
    val gateN = IntArray(5) { -1 }
    /** n each job is done at. */
    val doneAt = HashMap<String, Int>()
    /** The section in progress at [n] (5: the core). */
    val section: Int
    /** The jobs done by [n] ("S2 T3", "gate 2"...). */
    val done: Set<String>

    init {
        val times = P3Plan.plan().times
        fun at(job: String, s: Int): Int {
            val (ts, sec) = times[job] ?: (s to 5.0)
            return sectionN[ts.coerceIn(1, s)] + (sec * 20).roundToInt()
        }
        for (s in 1..4) {
            val jobs = P3Plan.jobsIn(s)
            val stations = jobs.filter { !it.startsWith("gate") }
            stations.forEach { doneAt[it] = at(it, s) }
            val end = (stations.maxOfOrNull { doneAt.getValue(it) } ?: sectionN[s]).coerceAtLeast(sectionN[s])
            endN[s] = end
            sectionN[s + 1] = if (s == 4) end else {
                val gate = if ("gate $s" in jobs) at("gate $s", s) else Int.MAX_VALUE
                val door = if (gate <= end) end else minOf(gate, end + 100)
                gateN[s] = minOf(gate, door)
                doneAt["gate $s"] = gateN[s]
                door
            }
        }
        section = (1..5).last { sectionN[it] <= n }
        done = doneAt.filter { (job, at) -> at <= n || sectionOf(job) < section }.keys
        for (s in 1..3) if (gateN[s] > n && s >= section) gateN[s] = -1
    }

    /** Where you are at [n]: on your next job if it's in this section, else your early enter, else the section's door. */
    fun yourSpot(): Spots.Spot {
        val next = P3Plan.mine().filter { it !in done }.minByOrNull { doneAt[it] ?: Int.MAX_VALUE }
        if (next != null && sectionOf(next) == section) Party.STANDS[next]?.let { return Spots.Spot(next, it.x, it.y, it.z, Spots.p3Start(section).yaw) }
        P3Plan.ee(section + 1)?.takeIf { it.byYou }?.let { return Spots.Spot(it.label, it.spot.x, it.spot.y, it.spot.z, it.yaw, it.pitch) }
        return Spots.p3Start(section)
    }

    fun label() = "%.1fs".format(n / 20.0)

    private fun sectionOf(job: String) = job.removePrefix("gate ").toIntOrNull() ?: job.substring(1, 2).toInt()
}
