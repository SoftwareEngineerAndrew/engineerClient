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
        lines(Files.newInputStream(file)).use { r ->
            for (line in r.lineSequence()) {
                val kind = kindOf(line) ?: continue
                val head = HEAD.find(line)
                val t = head?.groupValues?.get(2)?.toInt()
                if (t != null && t > ticks && kind !in WORLD) ticks = t
                if (kind in WORLD) world += line
                when (kind) {
                    "meta" -> JsonParser.parseString(line).asJsonObject.let { summary.add("self", it["self"]); summary.add("startMs", it["startMs"]) }
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
        return Scan(summary, layout?.replaceFirst(""""k":"rooms"""", """"k":"roomsAll""""), world, counts, projs)
    }

    /**
     * The run as a temp file to send - xz, or gzip when [xz] is off (the site can only check a
     * recording sent without the key if it is gzip) - and how many mobs were left out as already
     * uploaded.
     */
    fun pack(file: Path, scan: Scan, sibling: ByteArray?, xz: Boolean = true): Pair<Path, Int> {
        val drop = sibling?.let { s -> val theirs = counts(s.inputStream()); scan.counts.filter { (id, n) -> (theirs[id] ?: 0) >= n }.keys } ?: emptySet()
        val out = Files.createTempFile("betterpf-upload-", if (xz) ".jsonl.xz" else ".jsonl.gz")
        val stream = if (xz) XZOutputStream(Files.newOutputStream(out), LZMA2Options(3)) else java.util.zip.GZIPOutputStream(Files.newOutputStream(out), 1 shl 16)
        BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 1 shl 16).use { w ->
            val cols = Columns(w)
            fun put(line: String, timed: Boolean = true) {
                val kept = (if (drop.isEmpty()) line else keep(line, drop) ?: return).let { fold(it, scan) ?: return }
                // (the world's lines carry ticks, but aren't in the timeline: none of them start a stretch)
                if (timed) cols.put(kept) else { w.write(kept); w.newLine() }
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

    /** How many events each mob has in a recording (the viewer's measure of which saw it best), as saved or as uploaded. */
    private fun counts(input: InputStream): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        lines(input).use { r ->
            for (line in r.lineSequence()) {
                val kind = KIND.find(line.take(24))?.groupValues?.get(1) ?: continue
                when {
                    kind == "e" -> for (m in ROW_ID.findAll(line)) out.merge(m.groupValues[1].toInt(), 1, Int::plus)
                    kind == "cols" -> {
                        val l = JsonParser.parseString(line).asJsonObject
                        if (l["of"]?.asString == "e") for (t in l.getAsJsonArray("tr")) t.asJsonArray.let { out.merge(it[0].asInt, it[1].asJsonArray.size(), Int::plus) }
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
        return out
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
