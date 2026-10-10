package com.engineerclient.betterpf

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import org.slf4j.LoggerFactory

/**
 * What a saved run (.jsonl.gz, as written while recording) is turned into to upload:
 *
 *  - xz (LZMA2) instead of gzip: about half the size on a typical run.
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
 *    fraction of the numbers written out (over a third smaller overall on an F7 run). The viewer puts each
 *    line back where it was.
 *
 *  - The recording player's camera frames thinned: one is left out where the frames either side of it, as the
 *    viewer draws between them, are within [CAM_TOLERANCE] degrees of it (under a pixel), never
 *    leaving more than [CAM_GAP] ticks between two (more than 2 is "not rendering" to the viewer).
 *
 *  - Other players a party member's upload already has ([sibling]): their lines are left out
 *    while that recording has them, with a margin either side: the two recordings' ticks are
 *    lined up the way the viewer does it (the same block changes in both), [ALIGNED_MARGIN]; if
 *    they can't be, by start time, [COVER_MARGIN]. The viewer fills a player from any
 *    recording that has them at that tick, so nothing it shows changes. If that recording is the
 *    player's own, their equipment, skin and held head go too: the viewer takes those from it.
 *    Only when the two line up by block changes: by start time alone, nothing is left out.
 *
 *  - Private chat (see [privateChat]) never goes up, whatever the recording has in it: runs
 *    recorded with Hide Private Chats off, or from before it, are sent through Upload Missing Runs.
 *
 * A party member's upload is anyone's say: read with limits on everything, and if anything about
 * it is off - or packing with it fails - the run goes up whole, as if there were none.
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

    /** Ticks a stretch of columns holds (see [Stretch]); a line bigger than [COLS_MAX] is split. */
    private const val COLS_TICKS = 600
    private const val COLS_MAX = 100_000 // (the site takes lines up to 128 KB)
    private const val NL = "\n" // never newLine(): on Windows that is "\r\n"
    private const val CAM_TOLERANCE = 0.05
    private const val CAM_GAP = 1.95
    private const val COVER_MARGIN = 600
    private const val ALIGNED_MARGIN = 40

    // Limits on a party member's upload (a real one is a few MB, 100-odd thousand lines, 50-odd
    // thousand block changes, a few hours of ticks at the very most). Past one: no sibling.
    private const val XZ_MEMORY_KIB = 65536 // the decoder's memory: preset 6 needs about 9 MB
    private const val SIB_MAX_CHARS = 300_000_000L
    private const val SIB_MAX_LINES = 3_000_000
    private const val SIB_MAX_LINE = 1 shl 20 // (the site takes lines up to 128 KB)
    private const val SIB_MAX_IDS = 500_000
    private const val SIB_MAX_ROWS = 5_000_000
    private const val SIB_MAX_TICK = 1_000_000L
    private const val MAX_BLOCKS = 1_000_000
    private const val MAX_PAL = 65_536
    /** Block pairs [lineUp] compares at most (the same block changing over and over is many). */
    private const val MAX_COMPARES = 20_000_000

    private val log = LoggerFactory.getLogger("engineerclient")

    /** Something in a recording that isn't what it should be. */
    private class Malformed(what: String) : IOException(what)

    private fun obj(line: String): JsonObject = JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject ?: throw Malformed("not an object")
    private fun arr(e: JsonElement?): JsonArray = (e as? JsonArray) ?: throw Malformed("not an array")
    private fun at(a: JsonArray, i: Int): JsonElement = if (i in 0 until a.size()) a[i] else throw Malformed("index $i of ${a.size()}")
    private fun str(e: JsonElement?): String = (e as? JsonPrimitive)?.takeIf { it.isString }?.asString ?: throw Malformed("not a string")
    /** A whole number, written as one (1.5, 1e3 and anything past a long aren't). */
    private fun long(e: JsonElement?): Long = (e as? JsonPrimitive)?.takeIf { it.isNumber }?.asString?.toLongOrNull() ?: throw Malformed("not a whole number")
    private fun int(e: JsonElement?): Int = long(e).let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else throw Malformed("$it isn't an int") }

    /**
     * Chat that is nobody else's business: private messages both ways, guild, officer and co-op
     * chat and notices, friends coming online, friend requests, and what /g online, /g info and
     * /f list show. Hypixel writes them "From [RANK] name: ...", "To name: ...", "Guild > ...",
     * "Officer > ...", "Co-op > ...", "Friend > ...", or as boxes of several lines - any line of a
     * message being one of these leaves the whole message out. Party chat is only left out with Hide
     * Private Chats on ([partyChat]): the viewer uses it.
     * Used both while recording (Hide Private Chats) and on upload (always).
     */
    private val PRIVATE_LINE = Regex("""^(?:(?:From|To) (?:\[[^\]]+] )?\w{1,16}: |(?:Guild|Officer|Co-op|Friend) > |Friend request from |You are now friends with |-*\s*Friends \(Page |Guild Name: |Total Members: |Online Members: |Offline Members: |-- .+ --$)""")
    private val PRIVATE_ANYWHERE = Regex("""(?i)\bco-op\b|\b(?:joined|left) the guild\b|\bto join (?:their|the|your) guild\b|\bremoved you from your friends\b""")
    fun privateChat(text: String): Boolean = text.lineSequence().any { raw ->
        val l = raw.trim()
        PRIVATE_LINE.containsMatchIn(l) || (!l.startsWith("Party > ") && PRIVATE_ANYWHERE.containsMatchIn(l))
    }

    /** Party chat ("Party > [RANK] name: ..."): left out too with Hide Private Chats on. */
    fun partyChat(text: String): Boolean = text.lineSequence().any { it.trim().startsWith("Party > ") }

    /** A "chat" line whose message is private (see [privateChat]), or party chat with [party]; one that can't be read is left out too. */
    private fun privateChatLine(line: String, party: Boolean): Boolean =
        runCatching { (JsonParser.parseString(line).asJsonObject["m"] as? JsonPrimitive)?.asString?.let { privateChat(it) || (party && partyChat(it)) } ?: false }.getOrDefault(true)

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
        // its CPU budget (Cloudflare error 1102).
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
            val l = obj(line)
            // (past the limits the rest is left out: lining up needs only some of them)
            if (kind == "pal") { val i = int(l["i"]); if (pal.size < MAX_PAL || i in pal) pal[i] = str(l["s"]) }
            else if (raw.size < MAX_BLOCKS) raw += Triple(long(l["t"]), "${int(l["x"])},${int(l["y"])},${int(l["z"])}", int(l["s"]))
        }
        fun changes() = raw.map { (t, at, s) -> t to at + "," + (pal[s] ?: "?") }
    }

    /**
     * How many ticks to add to the sibling's to get this recording's, and how sure: the viewer's
     * way (align) - the middle of the differences between the same block change in both, near
     * what the start times say - or failing that the start times.
     */
    private fun lineUp(scan: Scan, sib: Sibling): Pair<Long, Int> {
        // (a recording of the same run starts within minutes of this one)
        if (Math.abs(sib.startMs - scan.startMs) > 3_600_000L) return 0L to COVER_MARGIN
        val guess = (sib.startMs - scan.startMs) / 50
        val theirs = HashMap<String, MutableList<Long>>()
        for ((t, k) in sib.blocks) theirs.getOrPut(k) { ArrayList() } += t
        val diffs = ArrayList<Long>()
        var compares = 0
        // (one block changing thousands of times in both would be millions of pairs: stops at enough)
        pairs@ for ((t, k) in scan.blocks) {
            for (ts in theirs[k] ?: continue) {
                val d = t - ts; if (Math.abs(d - guess) <= 400) diffs += d
                if (diffs.size > 2000 || ++compares > MAX_COMPARES) break@pairs
            }
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
    fun pack(file: Path, scan: Scan, sibling: ByteArray?, hideParty: Boolean = false): Pair<Path, Int> {
        // Whatever is wrong with a party member's upload, this one goes up - whole, if need be.
        val sib = sibling?.let { b ->
            runCatching { readSibling(b.inputStream()) }.onFailure { log.warn("[ec] betterpf: a party member's upload couldn't be read, nothing left out: {}", it.toString()) }.getOrNull()
        }
        if (sib != null) {
            try {
                return write(file, scan, sib, hideParty)
            } catch (e: Exception) {
                log.warn("[ec] betterpf: packing with a party member's upload failed, packing it whole", e)
            }
        }
        return write(file, scan, null, hideParty)
    }

    private fun write(file: Path, scan: Scan, sibling: Sibling?, hideParty: Boolean): Pair<Path, Int> {
        // Nothing is left out unless the two recordings line up by their block changes: by start
        // time alone (or a recording that only claims to be of this run) is too unsure to trust.
        val aligned = sibling?.let { lineUp(scan, it) }
        val sib = sibling?.takeIf { aligned?.second == ALIGNED_MARGIN }
        if (sibling != null && sib == null) log.info("[ec] betterpf: a party member's upload doesn't line up with this one, nothing left out")
        val drop = sib?.let { s -> scan.counts.filter { (id, n) -> (s.counts[id] ?: 0) >= n }.keys } ?: emptySet()
        val players = sib?.let { Players(scan, it, aligned!!) }
        val out = Files.createTempFile("betterpf-upload-", ".jsonl.xz")
        try {
            writeTo(file, scan, out, drop, players, hideParty)
        } catch (t: Throwable) {
            Files.deleteIfExists(out)
            throw t
        }
        return out to drop.size
    }

    private fun writeTo(file: Path, scan: Scan, out: Path, drop: Set<Int>, players: Players?, hideParty: Boolean) {
        var camLine = 0
        val stream = XZOutputStream(Files.newOutputStream(out), LZMA2Options(6))
        BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 1 shl 16).use { w ->
            val cols = Stretch(w)
            fun put(line: String, timed: Boolean = true) {
                var kept = (if (drop.isEmpty()) line else keep(line, drop) ?: return).let { fold(it, scan) ?: return }
                if (kept.startsWith("{\"k\":\"cam\"")) kept = thin(kept, scan.camKeep.getOrNull(camLine++)) ?: return
                // (the world's lines carry ticks, but aren't in the timeline: none of them start a stretch)
                if (!timed) { if (players?.keepsWorld(kept) != false) { w.write(kept); w.write(NL) }; return }
                if (players == null) cols.put(kept) else players.put(kept, cols::put)
            }
            // The first line (meta), the layout and the world's lines; then everything else.
            lines(Files.newInputStream(file)).use { r ->
                var first = true
                for (line in r.lineSequence()) {
                    if (first) {
                        first = false; put(line, timed = false); scan.layout?.let { w.write(it); w.write(NL) }
                        for (l in scan.world) put(l, timed = false)
                        continue
                    }
                    // A line cut short (a crash part way through writing it) would get the whole run refused.
                    if (!line.startsWith("{") || !line.endsWith("}")) continue
                    val kind = kindOf(line)
                    if (kind == "chat" && privateChatLine(line, hideParty)) continue
                    if (kind !in WORLD) put(line)
                }
            }
            cols.end()
        }
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

    /**
     * Everything timed, a stretch of [COLS_TICKS] at a time, as fewer and denser lines, each stretch's
     * ahead of the rest of it (the viewer turns them back into the lines they were):
     *  - mob ("e") and player ("p") lines as "cols": per mob/player its values one after another
     *    as differences (mob ids too, in order);
     *  - mob events (spawn, tag, stand, frame, eq, name, gone) and the server tick, map and swing
     *    lines as "grp": per kind, a column per field the same way;
     *  - block changes as "blocks": each tick's sorted by place, every one a difference from the one
     *    before;
     *  - camera frames as "cams": columns of hundredths, the turns as differences.
     * The order they come in keeps each tick's events in the order the viewer needs them (a mob's
     * spawn before its moves, its tag before them, gone after).
     */
    private class Stretch(private val w: BufferedWriter) {
        private class Item(val t: Int, val rows: JsonArray)
        private var end = -1
        private val others = ArrayList<String>()
        private val items = mapOf("e" to ArrayList<Item>(), "p" to ArrayList<Item>())
        private val groups = LinkedHashMap<String, ArrayList<JsonObject>>()
        private val blocks = ArrayList<IntArray>() // t, x, y, z, s
        private val cams = ArrayList<Pair<Int, JsonArray>>()

        fun put(line: String) {
            val head = HEAD.find(line)
            val t = head?.groupValues?.get(2)?.toInt()
            if (t != null && t >= end) { flush(); end = (t / COLS_TICKS + 1) * COLS_TICKS }
            if (end < 0) { w.write(line); w.write(NL); return }
            val kind = head?.groupValues?.get(1)
            if (t == null || kind == null || !take(kind, t, line)) others += line
        }

        /** Whether the line went into this stretch's columns (one that doesn't fit their shape stays a line). */
        private fun take(kind: String, t: Int, line: String): Boolean = runCatching {
            when {
                kind in items -> { items.getValue(kind) += Item(t, JsonParser.parseString(line).asJsonObject.getAsJsonArray("d")); true }
                kind == "block" -> {
                    val l = JsonParser.parseString(line).asJsonObject
                    if (l.keySet() != BLOCK_KEYS) return false
                    blocks += intArrayOf(t, int(l["x"]), int(l["y"]), int(l["z"]), int(l["s"])); true
                }
                kind == "cam" -> {
                    val l = JsonParser.parseString(line).asJsonObject
                    val d = l.getAsJsonArray("d")
                    if (l.keySet() != CAM_KEYS || d.any { f -> f !is JsonArray || f.size() != 3 || f.any { hundredths(it) == null } }) return false
                    cams += t to d; true
                }
                kind in GROUPED || (kind == "eq" && line.contains("\"id\":")) -> { groups.getOrPut(kind) { ArrayList() } += JsonParser.parseString(line).asJsonObject; true }
                else -> false
            }
        }.getOrDefault(false)

        fun end() = flush()

        private fun flush() {
            for (kind in BEFORE_MOVES) groups[kind]?.let { write(it) { part -> group(kind, part) } }
            for ((kind, list) in items) if (list.isNotEmpty()) write(list) { encode(kind, it) }
            for ((kind, list) in groups) if (kind !in BEFORE_MOVES) write(list) { part -> group(kind, part) }
            if (blocks.isNotEmpty()) write(blocks) { blockLine(it) }
            if (cams.isNotEmpty()) write(cams) { camLine(it) }
            for (l in others) { w.write(l); w.write(NL) }
            for (list in items.values) list.clear()
            groups.clear(); blocks.clear(); cams.clear(); others.clear()
        }

        /** One line of [list], or two of its halves (and so on) while it's longer than [COLS_MAX]. */
        private fun <T> write(list: List<T>, encode: (List<T>) -> String) {
            val line = encode(list)
            if (line.length > COLS_MAX && list.size > 1) { write(list.subList(0, list.size / 2), encode); write(list.subList(list.size / 2, list.size), encode); return }
            w.write(line); w.write(NL)
        }

        /** One "cols" line: the lines' ticks, then per mob/player (mobs by id, as differences) the lines it's in, its rows' lengths and its columns. */
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
            // Mobs in id order, each id the difference from the one before ("kd").
            val byId = kind == "e" && keys.values.all { (it as? JsonPrimitive)?.isNumber == true && it.asString.toLongOrNull() != null }
            val order = if (byId) tracks.keys.sortedBy { keys.getValue(it).asLong } else tracks.keys.toList()
            val tr = JsonArray()
            var prevKey = 0L
            for (key in order) {
                val rows = tracks.getValue(key)
                val width = rows.maxOf { it.second.size() }
                val cols = JsonArray()
                for (c in 1 until width) cols.add(column(rows.filter { it.second.size() > c }.map { it.second[c] }))
                val k: JsonElement = if (byId) keys.getValue(key).asLong.let { id -> JsonPrimitive(id - prevKey).also { prevKey = id } } else keys.getValue(key)
                tr.add(JsonArray().apply {
                    add(k); add(deltas(rows.map { it.first.toLong() })); add(column(rows.map { JsonPrimitive(it.second.size()) })); add(cols)
                })
            }
            return JsonObject().apply {
                addProperty("k", "cols"); addProperty("of", kind)
                if (byId) addProperty("kd", 1)
                add("t", deltas(list.map { it.t.toLong() }))
                add("tr", tr)
            }.toString()
        }

        /** A "grp" line: one kind's lines, a column per field ("n" of them; null where a line hasn't the field). */
        private fun group(kind: String, list: List<JsonObject>): String {
            val fields = LinkedHashSet<String>()
            for (o in list) for (f in o.keySet()) if (f != "k") fields += f
            val c = JsonObject()
            for (f in fields) c.add(f, column(list.map { it[f] ?: JsonNull.INSTANCE }))
            return JsonObject().apply { addProperty("k", "grp"); addProperty("of", kind); addProperty("n", list.size); add("c", c) }.toString()
        }

        /** A "blocks" line: [dt, dx, dy, dz, state] each, from the one before; a tick's changes by place (one block's in the order they came). */
        private fun blockLine(list: List<IntArray>): String {
            val sorted = list.sortedWith(compareBy<IntArray>({ it[0] }, { it[2] }, { it[3] }, { it[1] }))
            val d = JsonArray()
            var p = IntArray(4)
            for (b in sorted) { d.add(JsonArray().apply { for (i in 0 until 4) add(b[i] - p[i]); add(b[4]) }); p = b }
            return JsonObject().apply { addProperty("k", "blocks"); add("d", d) }.toString()
        }

        /** A "cams" line: each line's tick (differences) and frame count, then per frame its partial tick, yaw and pitch in hundredths (yaw and pitch as differences). */
        private fun camLine(list: List<Pair<Int, JsonArray>>): String {
            val t = JsonArray(); val n = JsonArray(); val pt = JsonArray(); val yaw = JsonArray(); val pitch = JsonArray()
            var pT = 0; var pY = 0L; var pP = 0L
            for ((tick, d) in list) {
                t.add(tick - pT); pT = tick; n.add(d.size())
                for (f in d) {
                    val a = f.asJsonArray
                    pt.add(hundredths(a[0])!!)
                    val y = hundredths(a[1])!!; val p = hundredths(a[2])!!
                    yaw.add(y - pY); pitch.add(p - pP); pY = y; pP = p
                }
            }
            return JsonObject().apply { addProperty("k", "cams"); add("t", t); add("n", n); add("pt", pt); add("yaw", yaw); add("pitch", pitch) }.toString()
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

        companion object {
            private val BLOCK_KEYS = setOf("k", "t", "x", "y", "z", "s")
            private val CAM_KEYS = setOf("k", "t", "d")
            /** Kinds that go in "grp" lines; the first ones go ahead of the moves, the rest after. */
            private val BEFORE_MOVES = listOf("spawn", "tag", "stand", "frame", "eq", "name")
            private val GROUPED = BEFORE_MOVES.toSet() + setOf("gone", "st", "mp", "sw")
            /** A number with at most 2 decimals, in hundredths (exactly), else null. */
            fun hundredths(e: JsonElement): Long? = (e as? JsonPrimitive)?.takeIf { it.isNumber }?.let { p ->
                runCatching { BigDecimal(p.asString).movePointRight(2).longValueExact() }.getOrNull()
            }
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
    private class Players(private val scan: Scan, private val sib: Sibling, private val aligned: Pair<Long, Int>) {
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

    /**
     * What leaving things out needs from a party member's upload (or a saved run). Anyone can upload
     * anything, so every field is checked and everything is limited (see [SIB_MAX_CHARS] and on);
     * anything off throws, and the run goes up as if there were no sibling.
     */
    private fun readSibling(input: InputStream): Sibling {
        val out = HashMap<Int, Int>()
        var self = ""
        var startMs = 0L
        var minT = Long.MAX_VALUE
        var maxT = -1L
        var rows = 0
        val seen = HashMap<String, MutableList<Long>>() // name -> ticks it had a row at, and pgone ticks as -(t + 1)
        val blocks = BlockReader()
        fun tick(t: Long): Long { if (t !in 0..SIB_MAX_TICK) throw Malformed("tick $t"); if (t < minT) minT = t; if (t > maxT) maxT = t; return t }
        fun row(name: String, t: Long) { if (++rows > SIB_MAX_ROWS) throw Malformed("too many player rows"); seen.getOrPut(name) { ArrayList() } += t }
        fun count(id: Int, n: Int) {
            out.merge(id, n) { a, b -> minOf(a.toLong() + b, Int.MAX_VALUE.toLong()).toInt() }
            if (out.size > SIB_MAX_IDS) throw Malformed("too many mobs")
        }
        lines(input).use { r ->
            for (line in capped(r)) {
                val kind = kindOf(line) ?: continue
                HEAD.find(line)?.let { if (kind !in WORLD) tick(it.groupValues[2].toLongOrNull() ?: throw Malformed("tick")) }
                blocks.read(kind, line)
                when (kind) {
                    "meta" -> obj(line).let { m ->
                        self = runCatching { str(m["self"]) }.getOrDefault("").takeIf { it.length <= 16 } ?: ""
                        startMs = runCatching { long(m["startMs"]) }.getOrDefault(0L)
                    }
                    "p" -> { val l = obj(line); val t = tick(long(l["t"])); for (x in arr(l["d"])) row(str(at(arr(x), 0)), t) }
                    "pgone" -> { val l = obj(line); row(str(l["name"]), -(tick(long(l["t"])) + 1)) }
                    "e" -> for (m in ROW_ID.findAll(line)) count(m.groupValues[1].toIntOrNull() ?: throw Malformed("id"), 1)
                    "cols" -> {
                        val l = obj(line)
                        var acc = 0L
                        val ticks = arr(l["t"]).map { acc += long(it); tick(acc) }
                        // Which of the line's ticks each mob (player) has a row in: each one once, in order -
                        // so a mob's count is at most the lines there are, not however many times it is listed.
                        fun inLines(a: JsonArray): List<Int> {
                            var li = 0L
                            return arr(at(a, 1)).mapIndexed { k, d ->
                                val step = long(d)
                                if (step < 0 || (k > 0 && step == 0L)) throw Malformed("line order")
                                li += step
                                if (li >= ticks.size) throw Malformed("line $li of ${ticks.size}")
                                li.toInt()
                            }
                        }
                        when (l["of"]?.let(::str)) {
                            "e" -> for (t in arr(l["tr"])) arr(t).let { count(int(at(it, 0)), inLines(it).size) }
                            "p" -> for (tr in arr(l["tr"])) { val a = arr(tr); val name = str(at(a, 0)); for (li in inLines(a)) row(name, ticks[li]) }
                        }
                    }
                    "proj" -> {
                        val l = obj(line)
                        val n = 1 + (l["arcs"]?.let { arr(it).size() } ?: 0) + (if (l.has("s")) 1 else 0) + (if (l.has("g")) 1 else 0)
                        count(int(l["id"]), n)
                    }
                    else -> if (kind in ENTITY_KINDS || (kind == "eq" && line.contains("\"id\":")))
                        ID.find(line)?.groupValues?.get(1)?.let { count(it.toIntOrNull() ?: throw Malformed("id"), 1) }
                }
            }
        }
        if (maxT < 0) maxT = 0
        // A mob can't have more than about one line a tick: a count past the ticks the recording
        // spans is made up, and capped there so it can't win every mob.
        val span = if (minT > maxT) 0L else maxT - minT + 1
        val counts = out.mapValues { (_, n) -> minOf(n.toLong(), span).toInt() }
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
        return Sibling(self, startMs, counts, players, blocks.changes())
    }

    /**
     * A party member's recording's lines, with [SIB_MAX_LINE] chars a line, [SIB_MAX_LINES] lines
     * and [SIB_MAX_CHARS] in all at most (a few KB of xz or gzip can unpack to gigabytes).
     */
    private fun capped(r: BufferedReader): Sequence<String> = sequence {
        val buf = CharArray(1 shl 16)
        val sb = StringBuilder()
        var chars = 0L
        var lines = 0
        fun line(): String { if (++lines > SIB_MAX_LINES) throw Malformed("too many lines"); if (sb.endsWith('\r')) sb.setLength(sb.length - 1); return sb.toString().also { sb.setLength(0) } }
        while (true) {
            val n = r.read(buf)
            if (n < 0) break
            chars += n
            if (chars > SIB_MAX_CHARS) throw Malformed("too big")
            var from = 0
            for (i in 0 until n) if (buf[i] == '\n') { sb.appendRange(buf, from, i); from = i + 1; yield(line()) }
            sb.appendRange(buf, from, n)
            if (sb.length > SIB_MAX_LINE) throw Malformed("line too long")
        }
        if (sb.isNotEmpty()) yield(line())
    }

    /**
     * A recording's lines, gzip or xz (told apart by their first bytes, as the site and viewer do).
     * xz with a memory limit: its header says how big a dictionary to make, and a made-up one would
     * have it allocate gigabytes.
     */
    private fun lines(raw: InputStream): BufferedReader {
        val input = BufferedInputStream(raw, 1 shl 16)
        input.mark(6)
        val head = input.readNBytes(6)
        input.reset()
        val xz = head.size >= 3 && head[0] == 0xFD.toByte() && head[1] == '7'.code.toByte() && head[2] == 'z'.code.toByte()
        return BufferedReader(InputStreamReader(if (xz) XZInputStream(input, XZ_MEMORY_KIB) else GZIPInputStream(input), Charsets.UTF_8), 1 shl 16)
    }
}
