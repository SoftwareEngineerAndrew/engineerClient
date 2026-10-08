package com.engineerclient.betterpf

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * What a saved run (.jsonl.gz, as written while recording) is turned into to upload:
 *
 *  - xz (LZMA2) instead of gzip: about half the size (2.95 MB -> 1.43 MB on a 7-minute run).
 *    The site stores it as it comes; the viewer tells the two apart by their first bytes.
 *
 *  - When someone else in the party already uploaded their recording of the same run ([sibling]),
 *    the mobs that recording has at least as much of are left out. The viewer takes each mob
 *    (entity id - the server's, the same on every client) from the one recording with the most
 *    data for it, never mixing two, so this changes nothing it shows: only mobs this recording
 *    saw more of are kept. Players are always kept (the viewer fills gaps in one recording's view
 *    of a player from another's).
 *
 *  - Everything the viewer builds the world from comes first (after the meta line), with the
 *    whole room layout as a "roomsAll" line: those lines are written as the run goes, but none of
 *    them are about when. The viewer starts a long run from its first minute while the rest
 *    downloads; this way that first minute already has every room and door, and the world never
 *    has to be built again once the rest is in. The rest keeps its order.
 *
 *  - A projectile's whole life - its spawn and flight, new flights, where it stuck, when it was
 *    gone - is one "proj" line where it spawned (most arrows are shot point-blank into a wall and
 *    stick a tick later: three lines and a move, for one arrow).
 *
 *  - Mob moves ("e") and player lines ("p") as columns ("cols"): each 10 s, every mob's (player's)
 *    values one after another, as differences from the last - mostly 0, 1 and -1, which pack to a
 *    fraction of the numbers written out. 38% smaller in all on a 7-minute F7. The viewer puts each
 *    line back where it was.
 *
 *  - Your camera's frames thinned: one is left out where the frames either side of it, as the
 *    viewer draws between them, are within [CAM_TOLERANCE] degrees of it (under a pixel), never
 *    leaving more than [CAM_GAP] ticks between two (more than 2 is "not rendering" to the viewer).
 *
 *  - Other players a party member's upload already has ([sibling]): their lines are left out
 *    while that recording has them, with a margin either side: the two recordings' ticks are
 *    lined up the way the viewer does it (the same block changes in both), [ALIGNED_MARGIN]; if
 *    they can't be, by start time, [COVER_MARGIN]. The viewer fills a player from any
 *    recording that has them at that tick, so nothing it shows changes. If that recording is the
 *    player's own, their equipment, skin and held head go too: the viewer takes those from it.
 *
 * Two reads of the file: [scan] (also the summary the site lists it by), then [pack]. Runs on an
 * upload thread, never the game's. The local file is left as it is.
 */
object UploadPacker {

    /** Line kinds that are about one mob, by its "id" (the "e" lines hold many, in "d"). */
    private val ENTITY_KINDS = setOf("spawn", "arc", "gone", "name", "stand", "frame")
    private val KIND = Regex(""""k":"(\w+)"""")
    private val HEAD = Regex("""^\{"k":"(\w+)","t":(\d+)""")
    private val ID = Regex(""""id":(-?\d+)""")
    private val ROW_ID = Regex("""\[(-?\d+),""")
    /** Line kinds the world is built from, untimed as far as the viewer goes (its WORLD_KINDS). */
    private val WORLD = setOf("lib", "dslots", "door", "vol", "chunk", "pal", "skull", "skin", "party", "floor")
    private fun kindOf(line: String) = KIND.find(line.take(24))?.groupValues?.get(1)

    private const val RUN_START = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
    private val RUN_END = Regex("""^\s*☠ Defeated """)

    /** Ticks of mob and player lines a "cols" line holds; a stretch bigger than [COLS_MAX] is split. */
    private const val COLS_TICKS = 200
    private const val COLS_MAX = 100_000 // (the site takes lines up to 128 KB)
    private const val CAM_TOLERANCE = 0.05
    private const val CAM_GAP = 1.95
    private const val COVER_MARGIN = 600
    private const val ALIGNED_MARGIN = 40

    /** A projectile, followed through the file for its "proj" line. */
    internal class Proj(val spawn: JsonObject) {
        val t0 = spawn["t"].asInt
        val arcs = JsonArray()
        var stuck: JsonArray? = null
        var gone: Int? = null
        /** Anything more than a flight, a stick and gone (moved after sticking, a name...): kept as lines. */
        var foldable = true
    }

    /** What one read of a run file gives: its summary, the layout, the world's lines, each mob's event count, its projectiles. */
    class Scan internal constructor(
        val summary: JsonObject,
        internal val layout: String?,
        internal val world: List<String>,
        internal val counts: Map<Int, Int>,
        private val projs: Map<Int, Proj>,
        internal val self: String,
        internal val startMs: Long,
        /** Per "cam" line (in order), which of its frames stay; null: all. */
        internal val camKeep: List<BooleanArray?>,
        /** Block changes as (tick, "x,y,z,state"), for lining this recording up with another. */
        internal val blocks: List<Pair<Long, String>>,
    ) {
        internal fun fold(id: Int) = projs[id]?.takeIf { it.foldable }
    }

    /** One pass over the run file: the summary the site lists it by (self, startMs, floor, party, ticks, cleared...) and what [pack] needs. */
    fun scan(file: Path, privateRun: Boolean): Scan {
        val summary = JsonObject()
        var ticks = 0
        var startTick: Int? = null
        var endTick: Int? = null
        var layout: String? = null
        val world = ArrayList<String>()
        val counts = HashMap<Int, Int>()
        val projs = HashMap<Int, Proj>()
        val flying = HashSet<Int>() // projectiles not gone yet
        var self = ""
        var startMs = 0L
        val frames = ArrayList<DoubleArray>() // time, yaw, pitch, cam line, frame
        var camLines = 0
        val blocks = BlockReader()
        lines(Files.newInputStream(file)).use { r ->
            for (line in r.lineSequence()) {
                val kind = kindOf(line) ?: continue
                val head = HEAD.find(line)
                val t = head?.groupValues?.get(2)?.toInt()
                if (t != null && t > ticks && kind !in WORLD) ticks = t
                if (kind in WORLD) world += line
                blocks.read(kind, line)
                when (kind) {
                    "meta" -> JsonParser.parseString(line).asJsonObject.let {
                        summary.add("self", it["self"]); summary.add("startMs", it["startMs"])
                        self = it["self"]?.asString ?: ""; startMs = it["startMs"]?.asLong ?: 0L
                    }
                    "cam" -> {
                        val d = JsonParser.parseString(line).asJsonObject.getAsJsonArray("d")
                        d.forEachIndexed { k, f -> val a = f.asJsonArray; frames += doubleArrayOf(t!! - 1 + a[0].asDouble, a[1].asDouble, a[2].asDouble, camLines.toDouble(), k.toDouble()) }
                        camLines++
                    }
                    "floor" -> summary.add("floor", JsonParser.parseString(line).asJsonObject["floor"])
                    "party" -> summary.add("party", JsonParser.parseString(line).asJsonObject["m"])
                    "rooms" -> if (line.length > (layout?.length ?: -1)) layout = line
                    "chat" -> {
                        val l = JsonParser.parseString(line).asJsonObject
                        val m = l["m"]?.asString ?: continue
                        if (startTick == null && m.startsWith(RUN_START)) startTick = t ?: 0
                        else if (RUN_END.containsMatchIn(m)) endTick = t ?: 0
                    }
                }
                // Each mob's events (for leaving out what a party member's upload has), and projectiles.
                if (kind == "e") {
                    var mine: JsonArray? = null
                    for (m in ROW_ID.findAll(line)) {
                        val id = m.groupValues[1].toInt()
                        counts.merge(id, 1, Int::plus)
                        if (id in flying) {
                            val rows = mine ?: JsonParser.parseString(line).asJsonObject.getAsJsonArray("d").also { mine = it }
                            val row = rows.first { it.asJsonArray[0].asInt == id }.asJsonArray
                            val p = projs.getValue(id)
                            if (p.stuck == null) p.stuck = JsonArray().apply { add(t!! - p.t0); for (i in 1 until row.size()) add(row[i]) } else p.foldable = false
                        }
                    }
                } else if (kind in ENTITY_KINDS || kind == "tag" || (kind == "eq" && line.contains("\"id\":"))) {
                    val id = ID.find(line)?.groupValues?.get(1)?.toInt() ?: continue
                    if (kind != "tag") counts.merge(id, 1, Int::plus)
                    when {
                        kind == "spawn" && line.contains("\"v\":[") -> { projs[id] = Proj(JsonParser.parseString(line).asJsonObject); flying += id }
                        id !in flying -> {}
                        kind == "arc" -> projs.getValue(id).let { p ->
                            val l = JsonParser.parseString(line).asJsonObject
                            val v = l.getAsJsonArray("v")
                            p.arcs.add(JsonArray().apply { add(t!! - p.t0); add(l["x"]); add(l["y"]); add(l["z"]); v.forEach(::add) })
                        }
                        kind == "gone" -> { projs.getValue(id).gone = t; flying -= id }
                        else -> projs.getValue(id).foldable = false
                    }
                }
            }
        }
        if (!summary.has("floor")) summary.addProperty("floor", "")
        if (!summary.has("party")) summary.add("party", JsonArray())
        summary.addProperty("ticks", ticks)
        // Whether it is a whole run (Mort's map to "☠ Defeated") and how long it took — worked out
        // here so the server doesn't have to unpack the recording, which a big run can't afford on
        // its CPU budget (Cloudflare error 1102). The same rule the server used.
        val start = startTick; val end = endTick
        val cleared = start != null && end != null && end > start
        summary.addProperty("cleared", if (cleared) 1 else 0)
        if (privateRun) summary.addProperty("private", 1)
        if (cleared) summary.addProperty("timeMs", (end!! - start!!) * 50L)
        return Scan(summary, layout?.replaceFirst(""""k":"rooms"""", """"k":"roomsAll""""), world, counts, projs, self, startMs, thinCamera(frames, camLines), blocks.changes())
    }

    /**
     * Which camera frames to keep (see the top): from each kept frame, the furthest one the viewer's
     * straight line to it stays within [CAM_TOLERANCE] of every frame between, at most [CAM_GAP]
     * ticks on. In time order, as the viewer sorts them.
     */
    private fun thinCamera(frames: MutableList<DoubleArray>, lines: Int): List<BooleanArray?> {
        val keep = arrayOfNulls<BooleanArray>(lines)
        if (frames.size < 3) return keep.toList()
        frames.sortBy { it[0] }
        val kept = BooleanArray(frames.size)
        kept[0] = true; kept[frames.size - 1] = true
        fun turn(a: Double, b: Double) = ((b - a) % 360 + 540) % 360 - 180
        var k = 0
        while (k < frames.size - 1) {
            var m = k + 1
            var c = k + 2
            while (c < frames.size && frames[c][0] - frames[k][0] <= CAM_GAP) {
                val fk = frames[k]; val fc = frames[c]
                var ok = true
                for (j in k + 1 until c) {
                    val a = (frames[j][0] - fk[0]) / (fc[0] - fk[0])
                    val yaw = fk[1] + turn(fk[1], fc[1]) * a; val pitch = fk[2] + (fc[2] - fk[2]) * a
                    if (Math.abs(turn(yaw, frames[j][1])) > CAM_TOLERANCE || Math.abs(pitch - frames[j][2]) > CAM_TOLERANCE) { ok = false; break }
                }
                if (!ok) break
                m = c; c++
            }
            kept[m] = true; k = m
        }
        val counts = IntArray(lines)
        for (f in frames) counts[f[3].toInt()] = maxOf(counts[f[3].toInt()], f[4].toInt() + 1)
        frames.forEachIndexed { i, f ->
            val li = f[3].toInt()
            val arr = keep[li] ?: BooleanArray(counts[li]).also { keep[li] = it }
            arr[f[4].toInt()] = kept[i]
        }
        return keep.toList()
    }

    /** A party member's upload, as far as leaving things out goes: whose it is, each mob's event count, when it had each player, its block changes. */
    private class Sibling(val self: String, val startMs: Long, val counts: Map<Int, Int>, val players: Map<String, List<LongRange>>, val blocks: List<Pair<Long, String>>)

    /** A recording's block changes with their palette's states, as (tick, "x,y,z,state"). */
    private class BlockReader {
        private val pal = HashMap<Int, String>()
        private val raw = ArrayList<Triple<Long, String, Int>>()
        fun read(kind: String, line: String) {
            if (kind != "pal" && kind != "block") return
            val l = JsonParser.parseString(line).asJsonObject
            if (kind == "pal") pal[l["i"].asInt] = l["s"].asString
            else raw += Triple(l["t"].asLong, "${l["x"].asInt},${l["y"].asInt},${l["z"].asInt}", l["s"].asInt)
        }
        fun changes() = raw.map { (t, at, s) -> t to at + "," + (pal[s] ?: "?") }
    }

    /**
     * How many ticks to add to the sibling's to get this recording's, and how sure: the viewer's
     * way (align) - the middle of the differences between the same block change in both, near
     * what the start times say - or failing that the start times.
     */
    private fun lineUp(scan: Scan, sib: Sibling): Pair<Long, Int> {
        val guess = (sib.startMs - scan.startMs) / 50
        val theirs = HashMap<String, MutableList<Long>>()
        for ((t, k) in sib.blocks) theirs.getOrPut(k) { ArrayList() } += t
        val diffs = ArrayList<Long>()
        for ((t, k) in scan.blocks) {
            for (ts in theirs[k] ?: continue) { val d = t - ts; if (Math.abs(d - guess) <= 400) diffs += d }
            if (diffs.size > 2000) break
        }
        if (diffs.size < 3) return guess to COVER_MARGIN
        diffs.sort()
        return diffs[diffs.size / 2] to ALIGNED_MARGIN
    }

    /**
     * The run as a temp xz file to send, and how many mobs were left out as already uploaded.
     * Preset 6: 17% smaller than 3 for about three times the CPU (a few seconds, on the upload's
     * low-priority thread, after the run) and about 100 MB while it packs.
     */
    fun pack(file: Path, scan: Scan, sibling: ByteArray?): Pair<Path, Int> {
        val sib = sibling?.let { readSibling(it.inputStream()) }
        val drop = sib?.let { s -> scan.counts.filter { (id, n) -> (s.counts[id] ?: 0) >= n }.keys } ?: emptySet()
        val players = sib?.let { Players(scan, it) }
        var camLine = 0
        val out = Files.createTempFile("betterpf-upload-", ".jsonl.xz")
        val stream = XZOutputStream(Files.newOutputStream(out), LZMA2Options(6))
        BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 1 shl 16).use { w ->
            val cols = Columns(w)
            fun put(line: String, timed: Boolean = true) {
                var kept = (if (drop.isEmpty()) line else keep(line, drop) ?: return).let { fold(it, scan) ?: return }
                if (kept.startsWith("{\"k\":\"cam\"")) kept = thin(kept, scan.camKeep.getOrNull(camLine++)) ?: return
                // (the world's lines carry ticks, but aren't in the timeline: none of them start a stretch)
                if (!timed) { if (players?.keepsWorld(kept) != false) { w.write(kept); w.newLine() }; return }
                if (players == null) cols.put(kept) else players.put(kept, cols::put)
            }
            // The first line (meta), the layout and the world's lines; then everything else.
            lines(Files.newInputStream(file)).use { r ->
                var first = true
                for (line in r.lineSequence()) {
                    if (first) {
                        first = false; put(line, timed = false); scan.layout?.let { w.write(it); w.newLine() }
                        for (l in scan.world) put(l, timed = false)
                        continue
                    }
                    if (kindOf(line) !in WORLD) put(line)
                }
            }
            cols.end()
        }
        return out to drop.size
    }

    /** A line with the dropped mobs taken out, or null if nothing of it is left. */
    private fun keep(line: String, drop: Set<Int>): String? {
        val kind = kindOf(line) ?: return line
        if (kind == "e") {
            val l = JsonParser.parseString(line).asJsonObject
            val d = l.getAsJsonArray("d")
            val left = JsonArray()
            for (e in d) if (e.asJsonArray[0].asInt !in drop) left.add(e)
            if (left.size() == 0) return null
            if (left.size() == d.size()) return line
            l.add("d", left)
            return l.toString()
        }
        if (kind in ENTITY_KINDS || (kind == "eq" && line.contains("\"id\":"))) {
            val id = ID.find(line)?.groupValues?.get(1)?.toInt() ?: return line
            return if (id in drop) null else line
        }
        return line
    }

    /** A line as it goes up: a projectile's spawn becomes its "proj" line, the rest of its lines go (null). */
    private fun fold(line: String, scan: Scan): String? {
        val kind = kindOf(line) ?: return line
        when (kind) {
            "spawn", "arc", "gone" -> {
                val id = ID.find(line)?.groupValues?.get(1)?.toInt() ?: return line
                val p = scan.fold(id) ?: return line
                if (kind != "spawn") return null
                val s = p.spawn
                val o = JsonObject()
                o.addProperty("k", "proj"); o.add("t", s["t"]); o.add("id", s["id"])
                s["type"]?.takeIf { it.asString != "minecraft:arrow" }?.let { o.add("type", it) }
                for (k in listOf("x", "y", "z", "v", "a", "i")) s[k]?.let { o.add(k, it) }
                if (p.arcs.size() > 0) o.add("arcs", p.arcs)
                p.stuck?.let { o.add("s", it) }
                p.gone?.let { o.addProperty("g", it - p.t0) }
                return o.toString()
            }
            "e" -> {
                if (ROW_ID.findAll(line).none { scan.fold(it.groupValues[1].toInt()) != null }) return line
                val l = JsonParser.parseString(line).asJsonObject
                val left = JsonArray()
                for (e in l.getAsJsonArray("d")) if (scan.fold(e.asJsonArray[0].asInt) == null) left.add(e)
                if (left.size() == 0) return null
                l.add("d", left)
                return l.toString()
            }
        }
        return line
    }

    /** Mob ("e") and player ("p") lines into "cols" lines, a stretch of [COLS_TICKS] at a time, each ahead of the rest of its stretch. */
    private class Columns(private val w: BufferedWriter) {
        private class Item(val t: Int, val rows: JsonArray, val idx: Int, val ord: Int)
        private var end = -1
        private val others = ArrayList<String>()
        private val items = mapOf("e" to ArrayList<Item>(), "p" to ArrayList<Item>())
        private var ord = 0

        fun put(line: String) {
            val head = HEAD.find(line)
            val t = head?.groupValues?.get(2)?.toInt()
            if (t != null && t >= end) { flush(); end = (t / COLS_TICKS + 1) * COLS_TICKS }
            if (end < 0) { w.write(line); w.newLine(); return }
            val list = items[head?.groupValues?.get(1)]
            if (list != null && t != null) list += Item(t, JsonParser.parseString(line).asJsonObject.getAsJsonArray("d"), others.size, ord++)
            else others += line
        }

        fun end() = flush()

        private fun flush() {
            for ((kind, list) in items) if (list.isNotEmpty()) { write(kind, list); list.clear() }
            for (l in others) { w.write(l); w.newLine() }
            others.clear(); ord = 0
        }

        private fun write(kind: String, list: List<Item>) {
            val line = encode(kind, list)
            if (line.length > COLS_MAX && list.size > 1) { write(kind, list.subList(0, list.size / 2)); write(kind, list.subList(list.size / 2, list.size)); return }
            w.write(line); w.newLine()
        }

        /** One "cols" line: the lines' ticks, places and order, then per mob/player the lines it's in, its rows' lengths and its columns. */
        private fun encode(kind: String, list: List<Item>): String {
            val tracks = LinkedHashMap<String, MutableList<Pair<Int, JsonArray>>>()
            val keys = HashMap<String, JsonElement>()
            list.forEachIndexed { li, it ->
                for (r in it.rows) {
                    val row = r.asJsonArray
                    val key = row[0].toString()
                    keys.getOrPut(key) { row[0] }
                    tracks.getOrPut(key) { ArrayList() } += li to row
                }
            }
            val tr = JsonArray()
            for ((key, rows) in tracks) {
                val width = rows.maxOf { it.second.size() }
                val cols = JsonArray()
                for (c in 1 until width) cols.add(column(rows.filter { it.second.size() > c }.map { it.second[c] }))
                tr.add(JsonArray().apply {
                    add(keys[key]); add(deltas(rows.map { it.first.toLong() })); add(column(rows.map { JsonPrimitive(it.second.size()) })); add(cols)
                })
            }
            return JsonObject().apply {
                addProperty("k", "cols"); addProperty("of", kind)
                add("t", deltas(list.map { it.t.toLong() })); add("i", deltas(list.map { it.idx.toLong() })); add("o", deltas(list.map { it.ord.toLong() }))
                add("tr", tr)
            }.toString()
        }

        private fun deltas(v: List<Long>) = JsonArray().apply { var prev = 0L; for (x in v) { add(x - prev); prev = x } }

        /**
         * A column: all numbers (at most 4 decimals) as [decimals, first, differences...] in units
         * of the last decimal, exactly; anything else as runs, {"r": [value, count, ...]}.
         */
        private fun column(values: List<JsonElement>): JsonElement {
            val nums = values.map { v -> (v as? JsonPrimitive)?.takeIf { it.isNumber }?.let { runCatching { BigDecimal(it.asString) }.getOrNull() } }
            if (nums.all { it != null }) {
                val dec = nums.maxOf { it!!.stripTrailingZeros().scale().coerceAtLeast(0) }
                if (dec <= 4) return JsonArray().apply {
                    add(dec)
                    var prev = 0L
                    for (n in nums) { val q = n!!.movePointRight(dec).longValueExact(); add(q - prev); prev = q }
                }
            }
            val r = JsonArray()
            var last: JsonElement? = null; var n = 0
            for (v in values) {
                if (n > 0 && v == last) { n++; continue }
                if (n > 0) { r.add(last); r.add(n) }
                last = v; n = 1
            }
            if (n > 0) { r.add(last); r.add(n) }
            return JsonObject().apply { add("r", r) }
        }
    }

    /** A "cam" line with only the frames [keep] keeps, or null when none are left. */
    private fun thin(line: String, keep: BooleanArray?): String? {
        if (keep == null || keep.all { it }) return line
        if (keep.none { it }) return null
        val l = JsonParser.parseString(line).asJsonObject
        val d = l.getAsJsonArray("d")
        val left = JsonArray()
        d.forEachIndexed { k, f -> if (keep.getOrElse(k) { true }) left.add(f) }
        l.add("d", left)
        return l.toString()
    }

    /**
     * Other players' lines left out while [sib] has them (see the top). Their rows go from "p"
     * lines; when that starts, a "pgone" so this recording's view of them doesn't hold still on the
     * last row it had; when it ends, the row they're at then, as the recording would have had it.
     */
    private class Players(private val scan: Scan, private val sib: Sibling) {
        private val aligned = lineUp(scan, sib)
        /** Ticks (this recording's) the sibling surely has each player. */
        private val covered = sib.players.mapValues { (_, list) ->
            val (offset, margin) = aligned
            list.mapNotNull { r -> val a = r.first + offset + margin; val b = r.last + offset - margin; if (a <= b) a..b else null }
        }
        private val dropping = HashMap<String, Long>() // name -> the end of the stretch it's left out in
        private val last = HashMap<String, JsonElement?>() // the row each one left out is at

        private fun coverEnd(name: String, t: Long): Long? =
            if (name == scan.self) null else covered[name]?.firstOrNull { t in it }?.last

        /** Lines about a player the sibling is: their skin, held head and equipment come from their own recording. */
        private fun theirs(line: String): Boolean {
            val kind = kindOf(line) ?: return false
            if (kind != "eq" && kind != "skin" && kind != "held") return false
            if (sib.self.isEmpty() || sib.self == scan.self) return false
            return line.contains("\"name\":" + JsonPrimitive(sib.self).toString() + ",")
        }

        fun keepsWorld(line: String) = !theirs(line)

        fun put(line: String, out: (String) -> Unit) {
            val head = HEAD.find(line) ?: return out(line)
            val kind = head.groupValues[1]
            val t = head.groupValues[2].toLong()
            // Stretches over: back to how this recording has them.
            if (dropping.isNotEmpty()) {
                val ended = dropping.filter { (_, end) -> t > end }.keys
                if (ended.isNotEmpty()) {
                    val rows = JsonArray()
                    for (name in ended) { dropping.remove(name); last.remove(name)?.let { rows.add(it) } }
                    if (rows.size() > 0) out(JsonObject().apply { addProperty("k", "p"); addProperty("t", t); add("d", rows) }.toString())
                }
            }
            if (theirs(line)) return
            when (kind) {
                "p" -> {
                    val l = JsonParser.parseString(line).asJsonObject
                    val left = JsonArray()
                    val started = ArrayList<String>()
                    for (r in l.getAsJsonArray("d")) {
                        val name = r.asJsonArray[0].asString
                        val end = dropping[name] ?: coverEnd(name, t)?.also { dropping[name] = it; started += name }
                        if (end == null) left.add(r) else last[name] = r
                    }
                    if (left.size() == l.getAsJsonArray("d").size()) out(line)
                    else if (left.size() > 0) { l.add("d", left); out(l.toString()) }
                    for (name in started) out(JsonObject().apply { addProperty("k", "pgone"); addProperty("t", t); addProperty("name", name) }.toString())
                }
                "pgone" -> {
                    val name = JsonParser.parseString(line).asJsonObject["name"]?.asString
                    if (name != null && name in dropping) last[name] = null else out(line)
                }
                else -> out(line)
            }
        }
    }

    /** What leaving things out needs from a party member's upload (or a saved run). */
    private fun readSibling(input: InputStream): Sibling {
        val out = HashMap<Int, Int>()
        var self = ""
        var startMs = 0L
        var maxT = 0L
        val seen = HashMap<String, MutableList<Long>>() // name -> ticks it had a row at, and pgone ticks as -(t + 1)
        val blocks = BlockReader()
        fun row(name: String, t: Long) { seen.getOrPut(name) { ArrayList() } += t }
        lines(input).use { r ->
            for (line in r.lineSequence()) {
                val kind = KIND.find(line.take(24))?.groupValues?.get(1) ?: continue
                HEAD.find(line)?.groupValues?.get(2)?.toLong()?.let { if (kind !in WORLD && it > maxT) maxT = it }
                blocks.read(kind, line)
                when (kind) {
                    "meta" -> JsonParser.parseString(line).asJsonObject.let { self = it["self"]?.asString ?: ""; startMs = it["startMs"]?.asLong ?: 0L }
                    "p" -> { val l = JsonParser.parseString(line).asJsonObject; val t = l["t"].asLong; for (x in l.getAsJsonArray("d")) row(x.asJsonArray[0].asString, t) }
                    "pgone" -> { val l = JsonParser.parseString(line).asJsonObject; row(l["name"].asString, -(l["t"].asLong + 1)) }
                }
                when {
                    kind == "e" -> for (m in ROW_ID.findAll(line)) out.merge(m.groupValues[1].toInt(), 1, Int::plus)
                    kind == "cols" -> {
                        val l = JsonParser.parseString(line).asJsonObject
                        if (l["of"]?.asString == "e") for (t in l.getAsJsonArray("tr")) t.asJsonArray.let { out.merge(it[0].asInt, it[1].asJsonArray.size(), Int::plus) }
                        if (l["of"]?.asString == "p") {
                            var acc = 0L
                            val ticks = l.getAsJsonArray("t").map { acc += it.asLong; acc }
                            for (tr in l.getAsJsonArray("tr")) {
                                val a = tr.asJsonArray
                                var li = 0
                                for (d in a[1].asJsonArray) { li += d.asInt; row(a[0].asString, ticks[li]) }
                            }
                        }
                    }
                    kind == "proj" -> {
                        val l = JsonParser.parseString(line).asJsonObject
                        val n = 1 + (l["arcs"]?.asJsonArray?.size() ?: 0) + (if (l.has("s")) 1 else 0) + (if (l.has("g")) 1 else 0)
                        out.merge(l["id"].asInt, n, Int::plus)
                    }
                    kind in ENTITY_KINDS || (kind == "eq" && line.contains("\"id\":")) ->
                        ID.find(line)?.groupValues?.get(1)?.toInt()?.let { out.merge(it, 1, Int::plus) }
                }
            }
        }
        // When it had each player: from a row until their "pgone" (rows only come when something changed).
        val players = seen.mapValues { (_, events) ->
            val ranges = ArrayList<LongRange>()
            var from: Long? = null
            for (e in events.sortedBy { if (it < 0) -it - 1 else it }) {
                if (e >= 0) { if (from == null) from = e }
                else { val t = -e - 1; from?.let { ranges += it until t }; from = null }
            }
            from?.let { ranges += it..maxT }
            ranges
        }
        return Sibling(self, startMs, out, players, blocks.changes())
    }

    /** A recording's lines, gzip or xz (told apart by their first bytes, as the site and viewer do). */
    private fun lines(raw: InputStream): BufferedReader {
        val input = BufferedInputStream(raw, 1 shl 16)
        input.mark(6)
        val head = ByteArray(6).also { input.read(it) }
        input.reset()
        val xz = head[0] == 0xFD.toByte() && head[1] == '7'.code.toByte() && head[2] == 'z'.code.toByte()
        return BufferedReader(InputStreamReader(if (xz) XZInputStream(input) else GZIPInputStream(input), Charsets.UTF_8), 1 shl 16)
    }
}
