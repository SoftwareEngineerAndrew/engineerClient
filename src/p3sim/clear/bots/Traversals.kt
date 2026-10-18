package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.pack.PackReport
import com.engineerclient.p3sim.pack.arr
import com.engineerclient.p3sim.pack.asArr
import com.engineerclient.p3sim.pack.asDoubleOrNull
import com.engineerclient.p3sim.pack.int
import com.engineerclient.p3sim.pack.obj
import com.engineerclient.p3sim.pack.readGzipJson
import com.engineerclient.p3sim.pack.str
import com.engineerclient.p3sim.run.PlacedRoom
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/** One recorded walk through a room design in the canonical room frame: the player's x and z every few ticks, [dt] ticks after the walk began. */
class Traversal(val ticks: Int, val dt: IntArray, val x: DoubleArray, val z: DoubleArray) {
    init { require(dt.size == x.size && x.size == z.size && x.isNotEmpty()) { "a traversal needs at least one point" } }
}

/** What the planner asks for a room's walk: legs filling [t0, t1] starting at [from], or null for "use the plain route". */
fun interface RoomTraversals {
    fun legs(room: PlacedRoom, from: Pt, t0: Int, t1: Int, bpt: Double, random: Random): Pair<List<Leg>, Pt>?
}

/**
 * Recorded single-room walks, an OPTIONAL data pack `bots.traversals.json.gz` next to the other ADS packs (format in docs/ads/bots.md). The
 * jar ships none and the repository holds none; a tool outside the repository builds the file from a corpus of recorded rooms. Missing is
 * normal: the plain route of [RoomRoutes] is used.
 *
 * A walk is stored in the room's canonical frame, so one recording serves every placement of the design: [legs] puts it into the world with
 * the room's [com.engineerclient.p3sim.pack.rooms.Placement], walks the bot to its first point, plays it (slowed or sped up to fit the
 * time the plan gives the room, within [MIN_FIT] to [MAX_FIT]) and lets the bot stand about for the rest.
 */
class TraversalLibrary(private val byDesign: Map<String, List<Traversal>>) : RoomTraversals {
    val designs: Int get() = byDesign.size
    val walks: Int get() = byDesign.values.sumOf { it.size }

    fun has(designId: String): Boolean = byDesign[designId]?.isNotEmpty() == true

    override fun legs(room: PlacedRoom, from: Pt, t0: Int, t1: Int, bpt: Double, random: Random): Pair<List<Leg>, Pt>? {
        val placement = room.placement ?: return null
        val list = byDesign[room.designId] ?: return null
        if (list.isEmpty()) return null
        val budget = t1 - t0
        // Pick the walk whose start is nearest where the bot comes in, among those that fit the budget.
        fun world(tr: Traversal, i: Int): Pt = placement.toWorld(tr.x[i].toInt(), 0, tr.z[i].toInt()).let { Pt(it.x + 0.5, it.z + 0.5) }
        val fitting = list.filter { tr ->
            val entry = from.dist(world(tr, 0)) / bpt.coerceAtLeast(0.1)
            val room4play = budget - entry
            room4play >= tr.ticks * MIN_FIT
        }
        if (fitting.isEmpty()) return null
        val near = fitting.sortedBy { from.dist(world(it, 0)) }.take(3)
        val tr = near[random.nextInt(near.size)]
        val legs = ArrayList<Leg>()
        var t = t0
        var cur = from
        val first = world(tr, 0)
        val entryTicks = Math.ceil(from.dist(first) / bpt.coerceAtLeast(0.1)).toInt()
        if (entryTicks > 0) { legs += Leg(t, t + entryTicks, cur, first, hop = from.dist(first) / entryTicks > BotTuning.HOP_ABOVE_BPT); t += entryTicks; cur = first }
        val play = budget - entryTicks
        val fit = (play.toDouble() / tr.ticks.coerceAtLeast(1)).coerceAtMost(MAX_FIT)
        val start = t
        for (i in 1 until tr.dt.size) {
            val at = (start + tr.dt[i] * fit).toInt().coerceAtMost(t1)
            val p = world(tr, i)
            if (at < t) continue
            if (at > t || p != cur) { legs += Leg(t, at, cur, p, hop = p.dist(cur) > BotTuning.HOP_BLOCKS * 1.5 && p.dist(cur) / maxOf(1, at - t) > BotTuning.HOP_ABOVE_BPT); t = at; cur = p }
        }
        if (t < t1) legs += Leg(t, t1, cur, cur)
        return legs to cur
    }

    companion object {
        const val FILE = "bots.traversals.json.gz"
        const val FORMAT = "ads-bot-traversals"
        const val VERSION = 1

        /** Walks may be played this much slower than recorded (down to) ... */
        const val MIN_FIT = 0.6
        /** ... and no more than this much slower than recorded (a room with a long budget has the bot stand about after the walk). */
        const val MAX_FIT = 1.4

        /** Reads the optional file from [dir]. Null when it is not there or not readable; a bad file says why in [report] (a warning, never an error). */
        fun load(dir: Path, report: PackReport = PackReport()): TraversalLibrary? {
            val path = dir.resolve(FILE)
            if (!Files.isRegularFile(path)) return null
            return try {
                fromJson(Files.newInputStream(path).use { readGzipJson(it) }, report)
            } catch (e: Exception) {
                report.warn("The bot walks $FILE could not be read: ${e.message}. The bots use plain routes.")
                null
            }
        }

        fun fromJson(root: com.google.gson.JsonObject, report: PackReport = PackReport()): TraversalLibrary? {
            if (root.str("format") != FORMAT) { report.warn("$FILE is not a bot walks file (format ${root.str("format")}). The bots use plain routes."); return null }
            val v = root.int("version") ?: 0
            if (v > VERSION) { report.warn("$FILE is version $v but this game reads version $VERSION. The bots use plain routes."); return null }
            val out = HashMap<String, List<Traversal>>()
            val rooms = root.obj("rooms") ?: return TraversalLibrary(out)
            for ((design, el) in rooms.entrySet()) {
                val walks = el.asArr() ?: continue
                val list = ArrayList<Traversal>()
                for (w in walks) {
                    val o = w.asJsonObject
                    val pts = o.arr("pts") ?: continue
                    val dt = ArrayList<Int>(); val xs = ArrayList<Double>(); val zs = ArrayList<Double>()
                    for (p in pts) {
                        val a = p.asArr() ?: continue
                        if (a.size() < 3) continue
                        dt += (a[0].asDoubleOrNull() ?: continue).toInt(); xs += a[1].asDoubleOrNull() ?: continue; zs += a[2].asDoubleOrNull() ?: continue
                    }
                    if (xs.size < 2) continue
                    list += Traversal(o.int("ticks") ?: dt.last(), dt.toIntArray(), xs.toDoubleArray(), zs.toDoubleArray())
                }
                if (list.isNotEmpty()) out[design] = list
            }
            return TraversalLibrary(out)
        }
    }
}
