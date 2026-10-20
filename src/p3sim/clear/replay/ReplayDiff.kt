package com.engineerclient.p3sim.clear.replay

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.run.CheckMark
import com.engineerclient.p3sim.run.RunBlocks
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.state.BlockState
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * What a replay found ADS doing unlike the recording, written to `replays/<id>.report.txt` when it ends: every teammate action ADS had to
 * be made to do (and whether it could), your moves ADS did not take, targets it had no entity for, blocks it has unlike Hypixel's after
 * Hypixel changed them, chat Hypixel sent and ADS did not (and the other way), and the rooms' marks and when they came. Server thread,
 * but for [adsChat] (the client thread).
 */
class ReplayDiff(private val s: ReplaySession) {
    private val lines = ArrayList<String>()
    private val forced = ArrayList<String>()

    fun note(k: Int, kind: String, text: String) { lines += "%6d  %-7s %s".format(k, kind, text) }

    fun forced(k: Int, what: String, result: String?) { forced += "%6d  %s -> %s".format(k, what, result ?: "?") }

    // ---- moves

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

    private val teleports = ArrayList<String>()
    fun recordedTeleport(k: Int, e: JsonArray) {
        val a = e.get(2).takeIf { it.isJsonArray }?.asJsonArray ?: return
        teleports += "%6d  Hypixel put you at %s".format(k, ReplaySession.fmt(a[0].asDouble, a[1].asDouble, a[2].asDouble))
    }

    fun missingTarget(k: Int, what: String, b: JsonObject) {
        note(k, "target", "$what on ${b.get("type")?.asString} \"${b.get("tag")?.takeIf { it.isJsonPrimitive }?.asString ?: b.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: ""}\" at ${b.get("x")} ${b.get("y")} ${b.get("z")}: ADS has nothing there")
    }

    // ---- blocks: what Hypixel changed a block to, checked in ADS a moment later

    private class BlockCheck(val due: Int, val k: Int, val pos: BlockPos, val want: String)
    private val blockChecks = ArrayDeque<BlockCheck>()
    private val blockMiss = HashMap<String, MutableList<String>>()
    private var blocksChecked = 0
    private var blocksUnloaded = 0

    fun recordedBlock(k: Int, x: Int, y: Int, z: Int, state: String?) {
        if (state == null) return
        blockChecks.addLast(BlockCheck(k + BLOCK_LAG, k, BlockPos(x, y, z), state))
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

    fun tick(now: Int) {
        nowK = now
        while (blockChecks.isNotEmpty() && blockChecks.first().due <= now) {
            val c = blockChecks.removeFirst()
            if (!RunBlocks.isLoaded(c.pos.x, c.pos.z)) { blocksUnloaded++; continue }
            blocksChecked++
            val have = str(Sim.level.getBlockState(c.pos))
            if (have == c.want) continue
            val wantId = c.want.substringBefore('['); val haveId = have.substringBefore('[')
            val key = if (wantId == haveId) "$wantId: state differs" else "Hypixel $wantId, ADS $haveId"
            val l = blockMiss.getOrPut(key) { ArrayList() }
            if (l.size < 6) l += "k ${c.k} at ${c.pos.x} ${c.pos.y} ${c.pos.z}: Hypixel ${c.want}, ADS $have"
            else l += ""
        }
    }

    private fun str(b: BlockState): String {
        val id = BuiltInRegistries.BLOCK.getKey(b.block).toString()
        val props = b.values.toList().map { it.property().name + "=" + it.valueName() }.sorted()
        if (props.isEmpty()) return id
        return id + props.joinToString(",", "[", "]")
    }

    // ---- the report

    fun write() {
        val out = ArrayList<String>()
        val run = s.run
        out += "ADS replay report: ${s.file.id}  (written at run tick ${run.t})"
        out += ""
        out += "== Teammate actions ADS had to be made to do (${forced.size})"
        out += forced
        out += ""
        out += "== Your moves: $moves handed in, $movesOff ended away from where you sent (> 0.3 blocks)"
        out += moveExamples
        out += ""
        out += "== Hypixel's teleports of you (${teleports.size}; ADS's own should match these)"
        out += teleports.take(60)
        out += ""
        out += "== Rooms: recorded mark (k) vs ADS"
        for ((room, marks) in recRooms) {
            val r = run.state.room(room) ?: continue
            val ads = mapOf("NONE" to r.tDiscovered, "WHITE" to r.tWhite, "GREEN" to r.tGreen, "FAILED" to r.tRed)
            val parts = marks.filterKeys { it != "UNDISCOVERED" }.map { (m, k) -> "$m rec $k / ADS ${ads[m] ?: "never"}" }
            if (parts.isNotEmpty()) out += "  ${roomNames[room] ?: room}: ${parts.joinToString("; ")}  (ADS now ${r.check})"
        }
        for (r in run.state.rooms) if (r.check == CheckMark.GREEN || r.check == CheckMark.WHITE) if (r.index !in recRooms.keys) out += "  ${r.plan.name}: ADS ${r.check}, never marked in the recording"
        out += ""
        out += "== Blocks: $blocksChecked of Hypixel's block changes checked ${BLOCK_LAG} ticks later ($blocksUnloaded not loaded in ADS); ${blockMiss.values.sumOf { it.size }} differ"
        for ((key, l) in blockMiss.entries.sortedByDescending { it.value.size }) {
            out += "  ${l.size}x $key"
            l.filter { it.isNotEmpty() }.forEach { out += "      $it" }
        }
        out += ""
        val ads = adsChatQ.toList()
        out += "== Chat Hypixel sent that ADS did not (within ${CHAT_WINDOW} ticks)"
        val norm = { t: String -> NUM.replace(t, "#") }
        val adsBy = ads.groupBy { norm(it.second) }
        val recBy = recChat.groupBy { norm(it.second) }
        for ((k, t) in recChat) {
            if (IGNORE_CHAT.any { it.containsMatchIn(t) }) continue
            val got = adsBy[norm(t)]?.any { Math.abs(it.first - k) <= CHAT_WINDOW } == true
            if (!got) out += "%6d  %s".format(k, t.take(160))
        }
        out += ""
        out += "== Chat ADS sent that Hypixel did not"
        for ((k, t) in ads) {
            val got = recBy[norm(t)]?.any { Math.abs(it.first - k) <= CHAT_WINDOW } == true
            if (!got && !t.startsWith("[ADS]")) out += "%6d  %s".format(k, t.take(160))
        }
        out += ""
        out += "== Notes"
        out += lines
        try {
            Files.createDirectories(ReplayFile.dir)
            Files.write(ReplayFile.dir.resolve("${s.file.id}.report.txt"), out)
            Sim.note("Replay report: config/engineerclient/ads/replays/${s.file.id}.report.txt (${forced.size} forced, ${blockMiss.values.sumOf { it.size }} blocks differ)")
        } catch (t: Throwable) { Sim.note("Replay report could not be written: $t") }
    }

    companion object {
        const val BLOCK_LAG = 10
        const val CHAT_WINDOW = 40
        private val NUM = Regex("[0-9][0-9,.]*")
        /** Lines that are about the recording's account, Hypixel's server or chat, not the run. */
        private val IGNORE_CHAT = listOf(Regex("^Party >"), Regex("^Guild >"), Regex("^From "), Regex("^To "), Regex("profile"), Regex("Profile ID"), Regex("FIRE SALE"), Regex("^\\[Sacks]"), Regex("Autopet"), Regex("RARE DROP"), Regex("^Co-op >"))
    }
}
