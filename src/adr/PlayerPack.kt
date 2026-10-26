package com.engineerclient.adr

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.util.TreeMap

/**
 * Other players' rows as little as the viewer needs (UploadPacker; the viewer's unfoldPlayers
 * puts them back), the way [MobPlan] does mobs: the viewer slides a player from one row to their
 * next, up to [MobPlan.LERP_GAP] ticks apart (further, they hold still), so a row goes where that
 * slide is near where they really were every tick between - standing still too (a row is put in
 * where they stood until). Players at [PLAYER_STEP] of a block and 5 degrees, within [PLAYER_TOL]
 * blocks and [PLAYER_ANG] degrees; NPCs (Hypixel's are players too: version 2 ids) coarser still,
 * and facing the way they walk between rows, as mobs do ([MobPlan.walkYaw]). A row where what they
 * hold or crouching changes always stays. The recording player's own rows the same, but far finer
 * ([SELF]: "sthin" in the meta), and where the camera frames have where they looked (a frame from
 * [CAM_BEFORE] ticks before to [CAM_AFTER] after) their yaw and pitch are the row before's: the
 * viewer takes them from the frames ("selfcam").
 */
internal class PlayerPlan private constructor(private val rows: TreeMap<Int, JsonArray>) {
    private val kindRe = Regex("""^\{"k":"(\w+)","t":(\d+)""")

    /** The plan's "p" lines instead of the recording's: put in ahead of each line. */
    fun put(line: String, out: (String) -> Unit) {
        val head = kindRe.find(line)
        val t = head?.groupValues?.get(2)?.toInt()
        if (t != null) inject(t - 1, out)
        if (head?.groupValues?.get(1) != "p") { out(line); return }
        inject(t!!, out)
    }

    fun end(out: (String) -> Unit) = inject(Int.MAX_VALUE, out)

    private fun inject(upTo: Int, out: (String) -> Unit) {
        while (rows.isNotEmpty() && rows.firstKey() <= upTo) {
            val (t, d) = rows.pollFirstEntry()
            out(JsonObject().apply { addProperty("k", "p"); addProperty("t", t); add("d", d) }.toString())
        }
    }

    private class Kind(val step: Double, val tol: Double, val ang: Double, val angStep: Double)

    companion object {
        const val PLAYER_STEP = 0.1
        const val PLAYER_TOL = 0.35
        const val PLAYER_ANG = 15.0
        private val PLAYER = Kind(PLAYER_STEP, PLAYER_TOL, PLAYER_ANG, 5.0)
        private val NPC = Kind(0.2, 1.5, MobPlan.WALK_ANG_TOL, 20.0)
        /** The recording player (their camera follows these: near exact). */
        private val SELF = Kind(0.05, 0.1, 2.0, 0.1)
        /** A camera frame from this many ticks before a row of the recording player's to one after has their look (the viewer's unfoldPlayers). */
        const val CAM_BEFORE = 2.0
        const val CAM_AFTER = 1.0
        private const val NPC_VERSION = 2

        fun read(lines: Sequence<String>, self: String, camTimes: DoubleArray): PlayerPlan {
            // Each player's rows (as recorded), in stretches a "pgone" ends.
            val open = HashMap<String, MutableList<Pair<Int, JsonArray>>>()
            val stretches = ArrayList<List<Pair<Int, JsonArray>>>()
            val selfRows = TreeMap<Int, JsonArray>()
            val kindRe = Regex("""^\{"k":"(\w+)","t":(\d+)""")
            for (line in lines) {
                val kind = kindRe.find(line)?.groupValues?.get(1) ?: continue
                if (kind != "p" && kind != "pgone") continue
                val l = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                val t = l["t"]?.asInt ?: continue
                if (kind == "pgone") { val name = l["name"]?.asString ?: continue; open.remove(name)?.let { stretches += it }; continue }
                for (d in l.getAsJsonArray("d") ?: continue) {
                    val r = d as? JsonArray ?: continue
                    val name = r[0].asString
                    if (name == self || r.size() < 6 || (1..5).any { !r[it].isJsonPrimitive || !r[it].asJsonPrimitive.isNumber }) { selfRows.getOrPut(t) { JsonArray() }.add(r); continue }
                    open.getOrPut(name) { ArrayList() } += t to r
                }
            }
            stretches += open.values
            // The recording player's look where the camera frames have it: the row before's (no change to send).
            var look: Pair<com.google.gson.JsonElement, com.google.gson.JsonElement>? = null
            for ((t, d) in selfRows) for (e in d) {
                val r = e.asJsonArray
                if (r.size() < 6 || r[0].asString != self) continue
                if (look != null && camNear(camTimes, t)) { r.set(4, look.first); r.set(5, look.second) }
                look = r[4] to r[5]
            }
            // The recording player's own: thinned too, but much finer ([SELF]: "sthin" in the meta).
            val out = TreeMap<Int, JsonArray>()
            val mine = ArrayList<Pair<Int, JsonArray>>()
            for ((t, d) in selfRows) for (e in d) {
                val r = e.asJsonArray
                if (r[0].asString == self && r.size() >= 6 && (1..5).all { r[it].isJsonPrimitive && r[it].asJsonPrimitive.isNumber }) mine += t to r
                else out.getOrPut(t) { JsonArray() }.add(r)
            }
            if (mine.isNotEmpty()) stretches += mine
            for (list in stretches) for ((t, r) in thin(list, if (list === mine) SELF else null)) out.getOrPut(t) { JsonArray() }.add(r)
            return PlayerPlan(out)
        }

        /** A stretch of one player's rows: the ones to send, rounded, with their ticks. */
        private fun thin(list: List<Pair<Int, JsonArray>>, kind: Kind? = null): List<Pair<Int, JsonArray>> {
            val first = list.first().second
            val k = kind ?: if (first.size() > 7 && runCatching { first[7].asInt }.getOrNull() == NPC_VERSION) NPC else PLAYER
            // Points: each row, and where they stood until the next (it holds still till then).
            class Pt(val t: Int, val v: DoubleArray, val row: JsonArray, val must: Boolean)
            val pts = ArrayList<Pt>(list.size * 2)
            var extra: String? = null
            for ((i, tr) in list.withIndex()) {
                val (t, r) = tr
                val v = DoubleArray(5) { r[it + 1].asDouble }
                if (i > 0) {
                    val p = pts.last()
                    if (t - p.t >= 2) pts += Pt(t - 1, p.v, p.row, false)
                }
                // (what they hold, crouching...: everything after pitch)
                val e = JsonArray().apply { for (j in 6 until r.size()) add(r[j]) }.toString()
                pts += Pt(t, v, r, i == 0 || e != extra)
                extra = e
            }
            fun fits(a: Int, c: Int): Boolean {
                val pa = pts[a]; val pc = pts[c]
                val span = (pc.t - pa.t).toDouble()
                for (j in a + 1 until c) {
                    val pj = pts[j]
                    if (pj.must) return false
                    val f = if (span > MobPlan.LERP_GAP) 0.0 else (pj.t - pa.t) / span
                    for (i in 0..2) if (Math.abs(pa.v[i] + (pc.v[i] - pa.v[i]) * f - pj.v[i]) > k.tol) return false
                    // (NPCs are mobs: facing the way they go between rows, as the viewer has them - "walkyaw")
                    val walk = if (k === NPC && span <= MobPlan.LERP_GAP) walkDir(pa.v, pc.v, span) else null
                    val yaw = if (walk != null) MobPlan.walkYaw(pa.v[3], walk, pc.v[3], (pj.t - pa.t).toDouble(), span) else pa.v[3] + MobPlan.turn(pc.v[3] - pa.v[3]) * f
                    if (Math.abs(MobPlan.turn(yaw - pj.v[3])) > k.ang) return false
                    if (Math.abs(MobPlan.turn(pa.v[4] + MobPlan.turn(pc.v[4] - pa.v[4]) * f - pj.v[4])) > k.ang) return false
                }
                return true
            }
            val keep = ArrayList<Pt>()
            var a = 0
            keep += pts[0]
            while (a < pts.size - 1) {
                var m = a + 1
                while (m + 1 < pts.size && fits(a, m + 1)) m++
                keep += pts[m]; a = m
            }
            return keep.map { p ->
                val r = JsonArray()
                r.add(p.row[0])
                for (i in 0..2) r.add(round(p.v[i], k.step))
                for (i in 3..4) (Math.round(MobPlan.turn(p.v[i]) / k.angStep) * k.angStep).let { r.add(if (k.angStep < 1) MobPlan.tenth(it) else JsonPrimitive(it.toInt())) }
                for (j in 6 until p.row.size()) r.add(p.row[j])
                p.t to r
            }
        }

        /** MobPlan.walkDir for a row's values (x, y, z, yaw, pitch). */
        private fun walkDir(a: DoubleArray, c: DoubleArray, n: Double): Double? =
            MobPlan.walkDir(doubleArrayOf(0.0, a[0], a[1], a[2]), doubleArrayOf(0.0, c[0], c[1], c[2]), n)

        /** Whether a camera frame is from [CAM_BEFORE] ticks before tick [t] to [CAM_AFTER] after. */
        private fun camNear(times: DoubleArray, t: Int): Boolean {
            var i = java.util.Arrays.binarySearch(times, t - CAM_BEFORE)
            if (i < 0) i = -i - 1
            return i < times.size && times[i] <= t + CAM_AFTER
        }

        private fun round(v: Double, step: Double): JsonPrimitive = MobPlan.hundredth(Math.round(v / step) * step)
    }
}
