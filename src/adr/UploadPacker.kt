package com.engineerclient.adr

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
 *  - xz (LZMA2, [xzOptions]) instead of gzip: well under half the size on a typical run.
 *    The site stores it as it comes; the viewer tells the two apart by their first bytes.
 *
 *  - When someone else in the party already uploaded their recording of the same run ([sibling]),
 *    a mob's life is left out only where that recording has the same one: the same entity id
 *    (the server's, the same on every client) and type, alive over all of it ([LIFE_MARGIN]),
 *    and nearly everywhere this recording saw it, within [AGREE_DIST] blocks of where that one
 *    says ([covered]). The viewer takes each life from one recording and fills in lives only the
 *    others have, so nothing it shows changes - and a made-up or edited upload, which doesn't
 *    agree, makes this one keep everything.
 *
 *  - Mobs as little as the viewer needs ([MobPlan], [MobWriter]): moves thinned to where it can't
 *    draw them from the ones either side (it slides between them, [MobPlan.LERP_GAP]), rounded;
 *    gone, a spawn's stand look and its name folded into the spawn; a mob coming back into range
 *    looking as it went the same life on; nothing the viewer couldn't show (stands never seen).
 *
 *  - Everything the viewer builds the world from comes first (after the meta line), with the
 *    whole room layout as a "roomsAll" line: those lines are written as the run goes, but none of
 *    them are about when. The viewer starts a long run from its first minute while the rest
 *    downloads; this way that first minute already has every room and door, and the world never
 *    has to be built again once the rest is in. The rest keeps its order.
 *
 *  - Other players the same way ([PlayerPlan]): rows thinned to where the viewer can't slide
 *    between them, rounded; the recording player's own the same, far finer.
 *
 *  - A projectile's whole life - its spawn and flight, new flights, where it stuck, when it was
 *    gone - is one "pj" line where it spawned: the few points the viewer needs to fly it from
 *    one to the next ([ProjPack]; most arrows are shot point-blank into a wall and stick a tick
 *    later: three lines and a move, for one arrow). One of each set of twins (a bow sends some
 *    twice over), none that never moved and were gone within a few ticks, and arrows the server
 *    sent already stuck as "pj" rows too ([Stretch.stuckArrows]).
 *
 *  - Mob moves ("e") and player lines ("p") as columns ("cols"): the whole run's, a column per
 *    value with every mob's (player's) one after another, as differences within each mob's -
 *    mostly 0, 1 and -1, which pack to a fraction of the numbers written out. Mob events, the
 *    lines that came one at a time (but chat, time and skins: the site reads those), block changes
 *    and camera frames the same way ([Stretch]); the viewer turns them all back into the lines
 *    they were.
 *
 *  - The recording player's camera frames thinned: one is left out where the frames either side of it, as the
 *    viewer draws between them, are within [CAM_TOLERANCE] degrees of it (a pixel or so), never
 *    leaving more than [CAM_GAP] ticks between two (more than 2 is "not rendering" to the viewer).
 *    The mouse over an open window the same way ([thinMouse]), and the heads placed in the world
 *    as one "skulls" line ([skullsLine]).
 *
 *  - Other players a party member's upload already has ([sibling]), and where it has them
 *    where this one does: their lines are left out while that recording has them, with a margin either side: the two recordings' ticks are
 *    lined up the way the viewer does it (the same block changes in both), [ALIGNED_MARGIN]; if
 *    they can't be, by start time, [COVER_MARGIN]. The viewer fills a player from any
 *    recording that has them at that tick, so nothing it shows changes. If that recording is the
 *    player's own, their equipment, skin and held head go too: the viewer takes those from it.
 *    Only when the two line up by block changes: by start time alone, nothing is left out.
 *
 *  - Block changes as their numbers in the site's catalogue of what runs do, where it has them
 *    ([BlockTemplates], [Catalogue]): a crusher's tick, a room's moving granite, each one number - and
 *    the boss floors that go all at once (P1's end platforms, Goldor's floor) the same in every run
 *    first ([Templates.ends]).
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

    /** Ticks a stretch of columns holds (see [Stretch]): more than any run, so a run is one (its columns pack smaller whole); a line bigger than [COLS_MAX] is split. */
    private const val COLS_TICKS = 1_000_000_000
    private const val COLS_MAX = 100_000 // (the site takes lines up to 128 KB)
    private const val NL = "\n" // never newLine(): on Windows that is "\r\n"
    private const val CAM_TOLERANCE = 1.0
    private const val CAM_GAP = 1.95
    /** GUI pixels the viewer's mouse may be off (see thinMouse), and how far apart (ticks) it slides between two points. */
    private const val MOUSE_TOLERANCE = 1.0
    private const val MOUSE_GAP = 2.0
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
     * Party Chat on ([partyChat]): the viewer uses it.
     * Used both while recording (Hide Private Chats) and on upload (always).
     */
    private val PRIVATE_LINE = Regex("""^(?:(?:From|To) (?:\[[^\]]+] )?\w{1,16}: |(?:Guild|Officer|Co-op|Friend) > |Friend request from |You are now friends with |-*\s*Friends \(Page |Guild Name: |Total Members: |Online Members: |Offline Members: |-- .+ --$)""")
    private val PRIVATE_ANYWHERE = Regex("""(?i)\bco-op\b|\b(?:joined|left) the guild\b|\bto join (?:their|the|your) guild\b|\bremoved you from your friends\b""")
    fun privateChat(text: String): Boolean = text.lineSequence().any { raw ->
        val l = raw.trim()
        PRIVATE_LINE.containsMatchIn(l) || (!l.startsWith("Party > ") && PRIVATE_ANYWHERE.containsMatchIn(l))
    }

    /** Party chat ("Party > [RANK] name: ..."): left out with Hide Party Chat on. */
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
        /** The times (tick - 1 + partial tick, as the viewer reads them) of the camera frames that stay, in order. */
        internal val camTimes: DoubleArray,
        /** Block changes as (tick, "x,y,z,state"), for lining this recording up with another. */
        internal val blocks: List<Pair<Long, String>>,
        /** Per "mouse" line (in order), which of its points stay; null: all. */
        internal val mouseKeep: List<BooleanArray?> = emptyList(),
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
        val mice = ArrayList<DoubleArray>() // time, x, y, mouse line, point
        var mouseLines = 0
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
                    "mouse" -> {
                        val d = runCatching { JsonParser.parseString(line).asJsonObject.getAsJsonArray("d") }.getOrNull()
                        d?.forEachIndexed { k, f -> val a = f.asJsonArray; mice += doubleArrayOf(t!! - 1 + a[0].asDouble, a[1].asDouble, a[2].asDouble, mouseLines.toDouble(), k.toDouble()) }
                        mouseLines++
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
        return Scan(summary, layout?.replaceFirst(""""k":"rooms"""", """"k":"roomsAll""""), world, counts, projs, self, startMs, thinCamera(frames, camLines), camTimes(frames), blocks.changes(), thinMouse(mice, mouseLines))
    }

    /**
     * Which camera frames to keep (see the top): from each kept frame, the furthest one the viewer's
     * straight line to it stays within [CAM_TOLERANCE] of every frame between, at most [CAM_GAP]
     * ticks on. In time order, as the viewer sorts them.
     */
    /** The frames thinCamera kept (it marks them, f[5]): their times as the viewer reads them (partial ticks in tenths). */
    private fun camTimes(frames: List<DoubleArray>): DoubleArray =
        frames.filter { it.size > 5 && it[5] > 0 }.map { f -> val t = Math.floor(f[0]) ; t + Math.round((f[0] - t) * 10) / 10.0 }.sorted().toDoubleArray()

    /**
     * Which mouse points to keep: the viewer draws the mouse on the straight line between two
     * points up to [MOUSE_GAP] ticks apart (further, where the first was), so from each kept point
     * the furthest one that line stays within [MOUSE_TOLERANCE] GUI pixels of every point between.
     */
    private fun thinMouse(pts: MutableList<DoubleArray>, lines: Int): List<BooleanArray?> {
        val keep = arrayOfNulls<BooleanArray>(lines)
        if (pts.size < 3) return keep.toList()
        pts.sortBy { it[0] }
        val kept = BooleanArray(pts.size)
        kept[0] = true; kept[pts.size - 1] = true
        var k = 0
        while (k < pts.size - 1) {
            var m = k + 1
            var c = k + 2
            while (c < pts.size) {
                val pk = pts[k]; val pc = pts[c]
                val span = pc[0] - pk[0]
                var ok = true
                // (held still till c: the point before it stays if the viewer slid from it to c)
                if (span > MOUSE_GAP && c - k > 1 && pc[0] - pts[c - 1][0] <= MOUSE_GAP) break
                for (j in k + 1 until c) {
                    val a = if (span > MOUSE_GAP) 0.0 else (pts[j][0] - pk[0]) / span
                    if (Math.hypot(pk[1] + (pc[1] - pk[1]) * a - pts[j][1], pk[2] + (pc[2] - pk[2]) * a - pts[j][2]) > MOUSE_TOLERANCE) { ok = false; break }
                }
                if (!ok) break
                m = c; c++
            }
            kept[m] = true; k = m
        }
        val counts = IntArray(lines)
        for (f in pts) counts[f[3].toInt()] = maxOf(counts[f[3].toInt()], f[4].toInt() + 1)
        pts.forEachIndexed { i, f ->
            val li = f[3].toInt()
            val arr = keep[li] ?: BooleanArray(counts[li]).also { keep[li] = it }
            arr[f[4].toInt()] = kept[i]
        }
        return keep.toList()
    }

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
            if (kept[i]) frames[i] = f + 1.0
            val li = f[3].toInt()
            val arr = keep[li] ?: BooleanArray(counts[li]).also { keep[li] = it }
            arr[f[4].toInt()] = kept[i]
        }
        return keep.toList()
    }

/** A party member's upload, as far as leaving things out goes: whose it is, each mob's event count, when it had each player, its block changes, where it had each mob and player. */
    private class Sibling(
        val self: String, val startMs: Long, val counts: Map<Int, Int>, val players: Map<String, List<LongRange>>, val blocks: List<Pair<Long, String>>,
        val mobs: Map<Int, Track>, val lives: Map<Int, List<SibLife>>, val playerAt: Map<String, Track>,
        /** Ticks apart the viewer slides its mobs between rows (0: it holds them till the next). */
        val lerp: Int,
    ) {
        /** Where it had mob [id] at its tick [t] in life [life]. */
        fun mobAt(id: Int, life: SibLife, t: Long): DoubleArray? {
            val r = mobs[id]?.at(t, lerp)
            return if (r == null || r[0] < life.from) doubleArrayOf(t.toDouble(), life.x, life.y, life.z) else r
        }
    }

    /** A mob's life in a party member's upload, in its ticks: from its spawn to its gone, where it spawned and as what. */
    private class SibLife(val from: Long, var to: Long, val x: Double, val y: Double, val z: Double, val type: String?)

    /** One mob's or player's rows in a recording (t, x, y, z), in order. */
    private class Track {
        private var t = LongArray(8); private var p = DoubleArray(24); var n = 0; private set
        fun add(tick: Long, x: Double, y: Double, z: Double) {
            if (n == t.size) { t = t.copyOf(n * 2); p = p.copyOf(n * 6) }
            t[n] = tick; p[3 * n] = x; p[3 * n + 1] = y; p[3 * n + 2] = z; n++
        }
        fun sort() {
            if ((1 until n).all { t[it - 1] <= t[it] }) return
            val order = (0 until n).sortedBy { t[it] }
            val t2 = LongArray(t.size); val p2 = DoubleArray(p.size)
            order.forEachIndexed { k, i -> t2[k] = t[i]; for (c in 0..2) p2[3 * k + c] = p[3 * i + c] }
            t = t2; p = p2
        }
        /** [t, x, y, z] at tick [at]: its last row, or slid toward the next one up to [lerp] ticks on (the viewer's way); null before the first. */
        fun at(at: Long, lerp: Int): DoubleArray? {
            var lo = 0; var hi = n - 1
            if (n == 0 || t[0] > at) return null
            while (lo < hi) { val m = (lo + hi + 1) / 2; if (t[m] <= at) lo = m else hi = m - 1 }
            val i = lo
            if (lerp > 0 && i + 1 < n && t[i + 1] - t[i] in 2..lerp.toLong()) {
                val f = (at - t[i]).toDouble() / (t[i + 1] - t[i])
                return doubleArrayOf(t[i].toDouble(), p[3 * i] + (p[3 * i + 3] - p[3 * i]) * f, p[3 * i + 1] + (p[3 * i + 4] - p[3 * i + 1]) * f, p[3 * i + 2] + (p[3 * i + 5] - p[3 * i + 2]) * f)
            }
            return doubleArrayOf(t[i].toDouble(), p[3 * i], p[3 * i + 1], p[3 * i + 2])
        }
    }

    private const val LIFE_MARGIN = 10
    /** How far apart two recordings' views of a mob or player can be and still be the same (theirs are at a tenth of a block, slid). */
    private const val AGREE_DIST = 1.5
    private const val AGREE_SAMPLES = 30

    private fun dist(a: DoubleArray, b: DoubleArray) = Math.sqrt((a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]) + (a[3] - b[3]) * (a[3] - b[3]))

    /** Up to [AGREE_SAMPLES] of [rows], spread out. */
    private fun sample(rows: List<DoubleArray>) = if (rows.size <= AGREE_SAMPLES) rows else List(AGREE_SAMPLES) { rows[it * rows.size / AGREE_SAMPLES] }

    /**
     * Whether the party member's upload has this life of a mob - all of it (but a few ticks at its
     * ends), and where this recording saw it: nine in ten of its moves within [AGREE_DIST]. An
     * upload that has it somewhere else (or made up) has nothing left out for it.
     */
    private fun covered(life: MobPlan.Life, sib: Sibling, offset: Long, ticks: Int): Boolean {
        if (life.proj) return false
        val theirs = sib.lives[life.id] ?: return false
        val s = life.spawn.toLong(); val g = (life.gone ?: ticks).toLong()
        var from = s + LIFE_MARGIN; var to = g - LIFE_MARGIN
        if (from > to) { from = (s + g) / 2; to = from }
        val sl = theirs.firstOrNull { it.from + offset <= from && it.to + offset >= to } ?: return false
        if (sl.type != null && sl.type != life.type) return false
        val pts = sample(life.rows.filter { it[0] >= from && it[0] <= to }).ifEmpty { listOf(doubleArrayOf(from.toDouble(), life.at[0], life.at[1], life.at[2])) }
        val ok = pts.count { q -> sib.mobAt(life.id, sl, q[0].toLong() - offset)?.let { dist(it, q) <= AGREE_DIST } == true }
        return ok * 10 >= pts.size * 9
    }

    /**
     * A recording's block changes with their palette's states, as (tick, "x,y,z,state"). An upload's
     * template lines ("btpl") too, with [cat] - the version its meta says, and its rooms' frames.
     */
    private class BlockReader(private val cat: Catalogue.Blocks? = null) {
        private val pal = HashMap<Int, String>()
        private val raw = ArrayList<Triple<Long, String, Int>>()
        private val framed = ArrayList<Pair<Long, String>>()
        private var frame: Templates.Frame? = null
        private var catOk = false
        fun read(kind: String, line: String) {
            if (kind == "meta" && cat != null) { val v = cat.version; catOk = runCatching { int((obj(line)["cat"] as? JsonObject)?.get("blocks")) == v }.getOrDefault(false); return }
            if (kind == "roomsAll" && cat != null) { frame = Templates.Frame(obj(line)["r"] as? JsonArray); return }
            if (kind == "btpl") {
                val f = frame
                if (cat == null || !catOk || f == null) return
                for (c in decodeBtpl(obj(line), cat, f, MAX_BLOCKS - raw.size - framed.size)) framed += c.t.toLong() to "${c.x},${c.y},${c.z},${c.state}"
                return
            }
            if (kind != "pal" && kind != "block" && kind != "blocks") return
            val l = obj(line)
            // (past the limits the rest is left out: lining up needs only some of them)
            if (kind == "pal") { val i = int(l["i"]); if (pal.size < MAX_PAL || i in pal) pal[i] = str(l["s"]) }
            else if (kind == "block") { if (raw.size < MAX_BLOCKS) raw += Triple(long(l["t"]), "${int(l["x"])},${int(l["y"])},${int(l["z"])}", int(l["s"])) }
            else {
                // A "blocks" line (Stretch): each change from the one before.
                var t = 0L; var x = 0; var y = 0; var z = 0
                for (e in arr(l["d"])) {
                    if (raw.size >= MAX_BLOCKS) break
                    val b = arr(e)
                    t += long(at(b, 0)); x += int(at(b, 1)); y += int(at(b, 2)); z += int(at(b, 3))
                    raw += Triple(t, "$x,$y,$z", int(at(b, 4)))
                }
            }
        }
        fun changes() = raw.map { (t, at, s) -> t to at + "," + (pal[s] ?: "?") } + framed
    }

    /**
     * A "btpl" line's block changes (see [BlockTemplates]): per device, its first piece's tick (from
     * the device before's first) and number, then each next piece's tick (from the one before) and
     * its rank among the pieces the catalogue says come after that one - or -(number + 1).
     */
    private fun decodeBtpl(l: JsonObject, cat: Catalogue.Blocks, frame: Templates.Frame, max: Int): List<Templates.Change> {
        val out = ArrayList<Templates.Change>()
        var qt = 0L
        for (e in arr(l["s"])) {
            val s = arr(e)
            if (s.size() < 2 || s.size() % 2 != 0) throw Malformed("device")
            qt += long(s[0])
            var t = qt
            var id = int(s[1])
            var i = 0
            while (true) {
                if (id !in cat.pieces.indices) throw Malformed("piece $id")
                if (t !in 0..SIB_MAX_TICK) throw Malformed("tick $t")
                out += Templates.pieceCells(cat.pieces[id], frame, t.toInt()) ?: throw Malformed("piece $id isn't in this run")
                if (out.size > max) throw Malformed("too many block changes")
                i += 2
                if (i >= s.size()) break
                t += long(s[i])
                val c = int(s[i + 1])
                id = if (c >= 0) cat.next.getOrNull(id)?.getOrNull(c) ?: throw Malformed("next") else -(c.toLong() + 1).toInt()
            }
        }
        return out
    }

    /**
     * Block changes as template numbers ([Templates]; "btpl" lines): the pieces the catalogue has, a
     * device at a time - each piece as its rank among those the catalogue says usually come next, or
     * its number - and the rest as "blocks" lines. The boss floors that go all at once go the same
     * way in every run first ([Templates.ends]). Read back before it goes: anything not the same
     * and the changes go up as they are.
     */
    private class BlockTemplates(val cat: Catalogue.Blocks, val frame: Templates.Frame, private val pal: Map<Int, String>) {
        private val index = HashMap<String, Int>().apply { for ((i, st) in pal.entries.sortedBy { it.key }) putIfAbsent(st, i) }
        class Result(val streams: List<List<Templates.Piece>>, val rest: List<IntArray>, val all: List<Templates.Change>)

        /** [sorted]: [t, x, y, z, state number] by tick, y, z, x (as [Stretch.blockLine] has them). */
        fun encode(sorted: List<IntArray>): Result? {
            val changes = sorted.map { Templates.Change(it[0], it[1], it[2], it[3], pal[it[4]] ?: return null) }
            val all = if (cat.ends.isEmpty() || AIR !in index) changes
                else Templates.ends(changes, cat.ends).withIndex().sortedWith(compareBy({ it.value.t }, { it.value.y }, { it.value.z }, { it.value.x }, { it.index })).map { it.value }
            val covered = Templates.pieces(all, frame).filter { p -> cat.id[p.key]?.also { p.id = it } != null }
            val used = BooleanArray(all.size)
            for (p in covered) for (i in p.idx) used[i] = true
            val streams = Templates.streams(covered).map { s -> s.map { covered[it] } }.sortedWith(compareBy({ it[0].t }, { it[0].id }))
            val rest = all.indices.filter { !used[it] }.map { val c = all[it]; intArrayOf(c.t, c.x, c.y, c.z, index[c.state] ?: return null) }
            return Result(streams, rest, all)
        }

        fun line(streams: List<List<Templates.Piece>>): String {
            var pt = 0
            val s = JsonArray()
            for (st in streams) s.add(JsonArray().apply {
                add(st[0].t - pt); add(st[0].id); pt = st[0].t
                for (i in 1 until st.size) {
                    val r = cat.next[st[i - 1].id].indexOf(st[i].id)
                    add(st[i].t - st[i - 1].t); add(if (r >= 0) r else -(st[i].id + 1))
                }
            })
            return JsonObject().apply { addProperty("k", "btpl"); addProperty("v", cat.version); add("s", s) }.toString()
        }

        /** Whether [lines] read back as [all]: the same changes, and a block's in a tick in the same order. */
        fun check(lines: List<String>, all: List<Templates.Change>): Boolean {
            fun into(m: HashMap<String, MutableList<String>>, t: Long, x: Int, y: Int, z: Int, st: String) { m.getOrPut("$t,$x,$y,$z") { ArrayList() } += st }
            val want = HashMap<String, MutableList<String>>(); val got = HashMap<String, MutableList<String>>()
            for (c in all) into(want, c.t.toLong(), c.x, c.y, c.z, c.state)
            for (line in lines) {
                val l = obj(line)
                when (str(l["k"])) {
                    "btpl" -> for (c in decodeBtpl(l, cat, frame, MAX_BLOCKS)) into(got, c.t.toLong(), c.x, c.y, c.z, c.state)
                    "blocks" -> { var t = 0L; var x = 0; var y = 0; var z = 0; for (e in arr(l["d"])) { val b = arr(e); t += long(b[0]); x += int(b[1]); y += int(b[2]); z += int(b[3]); into(got, t, x, y, z, pal[int(b[4])] ?: return false) } }
                    else -> return false
                }
            }
            return got == want
        }
    }
    private const val AIR = "minecraft:air"

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
    fun pack(file: Path, scan: Scan, sibling: ByteArray?, hideParty: Boolean = false, cat: Catalogue? = null): Pair<Path, Int> {
        // Whatever is wrong with a party member's upload, this one goes up - whole, if need be.
        val sib = sibling?.let { b ->
            runCatching { readSibling(b.inputStream(), cat?.blocks, cat?.values) }.onFailure { log.warn("[ec] adr: a party member's upload couldn't be read, nothing left out: {}", it.toString()) }.getOrNull()
        }
        if (sib != null) {
            try {
                return write(file, scan, sib, hideParty, cat)
            } catch (e: Exception) {
                log.warn("[ec] adr: packing with a party member's upload failed, packing it whole", e)
            }
        }
        return write(file, scan, null, hideParty, cat)
    }

    private fun write(file: Path, scan: Scan, sibling: Sibling?, hideParty: Boolean, cat: Catalogue?): Pair<Path, Int> {
        // Nothing is left out unless the two recordings line up by their block changes: by start
        // time alone (or a recording that only claims to be of this run) is too unsure to trust.
        val aligned = sibling?.let { lineUp(scan, it) }
        val sib = sibling?.takeIf { aligned?.second == ALIGNED_MARGIN }
        if (sibling != null && sib == null) log.info("[ec] adr: a party member's upload doesn't line up with this one, nothing left out")
        val plan = lines(Files.newInputStream(file)).use { r -> MobPlan.read(r.lineSequence()) { scan.fold(it) != null } }
        // Mob lives the party member's upload has, the same (checked against this one's): left out.
        val ticks = scan.summary["ticks"]?.asInt ?: 0
        val left = sib?.let { s -> plan.lives.values.flatten().filter { covered(it, s, aligned!!.first, ticks) }.toHashSet() } ?: emptySet()
        val players = sib?.let { Players(scan, it, aligned!!, plan.players) }
        val others = lines(Files.newInputStream(file)).use { r -> PlayerPlan.read(r.lineSequence(), scan.self, scan.camTimes) }
        val out = Files.createTempFile("adr-upload-", ".jsonl.xz")
        try {
            writeTo(file, scan, out, plan, others, left, players, hideParty, cat)
        } catch (t: Throwable) {
            Files.deleteIfExists(out)
            throw t
        }
        return out to left.size
    }

    private fun writeTo(file: Path, scan: Scan, out: Path, plan: MobPlan, others: PlayerPlan, left: Set<MobPlan.Life>, players: Players?, hideParty: Boolean, cat: Catalogue?) {
        var camLine = 0
        var mouseLine = 0
        // Block changes as template numbers: with the catalogue, and the rooms (their frames) and palette the viewer will have.
        val blockTpl = cat?.blocks?.let { b ->
            val rooms = scan.layout?.let { runCatching { obj(it)["r"] as? JsonArray }.getOrNull() } ?: return@let null
            val pal = HashMap<Int, String>()
            for (l in scan.world) if (kindOf(l) == "pal") runCatching { val o = obj(l); pal.putIfAbsent(int(o["i"]), str(o["s"])) }
            BlockTemplates(b, Templates.Frame(rooms), pal)
        }
        val stream = XZOutputStream(Files.newOutputStream(out), xzOptions())
        BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 1 shl 16).use { w ->
            val cols = Stretch(w, blockTpl, cat?.values)
            val mobs = MobWriter(plan) { it in left }
            val timeline: (String) -> Unit = { l -> if (players == null) cols.put(l) else players.put(l, cols::put) }
            fun put(line: String, timed: Boolean = true) {
                var kept = fold(line, scan) ?: return
                if (kept.startsWith("{\"k\":\"cam\"")) kept = thin(kept, scan.camKeep.getOrNull(camLine++)) ?: return
                if (kept.startsWith("{\"k\":\"mouse\"")) kept = mouse(kept, scan.mouseKeep.getOrNull(mouseLine++)) ?: return
                // (the world's lines carry ticks, but aren't in the timeline: none of them start a stretch)
                if (!timed) { if (players?.keepsWorld(kept) != false) { w.write(kept); w.write(NL) }; return }
                others.put(kept) { mobs.put(it, timeline) }
            }
            // The first line (meta), the layout and the world's lines; then everything else.
            lines(Files.newInputStream(file)).use { r ->
                var first = true
                for (line in r.lineSequence()) {
                    if (first) {
                        // (the viewer fills mobs' and other players' moves in between the rows, up to this many ticks apart: MobPlan, PlayerPlan)
                        first = false; put(runCatching { obj(line).apply { addProperty("lerp", MobPlan.LERP_GAP); addProperty("plerp", MobPlan.LERP_GAP); addProperty("hy", 1); addProperty("selfcam", 1); addProperty("sthin", 1); addProperty("walkyaw", 1); addProperty("erel", 1); addProperty("eqtab", 1); addProperty("pj2", 1); addProperty("tab2", 1); addProperty("eqfold", 1)
                            val catMeta = JsonObject().apply { blockTpl?.let { addProperty("blocks", it.cat.version) }; cat?.values?.let { addProperty("values", it.version) } }
                            if (catMeta.size() > 0) add("cat", catMeta) }.toString() }.getOrDefault(line), timed = false)
                        scan.layout?.let { w.write(it); w.write(NL) }
                        val skulls = ArrayList<String>()
                        for (l in scan.world) if (kindOf(l) == "skull") skulls += l else put(l, timed = false)
                        for (l in skullsLines(skulls)) { w.write(l); w.write(NL) }
                        continue
                    }
                    // A line cut short (a crash part way through writing it) would get the whole run refused.
                    if (!line.startsWith("{") || !line.endsWith("}")) continue
                    val kind = kindOf(line)
                    if (kind == "chat" && privateChatLine(line, hideParty)) continue
                    if (kind == "chat") { put(plainChat(line)); continue }
                    if (kind !in WORLD) put(line)
                }
            }
            others.end { mobs.put(it, timeline) }
            mobs.end(timeline)
            cols.end()
        }
    }

    /**
     * The heads placed in the world as "skulls" lines (the viewer has no use for when each was
     * seen): by place, each [dx, dy, dz, texture] from the one before, the textures by number
     * ("tex"); [SKULLS_LINE] to a line at most (each line on its own: the site takes lines up to 128 KB).
     */
    private fun skullsLines(lines: List<String>): List<String> {
        val at = java.util.TreeMap<Triple<Int, Int, Int>, String>(compareBy<Triple<Int, Int, Int>>({ it.first }, { it.third }, { it.second }))
        for (line in lines) runCatching {
            val l = obj(line)
            at[Triple(int(l["x"]), int(l["y"]), int(l["z"]))] = (l["tex"] as JsonPrimitive).asString
        }
        return at.entries.chunked(SKULLS_LINE).map { part ->
            val tex = LinkedHashMap<String, Int>()
            val d = JsonArray()
            var p = Triple(0, 0, 0)
            for ((q, t) in part) {
                d.add(JsonArray().apply { add(q.first - p.first); add(q.second - p.second); add(q.third - p.third); add(tex.getOrPut(t) { tex.size }) })
                p = q
            }
            JsonObject().apply { addProperty("k", "skulls"); add("tex", JsonArray().apply { tex.keys.forEach(::add) }); add("d", d) }.toString()
        }
    }
    private const val SKULLS_LINE = 1200 // (every one a new texture: still under 100 KB)

    /**
     * xz as small as it gets for these files (a few percent under preset 6): preset 9's match finder
     * with an 8 MB dictionary (the site takes up to 16; an upload unpacks to a few MB), and no
     * position bits but four of literal context - it is text, where the byte before says more
     * than where a byte is.
     */
    private fun xzOptions() = LZMA2Options(9).apply { niceLen = 273; dictSize = 8 shl 20; pb = 0; lc = 4; lp = 0 }

    /** A chat line without its plain text ("m") where that is the coloured one ("c") without its § codes (the viewer works it out). */
    private fun plainChat(line: String): String = runCatching {
        val l = obj(line)
        val c = (l["c"] as? JsonPrimitive)?.asString ?: return line
        if ((l["m"] as? JsonPrimitive)?.asString != c.replace(CODES, "")) return line
        l.remove("m"); l.toString()
    }.getOrDefault(line)
    private val CODES = Regex("§.")

    /** A line as it goes up: a projectile's spawn becomes its "proj" line, the rest of its lines go (null). */
    private fun fold(line: String, scan: Scan): String? {
        val kind = kindOf(line) ?: return line
        when (kind) {
            "spawn", "arc", "gone" -> {
                val id = ID.find(line)?.groupValues?.get(1)?.toInt() ?: return line
                val p = scan.fold(id) ?: return line
                if (kind != "spawn") return null
                return ProjPack.line(p).toString()
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
     *  - mob events (spawn, tag, stand, frame, eq, name, gone), the server tick, map and swing
     *    lines, projectiles and the lines that come one at a time ([SINGLES]; a window's, [UI],
     *    all in one) as "grp": per kind, a column per field the same way;
     *  - block changes as "blocks": each tick's sorted by place, every one a difference from the one
     *    before;
     *  - camera frames as "cams": columns of hundredths (the turns tenths, as differences).
     * The order they come in keeps each tick's events in the order the viewer needs them (a mob's
     * spawn before its moves, its tag before them, gone after).
     */
    private class Stretch(private val w: BufferedWriter, private val blockTpl: BlockTemplates? = null, private val values: Catalogue.Values? = null) {
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
                kind in SINGLES -> { groups.getOrPut(kind) { ArrayList() } += JsonParser.parseString(line).asJsonObject; true }
                // (a window's lines in one "ui" group, each its kind in "of": the viewer needs a tick's in their order)
                kind in UI -> { groups.getOrPut("ui") { ArrayList() } += JsonParser.parseString(line).asJsonObject.apply { remove("k"); addProperty("of", kind) }; true }
                else -> false
            }
        }.getOrDefault(false)

        fun end() = flush()

        private fun flush() {
            stuckArrows()
            groups["pj"]?.let { groups["pj"] = projectiles(it) }
            groups["eq"]?.let { groups["eq"] = gear(it) }
            foldGear()
            for (kind in BEFORE_MOVES) groups[kind]?.let { write(it) { part -> group(kind, part) } }
            for ((kind, list) in items) if (list.isNotEmpty()) write(list) { encode(kind, it) }
            for ((kind, list) in groups) if (kind !in BEFORE_MOVES) write(list) { part -> group(kind, part) }
            if (blocks.isNotEmpty()) writeBlocks()
            if (cams.isNotEmpty()) write(cams) { camLine(it) }
            for (l in others) { w.write(l); w.write(NL) }
            for (list in items.values) list.clear()
            groups.clear(); blocks.clear(); cams.clear(); others.clear()
        }

        /** One line of [list], or two of its halves (and so on) while it's longer than [COLS_MAX]. */
        private fun <T> write(list: List<T>, encode: (List<T>) -> String) { for (l in lines(list, encode)) { w.write(l); w.write(NL) } }
        private fun <T> lines(list: List<T>, encode: (List<T>) -> String): List<String> {
            val sizes = tables.mapValues { it.value.order.size }
            val line = encode(list)
            if (line.length > COLS_MAX && list.size > 1) {
                for ((f, t) in tables) t.reset(sizes.getValue(f)) // (the halves number them again)
                return lines(list.subList(0, list.size / 2), encode) + lines(list.subList(list.size / 2, list.size), encode)
            }
            return listOf(line)
        }

        /** The block changes: as template numbers and the rest ([BlockTemplates]) where that reads back the same, else as they are. */
        private fun writeBlocks() {
            val sorted = blocks.sortedWith(BLOCK_ORDER)
            val tpl = blockTpl
            val res = tpl?.let { runCatching { it.encode(sorted) }.onFailure { e -> log.warn("[ec] adr: block templates failed, sent as they are", e) }.getOrNull() }
            if (tpl != null && res != null) {
                val out = (if (res.streams.isEmpty()) emptyList() else lines(res.streams) { tpl.line(it) }) + (if (res.rest.isEmpty()) emptyList() else lines(res.rest) { blockLine(it) })
                if (runCatching { tpl.check(out, res.all) }.getOrDefault(false)) { for (l in out) { w.write(l); w.write(NL) }; return }
                log.warn("[ec] adr: block templates didn't read back the same, sent as they are")
            }
            write(sorted) { blockLine(it) }
        }

        /**
         * Gear lines as they go up:
         *  - a cosmetic's animation left out: a change of only dye colours or the head's skin that the
         *    same one's next change of gear comes within [COSMETIC_TICKS] of (animated helmets and
         *    rainbow armour change every tick or two; a change of gear stays) - the look before stays;
         *  - players' main hand left out ("" in its slot: the viewer has it from their "p" rows), and a
         *    line that leaves them as they were.
         */
        private fun gear(list: List<JsonObject>): ArrayList<JsonObject> {
            val byKey = LinkedHashMap<String, MutableList<Int>>()
            list.forEachIndexed { i, o -> byKey.getOrPut((o["name"] ?: o["id"]).toString()) { ArrayList() } += i }
            val drop = BooleanArray(list.size)
            for (idx in byKey.values) {
                var kept: JsonObject? = null
                idx.forEachIndexed { n, i ->
                    val o = list[i]
                    val next = idx.getOrNull(n + 1)?.let { list[it] }
                    val was = kept
                    if (was != null && next != null && tick(next) - tick(o) < COSMETIC_TICKS && onlyCosmetic(o, was)) drop[i] = true else kept = o
                }
            }
            val out = ArrayList<JsonObject>()
            val last = HashMap<String, String>()
            list.forEachIndexed { i, o ->
                if (drop[i]) return@forEachIndexed
                val name = (o["name"] as? JsonPrimitive)?.asString
                if (name == null) { out += o; return@forEachIndexed }
                val c = o.deepCopy()
                (c["eq"] as? JsonArray)?.takeIf { it.size() > 0 }?.set(0, JsonPrimitive(""))
                val look = c["eq"].toString() + "|" + c["headTex"]
                if (last.put(name, look) != look) out += c
            }
            return out
        }
        private fun tick(o: JsonObject) = (o["t"] as? JsonPrimitive)?.asInt ?: 0
        /** Whether gear line [a] is [b] but for dye colours and the head's skin. */
        private fun onlyCosmetic(a: JsonObject, b: JsonObject): Boolean {
            val x = a["eq"] as? JsonArray ?: return false
            val y = b["eq"] as? JsonArray ?: return false
            if (x.size() != y.size()) return false
            for (i in 0 until x.size()) if ((x[i] as? JsonPrimitive)?.asString?.replace(DYE, "") != (y[i] as? JsonPrimitive)?.asString?.replace(DYE, "")) return false
            return true
        }

        /**
         * A mob's first gear, a tick after its spawn (nearly always): on its spawn row instead
         * ("eq", "headTex"; "eqfold" in the meta: the viewer puts it back a tick after the spawn).
         */
        private fun foldGear() {
            val spawns = groups["spawn"] ?: return
            val eqs = groups["eq"] ?: return
            class Life(val row: JsonObject, var first: Boolean = true)
            val life = HashMap<Int, Life>()
            val folded = HashSet<JsonObject>()
            var s = 0
            for (e in eqs) {
                val t = tick(e)
                while (s < spawns.size && tick(spawns[s]) <= t) { idOf(spawns[s])?.let { life[it] = Life(spawns[s]) }; s++ }
                val id = idOf(e) ?: continue
                val l = life[id] ?: continue
                if (l.first && tick(l.row) + 1 == t && !l.row.has("eq") && !l.row.has("headTex") && e.has("eq")) {
                    l.row.add("eq", e["eq"]); e["headTex"]?.let { l.row.add("headTex", it) }
                    folded += e
                }
                l.first = false
            }
            if (folded.isNotEmpty()) groups["eq"] = ArrayList(eqs.filter { it !in folded })
        }
        private fun idOf(o: JsonObject) = (o["id"] as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt

        /**
         * Arrows the server sent already stuck (a spawn with no flight) and nothing else is about but
         * their spawn: "pj" rows marked "spawn" ("pj2" in the meta), with its look and how long it
         * lives ("g", the spawn's "life") - the viewer makes the spawn it was again. Far smaller than a spawn
         * row; an F7 archer's run has hundreds.
         */
        private fun stuckArrows() {
            val spawns = groups["spawn"] ?: return
            val stuck = spawns.filter { s -> s.keySet().all { it in STUCK_KEYS } && (s["type"] as? JsonPrimitive)?.asString == "minecraft:arrow" && s.has("x") && s.has("y") && s.has("z") }
            if (stuck.isEmpty()) return
            // (only those no other line names: the order of a tick's events can't change)
            val named = HashMap<Int, Int>()
            for ((kind, list) in groups) for (o in list) (o["id"] as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt?.let { named.merge(it, if (kind == "spawn") 1 else 2, Int::plus) }
            for (it in items.getValue("e")) for (r in it.rows) named.merge(r.asJsonArray[0].asInt, 2, Int::plus)
            for (l in others) ID.find(l)?.groupValues?.get(1)?.toIntOrNull()?.let { named.merge(it, 2, Int::plus) }
            val moving = stuck.filter { named[it["id"].asInt] == 1 }.toSet()
            if (moving.isEmpty()) return
            groups["spawn"] = ArrayList(spawns.filter { it !in moving })
            val rows = moving.map { s -> JsonObject().apply {
                add("t", s["t"]); add("id", s["id"]); addProperty("spawn", 1); add("x", s["x"]); add("y", s["y"]); add("z", s["z"])
                s["yaw"]?.let { add("yaw", it) }; s["life"]?.let { add("g", it) }
            } }
            groups["pj"] = ArrayList(((groups["pj"] ?: emptyList<JsonObject>()) + rows).sortedBy { it["t"].asInt })
        }

        /**
         * The projectiles that go up ("pj2"): one of each where several are the same (a bow sends
         * twins: the same tick, place and flight), none for an arrow that never moved and was gone
         * within [DUD_TICKS] (shot into nothing the server keeps), and the gone tick ("g") counted
         * from its last point, where it ends.
         */
        private fun projectiles(list: List<JsonObject>): ArrayList<JsonObject> {
            val seen = HashSet<String>()
            val out = ArrayList<JsonObject>()
            for (o in list) {
                val p = o["p"] as? JsonArray
                val g = (o["g"] as? JsonPrimitive)?.asInt
                if (p != null && !o.has("type") && g != null && g <= DUD_TICKS
                    && (0 until p.size() / 4).all { i -> Math.hypot(Math.hypot(p[4 * i + 1].asDouble, p[4 * i + 2].asDouble), p[4 * i + 3].asDouble) < DUD_TENTHS }) continue
                if (!seen.add(JsonObject().apply { for ((k, v) in o.entrySet()) if (k != "id") add(k, v) }.toString())) continue
                out += if (p != null && g != null && p.size() >= 4) o.deepCopy().apply { addProperty("g", g - p[p.size() - 4].asInt) } else o
            }
            return out
        }

        /**
         * One "cols" line ("flat"): the lines' ticks; each mob/player once (mobs by id, as differences
         * from the one before: "kd") with how many rows it has ("n"); then every row's line (each
         * one's first as it is, the rest as differences: "li") and length ("sz"); then a column per
         * place in the rows, everyone's one after another - numbers as differences within each
         * mob's (its first as it is), anything else as runs. One long stream per value packs
         * smaller than each mob's own little table.
         */
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
            val byId = kind == "e" && keys.values.all { (it as? JsonPrimitive)?.isNumber == true && it.asString.toLongOrNull() != null }
            val order = if (byId) tracks.keys.sortedBy { keys.getValue(it).asLong } else tracks.keys.toList()
            val width = tracks.values.maxOfOrNull { rows -> rows.maxOf { it.second.size() } } ?: 1
            val ks = JsonArray(); val n = JsonArray(); val li = JsonArray(); val sz = JsonArray()
            val vals = List(maxOf(0, width - 1)) { ArrayList<JsonElement>() }
            val starts = List(maxOf(0, width - 1)) { ArrayList<Boolean>() }
            var prevKey = 0L
            for (key in order) {
                val rows = tracks.getValue(key)
                ks.add(if (byId) keys.getValue(key).asLong.let { id -> JsonPrimitive(id - prevKey).also { prevKey = id } } else keys.getValue(key))
                n.add(rows.size)
                var prevLi = 0
                val first = BooleanArray(vals.size) { true }
                rows.forEachIndexed { j, (at, row) ->
                    li.add(if (j == 0) at else at - prevLi); prevLi = at
                    sz.add(row.size())
                    for (c in 1 until row.size()) { vals[c - 1] += row[c]; starts[c - 1] += first[c - 1]; first[c - 1] = false }
                }
            }
            return JsonObject().apply {
                addProperty("k", "cols"); addProperty("of", kind); addProperty("flat", 1)
                if (byId) addProperty("kd", 1)
                add("t", deltas(list.map { it.t.toLong() }))
                add("keys", ks); add("n", n); add("li", li); add("sz", sz)
                add("cols", JsonArray().apply { vals.forEachIndexed { c, v -> add(flatColumn(v, starts[c])) } })
            }.toString()
        }

        /** A "flat" cols column: numbers (at most 4 decimals) as [decimals, ...] each a difference from the one before it in its mob's (one starting a mob's: as it is); else runs. */
        private fun flatColumn(values: List<JsonElement>, starts: List<Boolean>): JsonElement {
            val nums = values.map { v -> (v as? JsonPrimitive)?.takeIf { it.isNumber }?.let { runCatching { BigDecimal(it.asString) }.getOrNull() } }
            if (nums.all { it != null }) {
                val dec = nums.maxOfOrNull { it!!.stripTrailingZeros().scale().coerceAtLeast(0) } ?: 0
                if (dec <= 4) return JsonArray().apply {
                    add(dec)
                    var prev = 0L
                    nums.forEachIndexed { i, b -> val q = b!!.movePointRight(dec).longValueExact(); add(if (starts[i]) q else q - prev); prev = q }
                }
            }
            return column(values)
        }

        /**
         * Values that come over and over - gear ("eq"), head skins ("headTex"), names ("c"), stand poses,
         * mob and item types - on gear, spawn, name and stand lines ([TABLED]): each the first time whole,
         * then as its number in the order they first came ("eqtab"/"tab2" in the meta: the viewer keeps
         * the same lists), and one the site's catalogue has as -(its number there + 1) ([Catalogue.Values]).
         */
        private val tables = TABLED_FIELDS.associateWith { Table() }
        private class Table {
            val index = HashMap<String, Int>(); val order = ArrayList<String>()
            fun of(v: JsonElement, cat: Map<String, Int>?): JsonElement {
                if (v.isJsonNull || (v is JsonPrimitive && v.isString && v.asString.isEmpty())) return v
                cat?.get(Catalogue.canon(v))?.let { return JsonPrimitive(-1 - it) }
                val key = v.toString()
                index[key]?.let { return JsonPrimitive(it) }
                index[key] = order.size; order += key
                return v
            }
            fun reset(size: Int) { while (order.size > size) index.remove(order.removeAt(order.size - 1)) }
        }

        /** A "grp" line: one kind's lines, a column per field ("n" of them; null where a line hasn't the field). */
        private fun group(kind: String, list: List<JsonObject>): String {
            val fields = LinkedHashSet<String>()
            for (o in list) for (f in o.keySet()) if (f != "k") fields += f
            val c = JsonObject()
            for (f in fields) c.add(f, column(list.map { o -> (o[f] ?: JsonNull.INSTANCE).let { v -> if (kind in TABLED) tables[f]?.of(v, values?.fields?.get(f)) ?: v else v } }))
            return JsonObject().apply { addProperty("k", "grp"); addProperty("of", kind); addProperty("n", list.size); add("c", c) }.toString()
        }

        /** A "blocks" line: [dt, dx, dy, dz, state] each, from the one before; a tick's changes by place (one block's in the order they came). */
        private fun blockLine(list: List<IntArray>): String {
            val sorted = list.sortedWith(BLOCK_ORDER)
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
                    pt.add(Math.round(a[0].asDouble * 10))
                    // (turns in tenths of a degree: "s")
                    val y = Math.round(a[1].asDouble * 10); val p = Math.round(a[2].asDouble * 10)
                    yaw.add(y - pY); pitch.add(p - pP); pY = y; pP = p
                }
            }
            return JsonObject().apply { addProperty("k", "cams"); addProperty("s", 10); addProperty("ps", 10); add("t", t); add("n", n); add("pt", pt); add("yaw", yaw); add("pitch", pitch) }.toString()
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
            /** Block changes by tick, then y, z, x (stable: one block's in a tick in the order they came). */
            private val BLOCK_ORDER = compareBy<IntArray>({ it[0] }, { it[2] }, { it[3] }, { it[1] })
            private val CAM_KEYS = setOf("k", "t", "d")
            /** Kinds that go in "grp" lines; the first ones go ahead of the moves, the rest after. */
            private val BEFORE_MOVES = listOf("spawn", "tag", "stand", "frame", "eq", "name")
            private val GROUPED = BEFORE_MOVES.toSet() + setOf("gone", "st", "mp", "sw", "pj")
            /**
             * Lines that were each a line of their own, grouped too ("grp"; the viewer reads each kind into
             * a list of its own). Not chat, time or skin: the site reads those lines as they are.
             */
            private val SINGLES = setOf("mouse", "pgone", "use", "hotbar", "tp", "slotclick", "rooms", "held", "ether", "room")
            /** A window's lines, one "ui" group in their order. */
            private val UI = setOf("gui", "slots", "carried", "click", "guiclose")
            /** The kinds whose values go by number ([tables]), and the fields. */
            private val TABLED = setOf("eq", "spawn", "name", "stand")
            private val TABLED_FIELDS = listOf("eq", "headTex", "c", "pose", "type", "item", "itemId")
            /** A change of only colours and skins on gear followed this soon by another isn't sent (see gear). */
            private const val COSMETIC_TICKS = 10
            private val DYE = Regex("#[0-9a-f]{6}")
            /** A spawn that can be a "pj" row (see stuckArrows). */
            private val STUCK_KEYS = setOf("k", "t", "id", "type", "x", "y", "z", "yaw", "life")
            /** An arrow that went no further than this (tenths of a block) and was gone within [DUD_TICKS] isn't sent. */
            private const val DUD_TENTHS = 5.0
            private const val DUD_TICKS = 3
            /** A number with at most 2 decimals, in hundredths (exactly), else null. */
            fun hundredths(e: JsonElement): Long? = (e as? JsonPrimitive)?.takeIf { it.isNumber }?.let { p ->
                runCatching { BigDecimal(p.asString).movePointRight(2).longValueExact() }.getOrNull()
            }
        }
    }

    /** A "cam" line with only the frames [keep] keeps, or null when none are left. */
    /** A "mouse" line with only the points [keep] has (see thinMouse): when to a hundredth of a tick (it moves fast), where to a tenth of a GUI pixel. */
    private fun mouse(line: String, keep: BooleanArray?): String? = runCatching {
        val l = JsonParser.parseString(line).asJsonObject
        val left = JsonArray()
        l.getAsJsonArray("d").forEachIndexed { k, f -> if (keep?.getOrElse(k) { true } != false) left.add(JsonArray().apply { f.asJsonArray.forEachIndexed { i, v -> add(if (i == 0) MobPlan.hundredth(v.asDouble) else MobPlan.tenth(v.asDouble)) } }) }
        if (left.size() == 0) return null
        l.add("d", left)
        l.toString()
    }.getOrDefault(line)

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
    private class Players(private val scan: Scan, private val sib: Sibling, private val aligned: Pair<Long, Int>, private val ours: Map<String, List<DoubleArray>>) {
        /** Ticks (this recording's) the sibling surely has each player, where it has them as this one does. */
        private val covered = sib.players.mapValues { (name, list) ->
            val (offset, margin) = aligned
            list.mapNotNull { r -> val a = r.first + offset + margin; val b = r.last + offset - margin; if (a <= b && same(name, a, b, offset)) a..b else null }
        }

        /** Whether the sibling has [name] where this recording does, from tick [a] to [b] (nine in ten of its rows). */
        private fun same(name: String, a: Long, b: Long, offset: Long): Boolean {
            val pts = sample(ours[name]?.filter { it[0] >= a && it[0] <= b } ?: return true)
            val track = sib.playerAt[name] ?: return false
            // (slid between rows as the viewer does: their rows are thinned too)
            val ok = pts.count { q -> track.at(q[0].toLong() - offset, sib.lerp)?.let { dist(it, q) <= AGREE_DIST } == true }
            return ok * 10 >= pts.size * 9
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
    private fun readSibling(input: InputStream, cat: Catalogue.Blocks? = null, values: Catalogue.Values? = null): Sibling {
        val out = HashMap<Int, Int>()
        var self = ""
        var startMs = 0L
        var minT = Long.MAX_VALUE
        var maxT = -1L
        var rows = 0
        val seen = HashMap<String, MutableList<Long>>() // name -> ticks it had a row at, and pgone ticks as -(t + 1)
        val blocks = BlockReader(cat)
        val mobs = HashMap<Int, Track>()
        val playerAt = HashMap<String, Track>()
        val spawns = HashMap<Int, MutableList<DoubleArray>>() // id -> [t, life (or -1), x, y, z, back], and their types
        val types = HashMap<Long, String>() // (id shl 32 | spawn tick) -> type
        val gones = HashMap<Int, MutableList<Long>>()
        var lerp = 0
        var erel = false
        var tab2 = false
        var valuesOk = false
        val typeTable = ArrayList<String?>() // ("tab2": spawn types by number, see Stretch.tables)
        val relative = ArrayList<DoubleArray>() // ("erel": mob rows from where the mob spawned) id, t, x, y, z, till the spawns are in
        fun mob(id: Int, t: Long, x: Double?, y: Double?, z: Double?) {
            if (x == null || y == null || z == null) return
            if (++rows > SIB_MAX_ROWS) throw Malformed("too many rows")
            if (erel) relative += doubleArrayOf(id.toDouble(), t.toDouble(), x, y, z) else mobs.getOrPut(id) { Track() }.add(t, x, y, z)
        }
        fun player(name: String, t: Long, x: Double?, y: Double?, z: Double?) { if (x == null || y == null || z == null) return; playerAt.getOrPut(name) { Track() }.add(t, x, y, z) }
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
                        lerp = runCatching { int(m["lerp"]) }.getOrDefault(0).coerceIn(0, 200)
                        erel = runCatching { int(m["erel"]) }.getOrDefault(0) == 1
                        tab2 = runCatching { int(m["tab2"]) }.getOrDefault(0) == 1
                        valuesOk = values != null && runCatching { int((m["cat"] as? JsonObject)?.get("values")) == values.version }.getOrDefault(false)
                    }
                    "p" -> { val l = obj(line); val t = tick(long(l["t"])); for (x in arr(l["d"])) { val r = arr(x); val name = str(at(r, 0)); row(name, t); if (r.size() >= 4) player(name, t, num(r[1]), num(r[2]), num(r[3])) } }
                    "e" -> { val l = obj(line); val t = tick(long(l["t"])); for (x in arr(l["d"])) { val r = arr(x); val id = int(at(r, 0)); count(id, 1); if (r.size() >= 4) mob(id, t, num(r[1]), num(r[2]), num(r[3])) } }
                    "pgone" -> { val l = obj(line); row(str(l["name"]), -(tick(long(l["t"])) + 1)) }
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
                        val byId = l.has("kd")
                        var id = 0L
                        // Each one's first three columns (x, y, z: every row has them).
                        fun xyz(a: JsonArray, n: Int): List<List<Double?>> { val cols = arr(at(a, 3)); return (0..2).map { c -> if (c < cols.size()) nums(cols[c], n) else emptyList() } }
                        if (l.has("flat")) {
                            val of = l["of"]?.let(::str)
                            for (f in flatRows(l, ticks.size)) {
                                if (of == "e") {
                                    id = if (byId) id + long(f.key) else long(f.key)
                                    val mid = id.toInt().takeIf { i -> i.toLong() == id } ?: throw Malformed("id")
                                    count(mid, f.lines.size)
                                    f.lines.forEachIndexed { k, li -> mob(mid, ticks[li], f.x[k], f.y[k], f.z[k]) }
                                } else if (of == "p") {
                                    val name = str(f.key)
                                    f.lines.forEachIndexed { k, li -> row(name, ticks[li]); player(name, ticks[li], f.x[k], f.y[k], f.z[k]) }
                                }
                            }
                            continue
                        }
                        when (l["of"]?.let(::str)) {
                            "e" -> for (t in arr(l["tr"])) arr(t).let {
                                id = if (byId) id + long(at(it, 0)) else long(at(it, 0))
                                val mid = id.toInt().takeIf { i -> i.toLong() == id } ?: throw Malformed("id")
                                val lis = inLines(it); count(mid, lis.size)
                                val (xs, ys, zs) = xyz(it, lis.size)
                                lis.forEachIndexed { k, li -> mob(mid, ticks[li], xs.getOrNull(k), ys.getOrNull(k), zs.getOrNull(k)) }
                            }
                            "p" -> for (tr in arr(l["tr"])) {
                                val a = arr(tr); val name = str(at(a, 0)); val lis = inLines(a)
                                val (xs, ys, zs) = xyz(a, lis.size)
                                lis.forEachIndexed { k, li -> row(name, ticks[li]); player(name, ticks[li], xs.getOrNull(k), ys.getOrNull(k), zs.getOrNull(k)) }
                            }
                        }
                    }
                    "grp" -> {
                        // Mob events as columns (Stretch): each one's id, and its tick.
                        val l = obj(line)
                        val of = str(l["of"])
                        val n = int(l["n"]).also { if (it !in 0..SIB_MAX_ROWS) throw Malformed("rows") }
                        val c = l["c"] as? JsonObject ?: throw Malformed("columns")
                        c["t"]?.let { col -> for (t in longs(col, n)) tick(t) }
                        if (of in ENTITY_KINDS || of == "eq") c["id"]?.let { col -> for (id in longs(col, n)) count(id.toInt().takeIf { it.toLong() == id } ?: throw Malformed("id"), 1) }
                        if (of == "pgone") {
                            val ts = longs(c["t"] ?: throw Malformed("t"), n); val names = strs(c["name"] ?: throw Malformed("name"), n)
                            for (k in ts.indices) names.getOrNull(k)?.let { row(it, -(tick(ts[k]) + 1)) }
                        }
                        // (arrows sent already stuck: "pj" rows marked "spawn", a spawn each - see Stretch.stuckArrows)
                        if (of == "pj" && c["spawn"] != null) {
                            val ts = nums(c["t"] ?: throw Malformed("t"), n); val ids = nums(c["id"] ?: throw Malformed("id"), n)
                            val sp = nums(c["spawn"]!!, n).map { it == 1.0 }; val g = c["g"]?.let { nums(it, n) }
                            val xs = c["x"]?.let { nums(it, n) }; val ys = c["y"]?.let { nums(it, n) }; val zs = c["z"]?.let { nums(it, n) }
                            for (k in ts.indices) {
                                if (sp.getOrNull(k) != true) continue
                                val t = ts[k] ?: continue; val i = ids.getOrNull(k)?.toInt() ?: continue
                                count(i, 1)
                                spawns.getOrPut(i) { ArrayList() } += doubleArrayOf(t, g?.getOrNull(k) ?: -1.0, xs?.getOrNull(k) ?: Double.NaN, ys?.getOrNull(k) ?: Double.NaN, zs?.getOrNull(k) ?: Double.NaN, 0.0)
                                types[(i.toLong() shl 32) or t.toLong()] = "minecraft:arrow"
                                if (spawns.size > SIB_MAX_IDS) throw Malformed("too many mobs")
                            }
                        }
                        // Lives: spawns (and how long, "life") and gone.
                        if (of == "spawn" || of == "gone") {
                            val ts = nums(c["t"] ?: throw Malformed("t"), n); val ids = nums(c["id"] ?: throw Malformed("id"), n)
                            if (of == "gone") ts.indices.forEach { k -> val t = ts[k]?.toLong() ?: return@forEach; val i = ids.getOrNull(k)?.toInt() ?: return@forEach; gones.getOrPut(i) { ArrayList() } += t }
                            else {
                                val life = c["life"]?.let { nums(it, n) }; val back = c["b"]?.let { nums(it, n) }; val v = c["v"]?.let { present(it, n) }
                                val xs = c["x"]?.let { nums(it, n) }; val ys = c["y"]?.let { nums(it, n) }; val zs = c["z"]?.let { nums(it, n) }
                                val ty = c["type"]?.let { if (tab2) tabled(it, n, typeTable, if (valuesOk) values?.lists?.get("type") else null) else strs(it, n) }
                                for (k in ts.indices) {
                                    val t = ts[k] ?: continue; val i = ids.getOrNull(k)?.toInt() ?: continue
                                    if (v?.getOrNull(k) == true) continue // (a projectile)
                                    spawns.getOrPut(i) { ArrayList() } += doubleArrayOf(t, life?.getOrNull(k) ?: -1.0, xs?.getOrNull(k) ?: Double.NaN, ys?.getOrNull(k) ?: Double.NaN, zs?.getOrNull(k) ?: Double.NaN, back?.getOrNull(k) ?: 0.0)
                                    ty?.getOrNull(k)?.let { types[(i.toLong() shl 32) or t.toLong()] = it }
                                    if (spawns.size > SIB_MAX_IDS) throw Malformed("too many mobs")
                                }
                            }
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
        // Each mob's lives: from a spawn till its "life" ran out, its gone or the next spawn.
        val lives = HashMap<Int, List<SibLife>>()
        for ((id, list) in spawns) {
            val gs = gones[id]?.sorted() ?: emptyList()
            var type: String? = null
            lives[id] = list.sortedBy { it[0] }.let { ss -> ss.mapIndexed { k, s ->
                val t = s[0].toLong()
                type = types[(id.toLong() shl 32) or t] ?: if (s[5] > 0) type else null
                val next = ss.getOrNull(k + 1)?.get(0)?.toLong() ?: Long.MAX_VALUE
                val end = if (s[1] > 0) t + s[1].toLong() - 1 else gs.firstOrNull { it >= t && it <= next } ?: minOf(next, maxT)
                SibLife(t, end, s[2], s[3], s[4], type)
            } }
        }
        // Relative rows: from their life's spawn (to a fifth of a block, as MobPlan sends them).
        if (relative.isNotEmpty()) {
            val sorted = spawns.mapValues { (_, l) -> l.sortedBy { it[0] } }
            for (r in relative) {
                val s = sorted[r[0].toInt()]?.lastOrNull { it[0] <= r[1] } ?: continue
                if (s[2].isNaN() || s[3].isNaN() || s[4].isNaN()) continue
                mobs.getOrPut(r[0].toInt()) { Track() }.add(r[1].toLong(), r[2] + MobPlan.fifthOf(s[2]), r[3] + MobPlan.fifthOf(s[3]), r[4] + MobPlan.fifthOf(s[4]))
            }
        }
        for (tr in mobs.values) tr.sort()
        for (tr in playerAt.values) tr.sort()
        return Sibling(self, startMs, counts, players, blocks.changes(), mobs, lives, playerAt, lerp)
    }

    /** A number, or null. */
    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { it.isNumber }?.asDouble

    /** A run-length column of a field that goes by number (Stretch.tables, "tab2") as strings, at most [n]; [table] the run's list so far, [cat] the catalogue's. */
    private fun tabled(col: JsonElement, n: Int, table: MutableList<String?>, cat: List<JsonElement>?): List<String?> {
        val out = ArrayList<String?>()
        val r = (col as? JsonObject)?.get("r") as? JsonArray ?: throw Malformed("column")
        var i = 0
        while (i + 1 < r.size() && out.size < n) {
            val v = r[i]; val k = long(r[i + 1])
            if (k < 0) throw Malformed("run")
            for (j in 0 until minOf(k, (n - out.size).toLong())) out += when {
                v is JsonPrimitive && v.isNumber -> { val q = long(v); if (q >= 0) table.getOrNull(q.toInt()) else cat?.getOrNull((-1 - q).toInt())?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.asString } }
                v is JsonPrimitive && v.isString -> v.asString.take(64).also { if (it.isNotEmpty() && table.size < SIB_MAX_IDS) table += it } // ("" isn't numbered)
                else -> null
            }
            i += 2
        }
        return out
    }

    /** A column (Stretch.column) as numbers, at most [n]: null where a row hasn't one. */
    private fun nums(col: JsonElement, n: Int): List<Double?> {
        val out = ArrayList<Double?>()
        if (col is JsonArray) {
            if (col.size() == 0) return out
            val dec = long(col[0]).also { if (it !in 0..8) throw Malformed("decimals") }
            val scale = Math.pow(10.0, dec.toDouble())
            var q = 0L
            for (i in 1 until col.size()) { if (out.size >= n) break; q += long(col[i]); out += q / scale }
            return out
        }
        val r = arr((col as? JsonObject ?: throw Malformed("column"))["r"])
        var i = 0
        while (i + 1 < r.size() && out.size < n) {
            val v = num(r[i]); val k = long(r[i + 1])
            if (k < 0) throw Malformed("run")
            repeat(minOf(k, (n - out.size).toLong()).toInt()) { out += v }
            i += 2
        }
        return out
    }

    /** A "flat" cols line's mobs (players): each one's key, the lines it has a row in and its rows' x, y and z. */
    private class FlatRows(val key: JsonElement, val lines: List<Int>, val x: List<Double?>, val y: List<Double?>, val z: List<Double?>)
    private fun flatRows(l: JsonObject, lineCount: Int): List<FlatRows> {
        val keys = arr(l["keys"]); val ns = arr(l["n"]); val li = arr(l["li"]); val sz = arr(l["sz"]); val cols = arr(l["cols"])
        if (keys.size() != ns.size()) throw Malformed("keys")
        if (li.size() != sz.size() || li.size() > SIB_MAX_ROWS) throw Malformed("rows")
        // x, y, z: number columns, differences within each one's (its first as it is)
        val xyz = (0..2).map { c -> (cols.getOrNull(c) as? JsonArray)?.takeIf { it.size() > 0 } }
        val scale = xyz.map { col -> col?.let { Math.pow(10.0, long(it[0]).also { d -> if (d !in 0..8) throw Malformed("decimals") }.toDouble()) } }
        val ptr = IntArray(3) { 1 }
        val out = ArrayList<FlatRows>()
        var row = 0
        for (k in 0 until keys.size()) {
            val n = long(ns[k]).also { if (it < 0 || row + it > li.size()) throw Malformed("rows") }.toInt()
            val lines = ArrayList<Int>(n); val vals = List(3) { ArrayList<Double?>(n) }
            val q = LongArray(3); val started = BooleanArray(3)
            var at = 0L
            for (j in 0 until n) {
                val step = long(li[row])
                if (step < 0 || (j > 0 && step == 0L)) throw Malformed("line order")
                at = if (j == 0) step else at + step
                if (at >= lineCount) throw Malformed("line $at of $lineCount")
                lines += at.toInt()
                val size = long(sz[row]).also { if (it < 1 || it > 64) throw Malformed("row size") }
                for (c in 0..2) {
                    val col = xyz[c]
                    if (size <= c + 1 || col == null || ptr[c] >= col.size()) { vals[c] += null; continue }
                    val d = long(col[ptr[c]++])
                    q[c] = if (started[c]) q[c] + d else d; started[c] = true
                    vals[c] += q[c] / scale[c]!!
                }
                row++
            }
            out += FlatRows(keys[k], lines, vals[0], vals[1], vals[2])
        }
        return out
    }
    private fun JsonArray.getOrNull(i: Int): JsonElement? = if (i in 0 until size()) get(i) else null

    /** Which rows of a run-length column have a value. */
    private fun present(col: JsonElement, n: Int): List<Boolean> {
        val out = ArrayList<Boolean>()
        val r = (col as? JsonObject)?.get("r") as? JsonArray ?: return List(n) { false }
        var i = 0
        while (i + 1 < r.size() && out.size < n) { val k = long(r[i + 1]); if (k < 0) throw Malformed("run"); repeat(minOf(k, (n - out.size).toLong()).toInt()) { out += r[i] !is JsonNull }; i += 2 }
        return out
    }

    /** A run-length column as strings, at most [n]: null where a row hasn't one (or it isn't a string). */
    private fun strs(col: JsonElement, n: Int): List<String?> {
        val out = ArrayList<String?>()
        val r = (col as? JsonObject)?.get("r") as? JsonArray ?: return List(n) { null }
        var i = 0
        while (i + 1 < r.size() && out.size < n) {
            val v = (r[i] as? JsonPrimitive)?.takeIf { it.isString }?.asString?.take(64); val k = long(r[i + 1])
            if (k < 0) throw Malformed("run")
            repeat(minOf(k, (n - out.size).toLong()).toInt()) { out += v }
            i += 2
        }
        return out
    }

    /** A whole-number column (Stretch.column) as its values, at most [n]; null (a line without the field) left out. */
    private fun longs(col: JsonElement, n: Int): List<Long> {
        val out = ArrayList<Long>()
        if (col is JsonArray) {
            if (col.size() == 0 || long(col[0]) != 0L) throw Malformed("not whole numbers")
            var q = 0L
            for (i in 1 until col.size()) { if (out.size >= n) break; q += long(col[i]); out += q }
            return out
        }
        val r = arr((col as? JsonObject ?: throw Malformed("column"))["r"])
        var i = 0
        while (i + 1 < r.size() && out.size < n) {
            val v = r[i]; val k = long(r[i + 1])
            if (k < 0) throw Malformed("run")
            val x = if (v is JsonNull) null else long(v)
            for (j in 0 until minOf(k, (n - out.size).toLong())) if (x != null) out += x else break
            i += 2
        }
        return out
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
