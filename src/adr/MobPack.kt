package com.engineerclient.adr

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.TreeMap

/**
 * The mobs of an upload, as little as the viewer needs to show them the same (UploadPacker; the
 * viewer's unfoldEvents puts it all back):
 *
 *  - Moves ("e" rows) at a tenth of a block and 5 degrees, and only where the viewer can't draw
 *    them from the ones either side: it slides a mob from one row to its next (up to [LERP_GAP]
 *    ticks on, further it holds still), so a row goes when that slide is within [POS_TOL] blocks
 *    and [ANG_TOL] degrees of where the mob really was at every tick between - standing still
 *    included (a row is put in where it stood until, when sliding would be too far off).
 *  - Armor stands that draw nothing - invisible all their life, never a name, never holding or
 *    wearing anything - aren't sent at all (most of Hypixel's are).
 *  - A spawn says how long the mob lives ("life", in ticks): no "gone" line.
 *  - A mob coming back into range (the server sends it again, same id) is "b": the same as when it
 *    went, so only where it is; the gear and stand look it had come back with it, and the lines the
 *    server sends again for them go.
 *  - Gear, stand and name lines that change nothing go.
 *  - A stand's look in the tick it spawns is on the spawn ("f", "pose").
 *  - "name" only where it isn't the coloured name ("c") without its § codes.
 *  - Spawns at a tenth of a block (armor stands wearing something a hundredth) and whole degrees.
 *
 * Lives a party member's upload already has, the same, can be left out (see [UploadPacker]): the
 * viewer takes each life of a mob from whichever recording has it.
 */
internal class MobPlan private constructor(
    /** Stands that draw nothing: every line about them goes. */
    val nothing: Set<Int>,
    /** Each mob's lives, in order. */
    val lives: Map<Int, List<Life>>,
    private val farHalf: Boolean,
    /** Other players' rows (t, x, y, z) by name, for checking a party member's upload has them the same. */
    val players: Map<String, List<DoubleArray>>,
    /** Stands that ever wear or hold something (their spawns stay at a hundredth: see MobWriter). */
    val worn: Set<Int>,
) {
    class Life(val id: Int, val index: Int, val spawn: Int, var gone: Int?, val type: String, val proj: Boolean) {
        /** Its moves: t, x, y, z, yaw, head (NaN: none). */
        val rows = ArrayList<DoubleArray>()
        /** Where it spawned. */
        var at = DoubleArray(3)
        /** Its gone folded into the spawn ("life"): no other spawn of it in that tick. */
        var folded = false
    }

    /** The rows to send, by tick, without the lives [dropped] leaves out. */
    fun rows(dropped: (Life) -> Boolean): TreeMap<Int, JsonArray> {
        val out = TreeMap<Int, JsonArray>()
        for (list in lives.values) for (life in list) {
            if (life.id in nothing || dropped(life) || life.rows.isEmpty()) continue
            for (r in thin(life.rows)) out.getOrPut(r[0].toInt()) { JsonArray() }.add(JsonArray().apply {
                add(life.id); add(tenth(r[1])); add(tenth(r[2])); add(tenth(r[3])); add(angle(r[4]))
                if (!r[5].isNaN()) add(angle(r[5]))
            })
        }
        return out
    }

    /** The life of [id] that tick [t] is in (from its spawn to its gone), or null. */
    fun lifeAt(id: Int, t: Int): Life? = lives[id]?.lastOrNull { it.spawn <= t }?.takeIf { it.gone == null || t <= it.gone!! }

    /**
     * The rows the viewer can't draw from the others (see the top), in order. First the true path
     * as points with a straight line between each two: a row, and where a mob stood until it moved
     * on (the tick before its next row: it holds still till then), unless that is the next tick.
     */
    private fun thin(rows: List<DoubleArray>): List<DoubleArray> {
        val pts = ArrayList<DoubleArray>(rows.size * 2)
        for ((i, r) in rows.withIndex()) {
            if (i > 0) {
                val p = rows[i - 1]
                val gap = (r[0] - p[0]).toInt()
                // (far mobs move every other tick: the viewer fills that tick in halfway, as it was)
                if (gap >= 3 || (gap == 2 && !farHalf)) pts += p.copyOf().also { it[0] = r[0] - 1 }
            }
            pts += r
        }
        val keep = ArrayList<DoubleArray>()
        var k = 0
        keep += pts[0]
        while (k < pts.size - 1) {
            var m = k + 1
            while (m + 1 < pts.size && fits(pts, k, m + 1)) m++
            keep += pts[m]; k = m
        }
        return keep
    }

    /** Whether the viewer's way from pts[a] to pts[c] stays near every point between. */
    private fun fits(pts: List<DoubleArray>, a: Int, c: Int): Boolean {
        val pa = pts[a]; val pc = pts[c]
        val span = pc[0] - pa[0]
        if (span > LERP_GAP) {
            // Held still till the next row: right only if it really did.
            for (j in a + 1 until c) if (!near(pa, pts[j])) return false
            return true
        }
        for (j in a + 1 until c) {
            val pj = pts[j]
            val f = (pj[0] - pa[0]) / span
            for (i in 1..3) if (Math.abs(pa[i] + (pc[i] - pa[i]) * f - pj[i]) > POS_TOL) return false
            for (i in 4..5) if (!pa[i].isNaN() && !pc[i].isNaN() && !pj[i].isNaN() && Math.abs(turn(pa[i] + turn(pc[i] - pa[i]) * f - pj[i])) > ANG_TOL) return false
        }
        return true
    }

    private fun near(a: DoubleArray, b: DoubleArray): Boolean =
        (1..3).all { Math.abs(a[it] - b[it]) <= POS_TOL } && (4..5).all { a[it].isNaN() || b[it].isNaN() || Math.abs(turn(a[it] - b[it])) <= ANG_TOL }

    companion object {
        const val POS_TOL = 0.5
        const val ANG_TOL = 20.0
        /** The viewer slides a mob between two rows up to this many ticks apart (its LERP_GAP). */
        const val LERP_GAP = 60
        const val STAND = "minecraft:armor_stand"
        private const val INVISIBLE = 2

        fun turn(d: Double) = ((d % 360) + 540) % 360 - 180
        fun tenth(v: Double): JsonPrimitive = JsonPrimitive(BigDecimal(v).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it })
        fun hundredth(v: Double): JsonPrimitive = JsonPrimitive(BigDecimal(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it })
        fun angle(v: Double): JsonPrimitive = JsonPrimitive((Math.round(turn(v) / 5.0) * 5).toInt().let { if (it == -180) 180 else it })

        /**
         * Read from a run's lines (as recorded): [skip] says which ids aren't mobs to plan for
         * (projectiles folded into their "proj" line).
         */
        fun read(lines: Sequence<String>, skip: (Int) -> Boolean): MobPlan {
            var farHalf = false
            val players = HashMap<String, MutableList<DoubleArray>>()
            val lives = HashMap<Int, MutableList<Life>>()
            val current = HashMap<Int, Life>()
            val spawnTicks = HashMap<Int, MutableSet<Int>>() // id -> ticks it spawned at
            val stands = HashMap<Int, Boolean>() // stand id -> invisible in every stand line so far
            val shows = HashSet<Int>() // has had a name, gear or a visible look
            val wornIds = HashSet<Int>()
            val kindRe = Regex(""""k":"(\w+)"""")
            for (line in lines) {
                val kind = kindRe.find(line.take(24))?.groupValues?.get(1) ?: continue
                if (kind != "spawn" && kind != "gone" && kind != "e" && kind != "stand" && kind != "eq" && kind != "name" && kind != "p" && kind != "meta") continue
                val l = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                if (kind == "meta") { farHalf = (l["farHalf"]?.asInt ?: 0) > 0; continue }
                val t = l["t"]?.asInt ?: continue
                when (kind) {
                    "p" -> for (d in l.getAsJsonArray("d") ?: continue) {
                        val r = d.asJsonArray
                        if (r.size() < 4 || !r[1].isJsonPrimitive) continue
                        players.getOrPut(r[0].asString) { ArrayList() } += doubleArrayOf(t.toDouble(), r[1].asDouble, r[2].asDouble, r[3].asDouble)
                    }
                    "e" -> for (d in l.getAsJsonArray("d") ?: continue) {
                        val r = d.asJsonArray
                        val id = r[0].asInt
                        val life = current[id] ?: continue
                        life.rows += doubleArrayOf(t.toDouble(), r[1].asDouble, r[2].asDouble, r[3].asDouble, r[4].asDouble, if (r.size() > 5) r[5].asDouble else Double.NaN)
                    }
                    "spawn" -> {
                        val id = l["id"]?.asInt ?: continue
                        if (skip(id)) continue
                        current[id]?.let { if (it.gone == null) it.gone = t }
                        val list = lives.getOrPut(id) { ArrayList() }
                        val type = l["type"]?.asString ?: ""
                        val life = Life(id, list.size, t, null, type, l.has("v"))
                        life.at = doubleArrayOf(l["x"]?.asDouble ?: 0.0, l["y"]?.asDouble ?: 0.0, l["z"]?.asDouble ?: 0.0)
                        list += life; current[id] = life
                        spawnTicks.getOrPut(id) { HashSet() } += t
                        if (type == STAND && !(l["name"]?.asString.isNullOrEmpty() && l["c"]?.asString.isNullOrEmpty())) shows += id
                    }
                    "gone" -> {
                        val id = l["id"]?.asInt ?: continue
                        val life = current.remove(id) ?: continue
                        life.gone = t
                    }
                    "stand" -> {
                        val id = l["id"]?.asInt ?: continue
                        val f = l["f"]?.asInt ?: 0
                        stands[id] = (stands[id] ?: true) && (f and INVISIBLE) != 0
                    }
                    "eq" -> {
                        val id = l["id"]?.takeIf { !it.isJsonNull }?.asInt ?: continue
                        val worn = l.getAsJsonArray("eq")?.any { it is JsonPrimitive && it.asString.isNotEmpty() } == true
                        if (worn || !l["headTex"]?.takeIf { !it.isJsonNull }?.asString.isNullOrEmpty()) { shows += id; wornIds += id }
                    }
                    "name" -> {
                        val id = l["id"]?.asInt ?: continue
                        if (!(l["name"]?.asString.isNullOrEmpty() && l["c"]?.asString.isNullOrEmpty())) shows += id
                    }
                }
            }
            for (list in lives.values) for (life in list) {
                val g = life.gone ?: continue
                life.folded = !life.proj && spawnTicks[life.id]?.contains(g) != true
            }
            val nothing = lives.filter { (id, list) -> list.all { it.type == STAND } && stands[id] == true && id !in shows }.keys
            return MobPlan(nothing, lives, farHalf, players, wornIds)
        }
    }
}

/**
 * The mob lines of an upload as they go out (see [MobPlan]): spawns, gone, gear, stand looks and
 * names rewritten, the moves put in from the plan (all of a tick's ahead of the next tick's lines).
 */
internal class MobWriter(private val plan: MobPlan, private val dropped: (MobPlan.Life) -> Boolean) {
    private val rows = plan.rows(dropped)
    private val spawned = HashMap<Int, Int>() // id -> lives seen so far
    /** What the viewer has for each mob: its spawn's fields, gear and stand look. */
    private class State(var spawn: JsonObject, var eq: String? = null, var stand: String? = null, var name: String? = null)
    private val now = HashMap<Int, State>()
    private val left = HashMap<Int, State>() // as each was when it last went (lives this upload has)
    private var pending: JsonObject? = null // a spawn waiting for its stand look (the line after it)

    private val kindRe = Regex("""^\{"k":"(\w+)","t":(\d+)""")

    fun put(line: String, out: (String) -> Unit) {
        val head = kindRe.find(line)
        val kind = head?.groupValues?.get(1)
        val t = head?.groupValues?.get(2)?.toInt()
        if (t != null) inject(t - 1, out)
        when (kind) {
            "e" -> { flush(out); return } // (the plan's rows instead)
            "spawn", "gone", "stand", "eq", "name", "tag", "frame" -> {}
            else -> { flush(out); out(line); return }
        }
        val l = JsonParser.parseString(line).asJsonObject
        val id = l["id"]?.takeIf { !it.isJsonNull }?.asInt
        if (id == null) { flush(out); out(line); return } // (players' gear)
        if (id in plan.nothing) return
        if (kind == "spawn" && l.has("v")) { flush(out); spawned.merge(id, 1, Int::plus); out(line); return } // (a projectile not folded: as it is)
        if (kind == "spawn") {
            flush(out)
            val n = spawned.merge(id, 1, Int::plus)!! - 1
            val life = plan.lives[id]?.getOrNull(n)
            if (life == null) { out(line); return }
            if (dropped(life)) { now.remove(id); left.remove(id); return }
            pending = spawn(l, life, id)
            return
        }
        val life = plan.lifeAt(id, t!!)
        if (life == null || dropped(life)) return
        val st = now[id]
        when (kind) {
            "gone" -> {
                flush(out)
                now.remove(id)?.let { left[id] = it }
                if (!life.folded) out(line)
                return
            }
            "stand" -> {
                val key = JsonArray().apply { add(l["f"] ?: JsonNull.INSTANCE); add(l["pose"] ?: JsonNull.INSTANCE) }.toString()
                if (st != null && st.stand == key) return
                st?.stand = key
                val p = pending
                if (p != null && p["id"].asInt == id && p["t"].asInt == t) { l["f"]?.let { p.add("f", it) }; l["pose"]?.let { p.add("pose", it) }; return }
                flush(out); out(line); return
            }
            "eq" -> {
                flush(out)
                val key = JsonArray().apply { add(l["eq"] ?: JsonNull.INSTANCE); add(l["headTex"] ?: JsonNull.INSTANCE) }.toString()
                if (st != null && st.eq == key) return
                st?.eq = key
                out(line); return
            }
            "name" -> {
                flush(out)
                val c = l["c"]?.takeIf { !it.isJsonNull }?.asString
                val name = l["name"]?.takeIf { !it.isJsonNull }?.asString ?: ""
                val key = "$name\u0000${c ?: ""}"
                if (st != null && st.name == key) return
                st?.name = key
                plainName(l)
                out(l.toString()); return
            }
            else -> { flush(out); out(line) }
        }
    }

    /** The rows up to tick [t], and a spawn still waiting. */
    fun end(out: (String) -> Unit) { flush(out); inject(Int.MAX_VALUE, out) }

    private fun inject(upTo: Int, out: (String) -> Unit) {
        while (rows.isNotEmpty() && rows.firstKey() <= upTo) {
            flush(out)
            val (t, d) = rows.pollFirstEntry()
            out(JsonObject().apply { addProperty("k", "e"); addProperty("t", t); add("d", d) }.toString())
        }
    }

    private fun flush(out: (String) -> Unit) { pending?.let { out(it.toString()) }; pending = null }

    /** A spawn as it goes up: back ("b") when it is how it last went, else whole but rounder. */
    private fun spawn(l: JsonObject, life: MobPlan.Life, id: Int): JsonObject {
        val fields = JsonObject().apply { for ((k, v) in l.entrySet()) if (k !in PLACE && k != "name" && k != "c") add(k, v) }
        val name = l["name"]?.takeIf { !it.isJsonNull }?.asString ?: ""
        val c = l["c"]?.takeIf { !it.isJsonNull }?.asString
        val before = left.remove(id)
        val o = JsonObject()
        o.add("k", l["k"]); o.add("t", l["t"]); o.add("id", l["id"])
        val key = "$name\u0000${c ?: ""}"
        val back = before != null && before.spawn == fields && before.name == key
        if (back) o.addProperty("b", 1)
        else for ((k, v) in l.entrySet()) if (k !in PLACE) o.add(k, v)
        // (stands wearing something at a hundredth - a head on one is placed by its spawn -, the rest at a tenth, as moves)
        val fine = life.type == MobPlan.STAND && id in plan.worn
        for (k in listOf("x", "y", "z")) l[k]?.takeIf { it.isJsonPrimitive }?.let { o.add(k, if (fine) MobPlan.hundredth(it.asDouble) else MobPlan.tenth(it.asDouble)) }
        for (k in listOf("yaw", "headYaw")) l[k]?.takeIf { it.isJsonPrimitive }?.let { o.addProperty(k, Math.round(it.asDouble).toInt()) }
        if (life.folded) o.addProperty("life", life.gone!! - life.spawn + 1)
        if (!back) plainName(o)
        now[id] = if (back) State(fields, before!!.eq, before.stand, key) else State(fields, name = key)
        return o
    }

    companion object {
        private val PLACE = setOf("k", "t", "id", "x", "y", "z", "yaw", "headYaw")
        private val CODES = Regex("§.")
        /** "name" left out where it is "c" without its colour codes (the viewer works it out). */
        fun plainName(o: JsonObject) {
            val c = o["c"]?.takeIf { !it.isJsonNull }?.asString ?: return
            if (o["name"]?.takeIf { !it.isJsonNull }?.asString == c.replace(CODES, "")) o.remove("name")
        }
    }
}

/**
 * A projectile as little as the viewer needs: where it started, where it ended (where it stuck, or
 * where its flight took it by when it was gone), the time between, and a point ("via") wherever
 * the viewer's curve between those (gravity's for arrows, straight for skulls and fireballs) is
 * more than [VIA_TOL] blocks off where the recording saw it. The viewer's projPath puts it back.
 */
internal object ProjPack {
    private const val VIA_TOL = 0.5
    private const val MAX_VIAS = 4
    private const val FLIGHT_MAX = 600

    fun line(p: UploadPacker.Proj): JsonObject {
        val s = p.spawn
        val type = s["type"]?.asString ?: "minecraft:arrow"
        val arrow = !(s.has("i") || s.has("a"))
        val x0 = s["x"].asDouble; val y0 = s["y"].asDouble; val z0 = s["z"].asDouble
        // The flight as seen: the spawn, each new flight (arcs: dt, x, y, z, vx, vy, vz).
        val seen = ArrayList<DoubleArray>()
        seen += doubleArrayOf(0.0, x0, y0, z0)
        var last = doubleArrayOf(0.0, x0, y0, z0) + (s.getAsJsonArray("v")?.map { it.asDouble }?.toDoubleArray() ?: doubleArrayOf(0.0, 0.0, 0.0))
        for (a in p.arcs) {
            val arc = a.asJsonArray
            if (arc.size() < 7) continue
            val f = DoubleArray(7) { arc[it].asDouble }
            if (f[0] > seen.last()[0]) seen += f.copyOf(4)
            last = f
        }
        val stuck = p.stuck
        val end: DoubleArray = if (stuck != null && stuck.size() >= 4) doubleArrayOf(stuck[0].asDouble, stuck[1].asDouble, stuck[2].asDouble, stuck[3].asDouble)
        else fly(last, ((p.gone ?: (p.t0 + 100)) - p.t0).toDouble(), s["a"]?.asDouble, s["i"]?.asDouble)
        val path = ArrayList<DoubleArray>()
        path += seen[0]
        if (end[0] > 0) path += end
        // Vias where the curve is too far off what was seen.
        for (n in 0 until MAX_VIAS) {
            if (path.size < 2) break
            var worst: DoubleArray? = null; var wd = VIA_TOL
            for (q in seen) { if (q[0] <= 0 || q[0] >= path.last()[0]) continue; val d = off(path, q, arrow); if (d > wd) { wd = d; worst = q } }
            if (worst == null) break
            path += worst; path.sortBy { it[0] }
        }
        val o = JsonObject()
        o.addProperty("k", "pj"); o.add("t", s["t"]); o.add("id", s["id"])
        if (type != "minecraft:arrow") o.addProperty("type", type)
        o.add("x", MobPlan.tenth(x0)); o.add("y", MobPlan.tenth(y0)); o.add("z", MobPlan.tenth(z0))
        if (path.size > 1) o.add("p", JsonArray().apply {
            for (q in path.drop(1)) { add(q[0].toInt()); for (i in 1..3) add(Math.round((q[i] - path[0][i]) * 10).toInt()) }
        })
        if (!arrow) o.addProperty("pw", 1)
        if (stuck != null) o.addProperty("s", 1)
        p.gone?.let { o.addProperty("g", it - p.t0) }
        return o
    }

    /** Where the viewer's curve through [path] passes nearest to [q] (dt, x, y, z): how far. */
    private fun off(path: List<DoubleArray>, q: DoubleArray, arrow: Boolean): Double {
        var best = Double.MAX_VALUE
        for (k in 0 until path.size - 1) {
            val a = path[k]; val b = path[k + 1]
            for (i in 0..40) {
                val tt = a[0] + (b[0] - a[0]) * i / 40
                val p = at(a, b, tt, arrow)
                best = minOf(best, Math.hypot(Math.hypot(p[0] - q[1], p[1] - q[2]), p[2] - q[3]))
            }
        }
        return best
    }

    /** The viewer's point at [tt] between path points a and b (its projPath). */
    fun at(a: DoubleArray, b: DoubleArray, tt: Double, arrow: Boolean): DoubleArray {
        val span = (b[0] - a[0]).takeIf { it > 0 } ?: 1.0
        val f = (tt - a[0]) / span
        val g = if (arrow) 0.025 * (tt - a[0]) * (b[0] - tt) else 0.0
        return doubleArrayOf(a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f + g, a[3] + (b[3] - a[3]) * f)
    }

    /** A flight [f] (t, x, y, z, vx, vy, vz) flown on the game's physics till [until] ticks after the spawn (the viewer's old flight()). */
    private fun fly(f: DoubleArray, until: Double, accel: Double?, inertia: Double?): DoubleArray {
        var x = f[1]; var y = f[2]; var z = f[3]; var vx = f[4]; var vy = f[5]; var vz = f[6]
        val n = minOf(maxOf(0, (until - f[0]).toInt()), FLIGHT_MAX)
        for (k in 0 until n) {
            if (inertia != null) {
                val len = Math.sqrt(vx * vx + vy * vy + vz * vz)
                val a = accel ?: 0.0
                if (len > 1e-9) { vx += vx / len * a; vy += vy / len * a; vz += vz / len * a }
                vx *= inertia; vy *= inertia; vz *= inertia
            }
            x += vx; y += vy; z += vz
            if (inertia == null) { vx *= 0.99; vy = vy * 0.99 - 0.05; vz *= 0.99 }
        }
        return doubleArrayOf(f[0] + n, x, y, z)
    }
}
