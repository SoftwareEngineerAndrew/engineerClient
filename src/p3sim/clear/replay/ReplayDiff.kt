package com.engineerclient.p3sim.clear.replay

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.run.CheckMark
import com.engineerclient.p3sim.run.KeyDropped
import com.engineerclient.p3sim.run.KeyPicked
import com.engineerclient.p3sim.run.RunBlocks
import com.engineerclient.p3sim.run.RunEvent
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * What a replay found ADS doing unlike the recording, written to `replays/<id>.report.txt` when it ends. Each difference is put down
 * as either ADS's (it happened by you, or is the world itself) or the teammates' (they did it out of ADS's reach; it was done for
 * them). Server thread, but for [adsChat] (the client thread).
 */
class ReplayDiff(private val s: ReplaySession) {
    private val lines = ArrayList<String>()
    private val forced = ArrayList<String>()

    private val actions = ArrayList<String>()
    fun action(k: Int, text: String) { actions += "%6d  %s".format(k, text) }

    private val kicks = ArrayList<String>()
    fun kick(k: Int, reason: String) { kicks += "%6d  %s".format(k, reason); note(k, "kick", "the server would have dropped you: $reason") }

    private var endWhy = "still running"; private var endK = 0; private var complete = false
    private var bossK: Int? = null; private var lastK = 0
    fun ended(why: String, k: Int, boss: Int?, last: Int, whole: Boolean) { endWhy = why; endK = k; bossK = boss; lastK = last; complete = whole }

    fun note(k: Int, kind: String, text: String) { lines += "%6d  %-7s %s".format(k, kind, text) }

    fun forced(k: Int, what: String, result: String?) { forced += "%6d  %s -> %s".format(k, what, result ?: "?") }

    // ---- mobs

    private var mobsOk = 0
    private val mobsMissing = ArrayList<String>()
    private val mobsNearYou = ArrayList<String>()

    fun mobOk() { mobsOk++ }

    /** Recorded WITHER-door HP jumps: ADS had the same max HP (within 1%) two ticks later, or not. */
    private var hpScaleOk = 0
    private val hpScaleOff = ArrayList<String>()
    fun hpScale(k: Int, label: String, rec: Double, ads: Double?) {
        if (ads != null && Math.abs(ads - rec) <= rec * 0.01) hpScaleOk++
        else hpScaleOff += "%6d  %s: Hypixel %,.0f, ADS %s".format(k, label, rec, ads?.let { "%,.0f".format(it) } ?: "no mob")
    }
    /** Camp mob spawns, in ticks after each side's own BLOOD door: Hypixel's (from the recording) and ADS's. */
    var campHypixel: List<Int> = emptyList()
    val campAds = ArrayList<Int>()
    fun mobNotFound(k: Int, label: String, x: Double, y: Double, z: Double, you: Double, why: String = "") {
        mobsMissing += "%6d  %s at %s (you %.0f blocks away)%s".format(k, label, ReplaySession.fmt(x, y, z), you, if (why.isEmpty()) "" else ": $why")
    }
    /** A recorded death ADS has not done yet; [you]: how far you were (near you, the kill was most likely yours). */
    fun mobPending(k: Int, label: String, you: Double, ads: String) { if (you < 10) mobsNearYou += "%6d  %s (you %.0f blocks away): %s".format(k, label, you, ads) }

    // ---- moves and teleports

    private var movesOff = 0
    private var moves = 0
    private val moveExamples = ArrayList<String>()

    /** After a recorded move was handed in: where ADS has you against where the move put you. */
    fun moved(k: Int, x: Double, y: Double, z: Double, p: ServerPlayer) {
        moves++
        val d = Math.sqrt((p.x - x) * (p.x - x) + (p.y - y) * (p.y - y) + (p.z - z) * (p.z - z))
        if (d > 0.3) {
            movesOff++
            if (moveExamples.size < 40 && (moveExamples.isEmpty() || !moveExamples.last().startsWith("%6d".format(k - 1)))) moveExamples += "%6d  sent %s, ADS has %s (%.2f off)".format(k, ReplaySession.fmt(x, y, z), ReplaySession.fmt(p.x, p.y, p.z), d)
        }
    }

    private val hypTps = ArrayList<Pair<Int, Vec3>>()
    private val adsTps = ArrayList<Pair<Int, Vec3>>()

    /** Hypixel putting you where you already were (after some block clicks): a resync, nothing moves, so not a teleport to match. */
    private var resyncs = 0
    private val setbacks = JsonArray()

    /** [design, x, y, z] of a world point in its room's frame (x and z with their fractions). */
    private fun canonPoint(v: Vec3): JsonArray? {
        val bx = Math.floor(v.x).toInt(); val bz = Math.floor(v.z).toInt()
        val room = s.run.plan.rooms.firstOrNull { it.placement?.ownsColumn(bx, bz) == true } ?: return null
        val pl = room.placement ?: return null
        val (cx, cz) = com.engineerclient.p3sim.clear.puzzles.hard.Frames.toCanonical(pl, v.x, v.z) ?: return null
        return JsonArray().apply { add(room.design?.id ?: room.name); add(Math.round(cx * 100) / 100.0); add(Math.round(v.y * 100) / 100.0); add(Math.round(cz * 100) / 100.0) }
    }

    fun recordedTeleport(k: Int, e: JsonArray, was: Vec3?) {
        val a = e.get(2).takeIf { it.isJsonArray }?.asJsonArray ?: return
        val to = Vec3(a[0].asDouble, a[1].asDouble, a[2].asDouble)
        if (was != null && was.distanceTo(to) < 0.5) { resyncs++; return }
        // Before the start Hypixel holds the party in the entrance by putting them back: where to, and from where, in the room's own frame.
        if (k < 0) { if (was != null && to.y < 200 && to.y > 0) canonPoint(to)?.let { a -> canonPoint(was)?.let { b -> setbacks.add(JsonArray().apply { add(k); a.forEach { add(it) }; b.forEach { add(it) } }) } }; return }
        hypTps += k to to
        if (was != null) tpWay[hypTps.size - 1] = listOfNotNull(
            blockedWay(was, to).takeIf { it.isNotEmpty() }?.let { "ADS has in the way: $it" },
            canonPoint(to)?.let { "room frame $it" },
            pastStop(was, to).let { "past it ADS has $it" },
        ).joinToString("; ")
    }

    /** For each of Hypixel's teleports (by index): ADS's blocks with a collision on the straight way it moved you (feet and head), at the time. */
    private val tpWay = HashMap<Int, String>()

    /**
     * What ADS has a step past where Hypixel stopped you ([b], going from [a]): the cells half a block and one block on, at the feet, the
     * body and the eyes. When Hypixel's blink ends short of ADS's, the block that stopped it is one of these on Hypixel.
     */
    private fun pastStop(a: Vec3, b: Vec3): String {
        val d = Vec3(b.x - a.x, 0.0, b.z - a.z).takeIf { it.length() > 1e-3 }?.normalize() ?: return "-"
        val seen = LinkedHashSet<BlockPos>()
        for (f in listOf(0.5, 1.0)) for (dy in listOf(0.1, 1.2, 1.62)) seen += BlockPos.containing(b.x + d.x * f, b.y + dy, b.z + d.z * f)
        return seen.joinToString(", ") { "${it.x} ${it.y} ${it.z} ${Sim.level.getBlockState(it).block.descriptionId.substringAfterLast('.')}" }
    }

    /** The cells on the line from [a] to [b] (each quarter block, feet and head height) that have a collision in ADS, as "x y z block". */
    private fun blockedWay(a: Vec3, b: Vec3): String {
        val seen = LinkedHashSet<BlockPos>()
        val n = (a.distanceTo(b) * 4).toInt().coerceIn(1, 400)
        for (i in 1..n) {
            val p = a.lerp(b, i / n.toDouble())
            for (dy in listOf(0.1, 1.2)) {
                val pos = BlockPos.containing(p.x, p.y + dy, p.z)
                if (!Sim.level.getBlockState(pos).getCollisionShape(Sim.level, pos).isEmpty) seen += pos
            }
        }
        return seen.joinToString(", ") { "${it.x} ${it.y} ${it.z} ${Sim.level.getBlockState(it).block.descriptionId.substringAfterLast('.')}" }
    }

    fun adsTeleport(k: Int, to: Vec3) { adsTps += k to to }

    fun missingTarget(k: Int, what: String, b: JsonObject) {
        note(k, "target", "$what on ${b.get("type")?.asString} \"${b.get("tag")?.takeIf { it.isJsonPrimitive }?.asString ?: b.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: ""}\" at ${b.get("x")} ${b.get("y")} ${b.get("z")}: ADS has nothing there")
    }

    // ---- keys

    private val keyLines = ArrayList<String>()
    fun recordedKeyStand(k: Int, kind: String, x: Double, y: Double, z: Double) { keyLines += "%6d  Hypixel  %s appeared at %s".format(k, kind, ReplaySession.fmt(x, y, z)) }
    fun recordedKeyPick(k: Int, kind: String, by: String) { keyLines += "%6d  Hypixel  %s taken by %s".format(k, kind, by) }

    fun adsEvent(e: RunEvent) {
        when (e) {
            is KeyDropped -> keyLines += "%6d  ADS      %s Key dropped at %s (room %s)".format(e.t, e.kind.name.lowercase().replaceFirstChar { it.uppercase() }, ReplaySession.fmt(e.x, e.y, e.z), s.run.plan.rooms.getOrNull(e.room)?.name ?: e.room)
            is KeyPicked -> keyLines += "%6d  ADS      %s Key taken by %s".format(e.t, e.kind.name.lowercase().replaceFirstChar { it.uppercase() }, s.run.state.players[e.player]?.name ?: "?")
            else -> {}
        }
    }

    // ---- blocks: what Hypixel changed a block to, checked in ADS a moment later

    private class BlockCheck(val due: Int, val k: Int, val pos: BlockPos, val want: String, val you: Double, val mate: Double)
    private val blockChecks = ArrayDeque<BlockCheck>()
    /** Block differences by where they came from: Hypixel's start-of-run updates (T0), near you (yours), away from you (teammates or the room). */
    private val blockMiss = linkedMapOf("start" to HashMap<String, MutableList<String>>(), "you" to HashMap(), "away" to HashMap())
    private var blocksChecked = 0
    private var blocksUnloaded = 0
    private var blocksForced = 0

    /** A block Hypixel changed; [you] and [mate]: how far you and the nearest teammate were from it. */
    fun recordedBlock(k: Int, x: Int, y: Int, z: Int, state: String?, you: Double, mate: Double) {
        if (state == null) return
        val pos = BlockPos(x, y, z)
        blockHistory.getOrPut(pos) { ArrayList() } += k to state
        blockChecks.addLast(BlockCheck(k + BLOCK_LAG, k, pos, state, you, mate))
    }

    /** Every recorded state of each changed cell, in order. */
    private val blockHistory = HashMap<BlockPos, MutableList<Pair<Int, String>>>()

    /** The states Hypixel had at [pos] from [from] to [to]: the one standing at [from] and every later change up to [to]. */
    private fun hypStates(pos: BlockPos, from: Int, to: Int): List<String> {
        val h = blockHistory[pos] ?: return emptyList()
        val out = ArrayList<String>()
        h.lastOrNull { it.first <= from }?.let { out += it.second }
        for ((k, st) in h) if (k in (from + 1)..to) out += st
        return out
    }

    // ---- chat

    private val recChat = ArrayList<Pair<Int, String>>()
    private val adsChatQ = ConcurrentLinkedQueue<Pair<Int, String>>()
    @Volatile var nowK = 0

    fun recordedChat(k: Int, t: String) { if (t.isNotBlank()) recChat += k to t }

    /** A chat line the game got from ADS (client thread). */
    fun adsChat(t: String) { val c = ReplaySession.clean(t); if (c.isNotBlank()) adsChatQ += nowK to c }

    // ---- rooms

    private val recRooms = LinkedHashMap<Int, MutableMap<String, Int>>()
    private val roomNames = HashMap<Int, String?>()

    fun recordedRoom(k: Int, room: Int, name: String?, mark: String) {
        roomNames[room] = name
        recRooms.getOrPut(room) { LinkedHashMap() }.putIfAbsent(mark, k)
    }

    private var lastDung: JsonObject? = null
    fun recordedDung(o: JsonObject?) { if (o != null) lastDung = o }

    fun tick(now: Int) {
        nowK = now
        while (blockChecks.isNotEmpty() && blockChecks.first().due <= now) {
            val c = blockChecks.removeFirst()
            if (!RunBlocks.isLoaded(c.pos.x, c.pos.z)) { blocksUnloaded++; continue }
            blocksChecked++
            val haveState = Sim.level.getBlockState(c.pos)
            val have = str(haveState)
            // Hypixel's cell as it stood at the check, give or take [BLOCK_TOLERANCE] ticks (a crusher steps on: the newest state is what ADS should show).
            val states = hypStates(c.pos, now - BLOCK_TOLERANCE, now)
            if (have in states) continue
            val want = states.lastOrNull() ?: c.want
            val wantId = want.substringBefore('['); val haveId = have.substringBefore('[')
            val key = if (wantId == haveId) "$wantId: state differs" else "Hypixel $wantId, ADS $haveId"
            val cat = if (c.k <= 1) "start" else if (c.you <= NEAR) "you" else "away"
            val l = blockMiss.getValue(cat).getOrPut(key) { ArrayList() }
            l += "k ${c.k} at ${c.pos.x} ${c.pos.y} ${c.pos.z}: Hypixel $want, ADS $have (you %.0f, teammate %.0f)".format(c.you, c.mate)
            canonCell(cat, c.k, c.pos, parse(want), haveState, c.you)?.let { cells.add(it) }
            // The world is put as Hypixel had it, so what comes after is played on the same blocks.
            parse(want)?.let { Sim.level.setBlock(c.pos, it, Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE); blocksForced++ }
        }
    }

    /** A block a teammate or the room changed away from you: counted when ADS did not have it already, then put in as it is, now. */
    fun awayBlock(k: Int, x: Int, y: Int, z: Int, state: String?, you: Double, mate: Double) {
        val st = state?.let { parse(it) } ?: return
        val pos = BlockPos(x, y, z)
        blockHistory.getOrPut(pos) { ArrayList() } += k to state
        if (!RunBlocks.isLoaded(x, z)) { blocksUnloaded++; return }
        blocksChecked++
        val have = Sim.level.getBlockState(pos)
        if (str(have) == state) return
        val wantId = state.substringBefore('['); val haveId = str(have).substringBefore('[')
        val key = if (wantId == haveId) "$wantId: state differs" else "Hypixel $wantId, ADS $haveId"
        blockMiss.getValue("away").getOrPut(key) { ArrayList() } += "k $k at $x $y $z: Hypixel $state, ADS ${str(have)} (you %.0f, teammate %.0f)".format(you, mate)
        canonCell("away", k, pos, st, have, you)?.let { cells.add(it) }
        Sim.level.setBlock(pos, st, Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE); blocksForced++
    }

    /** Puts a recorded block in as it is, now. */
    fun setNow(x: Int, y: Int, z: Int, state: String?) {
        val st = state?.let { parse(it) } ?: return
        Sim.level.setBlock(BlockPos(x, y, z), st, Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE)
    }

    /** Every block difference in its room's own frame: [cat, k, design, x, y, z, Hypixel state, ADS state, you], states turned back to the design's rotation. */
    private val cells = JsonArray()

    private fun canonCell(cat: String, k: Int, pos: BlockPos, hyp: BlockState?, ads: BlockState, you: Double): JsonArray? {
        val room = s.run.plan.rooms.firstOrNull { it.placement?.ownsColumn(pos.x, pos.z) == true } ?: return null
        val pl = room.placement ?: return null
        val (cx, cz) = pl.toCanonical(pos.x, pos.z) ?: return null
        val back = net.minecraft.world.level.block.Rotation.entries[(4 - pl.quarterTurns % 4) % 4]
        return JsonArray().apply {
            add(cat); add(k); add(room.design?.id ?: room.name); add(cx); add(pos.y); add(cz)
            add(hyp?.rotate(back)?.let { str(it) } ?: "?"); add(str(ads.rotate(back))); add(Math.round(you).toInt())
        }
    }

    private fun parse(s: String): BlockState? = try {
        BlockStateParser.parseForBlock(Sim.level.holderLookup(Registries.BLOCK), s, false).blockState()
    } catch (t: Throwable) { null }

    private fun str(b: BlockState): String {
        val id = BuiltInRegistries.BLOCK.getKey(b.block).toString()
        val props = b.values.toList().map { it.property().name + "=" + it.valueName() }.sorted()
        if (props.isEmpty()) return id
        return id + props.joinToString(",", "[", "]")
    }

    // ---- the report

    private val chatMissing = ArrayList<String>()
    private val chatExtra = ArrayList<String>()
    private val chatNumbers = ArrayList<String>()

    /** ADS's tab counters as its last tab refresh showed them (Hypixel's are its tab's too, a second apart): secrets, crypts, deaths, opened, completed, cleared %. */
    private var tab: IntArray? = null

    /** ADS's tab refreshed now: its counters as the party reads them. */
    fun tabRefreshed() {
        val st = s.run.state
        tab = intArrayOf(st.secrets.teamFound, st.secrets.crypts, st.deaths.teamDeaths, st.counters.opened, st.counters.completed, st.score.clearedPct)
    }

    /** The counters to set against Hypixel's: the last refresh's, else (none yet) the state's now. */
    private fun adsTab(): IntArray = tab ?: s.run.state.let { st -> intArrayOf(st.secrets.teamFound, st.secrets.crypts, st.deaths.teamDeaths, st.counters.opened, st.counters.completed, st.score.clearedPct) }

    fun write() {
        val out = ArrayList<String>()
        val run = s.run
        out += "ADS replay report: ${s.file.id}  (written at run tick ${run.t})"
        out += "Ended: $endWhy at k $endK (${if (complete) "the whole recording" else "CUT SHORT: the recording goes to k $lastK, boss at ${bossK ?: "-"}"})"
        if (kicks.isNotEmpty()) { out += "Kicks ADS's server would have made (the replay went on):"; out += kicks }
        out += ""
        out += "== Score parts at the end: Hypixel (Odin) vs ADS"
        val a = adsTab()
        lastDung?.let { d ->
            fun g(k: String) = d.get(k)?.takeIf { it.isJsonPrimitive }?.asString ?: "?"
            out += "  secrets ${g("secretsFound")} / ${a[0]}   crypts ${g("crypts")} / ${a[1]}   deaths ${g("deaths")} / ${a[2]}"
            out += "  rooms opened ${g("openedRooms")} / ${a[3]}   completed ${g("completedRooms")} / ${a[4]}   cleared ${g("percentCleared")}% / ${a[5]}%"
            out += "  bat ${g("bat")} / ${run.state.secrets.batKilled}   mimic ${g("mimic")} / ${run.state.secrets.mimicKilled}   prince ${g("prince")} / ${run.state.secrets.princeKilled}"
        }
        out += ""
        out += "== Mob HP after a WITHER door: $hpScaleOk as recorded, ${hpScaleOff.size} not"
        out += hpScaleOff.map { "  $it" }
        out += "== Watcher camp spawns, ticks after the BLOOD door: Hypixel ${campHypixel.size}, ADS ${campAds.size}"
        out += "  Hypixel ${campHypixel.joinToString(" ")}"
        out += "  ADS     ${campAds.joinToString(" ")}"
        out += "== Mobs: $mobsOk recorded deaths ADS had done itself"
        out += "  Near you, not dead in ADS when the recording's died (${mobsNearYou.size}; the kill was likely yours):"
        out += mobsNearYou.map { "  $it" }
        out += "  Recorded deaths ADS has no such mob for (${mobsMissing.size}):"
        out += mobsMissing.map { "  $it" }
        out += ""
        out += "== Keys"
        out += keyLines.sortedBy { it.trim().substringBefore(' ').toIntOrNull() ?: 0 }
        out += ""
        out += "== Teleports of you: Hypixel ${hypTps.size}, ADS ${adsTps.size} (and $resyncs of Hypixel's resyncs, to where you stood)"
        val used = HashSet<Int>()
        val delays = ArrayList<Int>()
        for ((k, at) in hypTps) {
            val i = adsTps.indices.firstOrNull { it !in used && Math.abs(adsTps[it].first - k) <= 12 && adsTps[it].second.distanceTo(at) < 1.5 }
            if (i != null) { used += i; delays += k - adsTps[i].first } else out += "%6d  Hypixel put you at %s; ADS did not".format(k, ReplaySession.fmt(at.x, at.y, at.z)) +
                (tpWay[hypTps.indexOfFirst { it.first == k && it.second == at }]?.let { "  ($it)" } ?: "")
        }
        for (i in adsTps.indices) if (i !in used) adsTps[i].let { (k, at) -> out += "%6d  ADS put you at %s; Hypixel did not".format(k, ReplaySession.fmt(at.x, at.y, at.z)) }
        if (delays.isNotEmpty()) out += "  ${delays.size} matched; Hypixel's came ${delays.average().let { "%.1f".format(it) }} ticks after ADS's on average (min ${delays.min()}, max ${delays.max()})"
        out += ""
        out += "== Your moves: $moves handed in, $movesOff ended away from where you sent (> 0.3 blocks)"
        out += moveExamples
        out += ""
        out += "== Teammate and room work ADS had to be made to do (${forced.size})"
        out += forced
        out += ""
        out += "== Rooms: recorded mark (k) vs ADS"
        for ((room, marks) in recRooms) {
            val r = run.state.room(room) ?: continue
            val ads = mapOf("NONE" to r.tDiscovered, "WHITE" to r.tWhite, "GREEN" to r.tGreen, "RED" to r.tRed, "FAILED" to r.tRed)
            val parts = marks.filterKeys { it != "UNDISCOVERED" }.map { (m, k) -> "$m rec $k / ADS ${ads[m] ?: "never"}" }
            if (parts.isNotEmpty()) out += "  ${roomNames[room] ?: room}: ${parts.joinToString("; ")}  (ADS now ${r.check})"
        }
        for (r in run.state.rooms) if (r.check == CheckMark.GREEN || r.check == CheckMark.WHITE) if (r.index !in recRooms.keys) out += "  ${r.plan.name}: ADS ${r.check}, never marked in the recording"
        out += ""
        out += "== Blocks: $blocksChecked of Hypixel's block changes checked $BLOCK_LAG ticks later against Hypixel's cell then (±$BLOCK_TOLERANCE) ($blocksUnloaded not loaded in ADS); $blocksForced differed and were set as Hypixel had them"
        for ((cat, m) in blockMiss) {
            out += "  -- ${mapOf("start" to "Hypixel's start-of-run updates (k 0-1)", "you" to "within $NEAR blocks of you", "away" to "away from you (teammates, or the room by itself)")[cat]}: ${m.values.sumOf { it.size }}"
            for ((key, l) in m.entries.sortedByDescending { it.value.size }) { out += "  ${l.size}x $key"; l.take(3).forEach { out += "      $it" } }
        }
        out += ""
        val ads = adsChatQ.toList()
        out += "== Chat Hypixel sent that ADS did not (within $CHAT_WINDOW ticks)"
        val norm = { t: String -> NUM.replace(t, "#") }
        val adsBy = ads.groupBy { norm(it.second) }
        val recBy = recChat.groupBy { norm(it.second) }
        for ((k, t) in recChat) {
            if (IGNORE_CHAT.any { it.containsMatchIn(t) }) continue
            val got = adsBy[norm(t)]?.any { Math.abs(it.first - k) <= CHAT_WINDOW } == true
            if (!got) { out += "%6d  %s".format(k, t.take(160)); chatMissing += norm(t) }
        }
        out += ""
        out += "== The same line with other numbers (Hypixel | ADS)"
        for ((k, t) in recChat) {
            if (IGNORE_CHAT.any { it.containsMatchIn(t) }) continue
            val same = adsBy[norm(t)]?.filter { Math.abs(it.first - k) <= CHAT_WINDOW } ?: continue
            if (same.isEmpty() || same.any { it.second == t }) continue
            val a = same.minBy { Math.abs(it.first - k) }
            out += "%6d  %s | %+d  %s".format(k, t.take(110), a.first - k, a.second.take(110))
            chatNumbers += "$t | ${a.second}"
        }
        out += ""
        out += "== Chat ADS sent that Hypixel did not"
        for ((k, t) in ads) {
            val got = recBy[norm(t)]?.any { Math.abs(it.first - k) <= CHAT_WINDOW } == true
            if (!got && !t.startsWith("[ADS]")) { out += "%6d  %s".format(k, t.take(160)); chatExtra += norm(t) }
        }
        out += ""
        out += "== Notes"
        out += lines
        out += ""
        out += "== Your clicks as ADS took them"
        out += actions
        val j = JsonObject()
        j.addProperty("end", endWhy); j.addProperty("endK", endK); bossK?.let { j.addProperty("bossK", it) }; j.addProperty("lastK", lastK); j.addProperty("complete", complete)
        j.add("cells", cells)
        j.add("setbacks", setbacks); j.addProperty("resyncs", resyncs)
        j.add("designs", JsonArray().apply { s.run.plan.rooms.mapNotNull { it.design?.id }.distinct().forEach { add(it) } })
        j.add("kicks", JsonArray().apply { kicks.forEach { add(it) } })
        j.addProperty("id", s.file.id); j.addProperty("cls", s.file.self.cls); j.addProperty("t", run.t)
        lastDung?.let { j.add("hypixel", it) }
        j.add("ads", JsonObject().apply {
            addProperty("secretsFound", a[0]); addProperty("crypts", a[1]); addProperty("deaths", a[2])
            addProperty("openedRooms", a[3]); addProperty("completedRooms", a[4]); addProperty("percentCleared", a[5])
        })
        j.addProperty("hpScaleOk", hpScaleOk); j.addProperty("hpScaleOff", hpScaleOff.size); j.addProperty("mobsOk", mobsOk); j.addProperty("mobsNearYou", mobsNearYou.size); j.addProperty("mobsMissing", mobsMissing.size)
        j.addProperty("tpHypixel", hypTps.size); j.addProperty("tpAds", adsTps.size); j.addProperty("tpMatched", delays.size); j.addProperty("tpDelay", if (delays.isEmpty()) 0.0 else delays.average())
        j.addProperty("forced", forced.size)
        j.add("campHypixel", JsonArray().apply { campHypixel.forEach { add(it) } }); j.add("campAds", JsonArray().apply { campAds.forEach { add(it) } })
        j.add("blocks", JsonObject().apply { for ((cat, m) in blockMiss) add(cat, JsonObject().apply { for ((key, l) in m) addProperty(key, l.size) }) })
        j.add("chatMissing", com.google.gson.JsonArray().apply { chatMissing.forEach { add(it) } })
        j.add("chatExtra", com.google.gson.JsonArray().apply { chatExtra.forEach { add(it) } })
        j.add("chatNumbers", com.google.gson.JsonArray().apply { chatNumbers.forEach { add(it) } })
        try {
            Files.createDirectories(ReplayFile.dir)
            Files.writeString(ReplayFile.dir.resolve("${s.file.id}.report.json"), j.toString())
            Files.writeString(ReplayFile.dir.resolve("${s.file.id}.blocks.txt"), blockMiss.flatMap { (cat, m) -> m.flatMap { (key, l) -> l.map { "$cat | $key | $it" } } }.joinToString("\n"))
            Files.write(ReplayFile.dir.resolve("${s.file.id}.report.txt"), out)
            Sim.note("Replay report: config/engineerclient/ads/replays/${s.file.id}.report.txt")
        } catch (t: Throwable) { Sim.note("Replay report could not be written: $t") }
    }

    companion object {
        const val BLOCK_LAG = 10
        const val NEAR = 12.0
        const val BLOCK_TOLERANCE = 3
        const val CHAT_WINDOW = 40
        private val NUM = Regex("[0-9][0-9,.]*")
        /** Lines that are about the recording's account, Hypixel's server or chat, not the run. */
        private val IGNORE_CHAT = listOf(Regex("^Party >"), Regex("^Guild >"), Regex("^From "), Regex("^To "), Regex("profile"), Regex("Profile ID"), Regex("FIRE SALE"), Regex("^\\[Sacks]"), Regex("Autopet"), Regex("RARE DROP"), Regex("^Co-op >"))
    }
}
