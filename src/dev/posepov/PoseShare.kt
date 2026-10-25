package com.devgineerclient.posepov

import com.devgineerclient.DevgineerClient
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalTypes
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.Mth
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

/**
 * Pose sharing with the party, for Pose POV: through undonecoffee.com's relay (adr/src/pose.js), one room per Hypixel server.
 * Every game in a dungeon takes part (whether or not it has Pose POV on), but sends only while a party member is looking
 * (the leap menu open, or a leap in hand, ready for it): where you stand and look (and what you hold, and your swings),
 * the same for each teammate you can see who doesn't share for themselves (so one out of someone's range still shows if
 * you can see them), and Odin's solution to the terminal you have open. The relay passes it only to party members who
 * list you as you list them, by name and by the entity id Hypixel gave each in this run: never on screen and known only
 * to games in the run, so neither a stream of it nor knowing the server lets anyone pose as a teammate. No Mojang, no
 * accounts, nothing about you but that.
 *
 * What arrives is never trusted past what it can show: names must be your own teammates, items are looked up by id (no
 * names, no components), and a terminal is only its type and the cells to click, inside that terminal's own grid and
 * no more of them than it can have; nothing a sender writes is ever shown as text.
 */
object PoseShare {
    private const val SITE = "undonecoffee.com"
    private const val TICK_NS = 50_000_000L
    /** How far behind the newest pose a shared player is drawn (to ride out uneven arrival): each track's own, from how often
     *  and how evenly its poses come, within these. */
    private const val BUFFER_MIN_NS = 6_000_000L
    private const val BUFFER_MAX_NS = 90_000_000L
    /** Peer-to-peer frames: one a frame at most, as often as this (the "Peer-to-peer FPS" setting; 60 a second), each with the frame's look rather than the tick's. */
    @Volatile var frameNs = 16_666_666L
    private const val EXTRAPOLATE_NS = 100_000_000L
    /** How far past a peer's newest tick its position is carried on by that tick's move. */
    private const val CARRY_NS = 60_000_000L
    /**
     * Peer-to-peer kinds ([PeerLink]'s header): the relay's own numbers for the frames both carry (3 terminal, 4 closed, 7 and 8
     * PartyProgress), and two of its own. [D_TICK]: [time u16, ms][x y z: 24-bit][yaw u16][pitch i16][flags]([item u16] when
     * flag 0x80), a tick at a time (where you are only changes then). [D_LOOK]: [time u16][yaw u16][pitch i16], every frame the
     * look changes in.
     */
    private const val D_TICK = 9
    private const val D_LOOK = 10
    /** A shared view older than this isn't shown: an old frame would pass for where they are now. */
    private const val STALE_NS = 300_000_000L
    private const val GUI_STALE_NS = 3_000_000_000L
    /** While still, your pose is sent this often rather than every tick (well inside [STALE_NS]). */
    private const val STILL_NS = 100_000_000L
    private val LOBBY = Regex("[A-Za-z0-9]{1,24}")
    private val STARTS_WITH = Regex("^What starts with: '([A-Za-z])'\\?$")
    private val SELECT = Regex("^Select all the ([\\w ]+) items!$")

    private val mc get() = Minecraft.getInstance()
    private val http: HttpClient = HttpClient.newHttpClient()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    /** Whether a party member linked with you is watching: only then is anything sent. */
    @Volatile private var wanted = false
    private var sending: CompletableFuture<*> = CompletableFuture.completedFuture(null)
    @Volatile private var connecting = false
    private var room: String? = null
    @Volatile private var retryAt = 0L
    private var backoff = 2_000L
    private var seq = 0
    @Volatile private var sentParty: List<String> = emptyList()
    private var sentEntries: List<String> = emptyList()
    @Volatile private var partyAt = 0L
    private var lastPing = 0L
    private var wasSwinging = false
    private var guiFrame: ByteArray? = null
    private var guiAt = 0L
    private var sentWatch = false
    private var watchAt = 0L
    private var lastSig: ByteArray? = null
    private var prevFrame: ByteArray? = null
    private var prevSent = false
    private var selfAt = 0L
    // Peer-to-peer: the last tick's body and whether it was a change (a change is followed by one more, the settled pose, so
    // nobody carries on a move that has stopped), when it last went, and the item last sent with one.
    private var lastTick: ByteArray? = null
    private var tickChanged = false
    private var tickSentAt = 0L
    private var tickItem = -1
    private var tickItemAt = 0L
    // [frame]'s own: when it last ran, its last look and whether that was a change.
    private var frameAt = 0L
    private var lastLook = -1L
    private var lookChanged = false
    private var wasWanted = false


    /** Whether you're looking at your teammates' views, or about to (set by [PosePov] each tick). */
    @Volatile var watching = false
    /** "Peer-to-peer sharing": straight to teammates who have it on too, over UDP (see [PeerLink]); set by [PosePov]. */
    @Volatile var p2p = false
    @Volatile private var mySlot = 0
    @Volatile private var v6 = false
    /** Frame kinds past the pose relay's own that it says it passes on (its sign-in's "x"); [shareExtra]'s go peer-to-peer only otherwise. */
    @Volatile private var relayExtras: Set<Int> = emptySet()
    private var sentPort = 0
    private var portAt = 0L

    /** slot -> name (lower case) of the members linked with you, and the relay's subject ids. */
    private val roster = ConcurrentHashMap<Int, String>()
    private val subjects = ConcurrentHashMap<Int, String>()
    /** Your teammates' names (lower case), as of the last tick: what arrives must be about one of them. */
    @Volatile private var teammates: Set<String> = emptySet()
    private val pings = ConcurrentHashMap<Int, Long>()
    /** Your round trip to the relay, smoothed (ms); -1 until measured. */
    @Volatile var rttMs = -1.0; private set

    /** How many teammates are sharing with you right now, and whether you're connected (for the leap menu's dot). */
    val sharingCount get() = roster.size
    val connected get() = ready

    // ------------------------------------------------------------------ what's been heard

    class Sample(val seq: Long, val time: Long, val arrived: Long, val pos: Vec3, val yaw: Float, val pitch: Float, val flags: Int, val item: Int, val delayMs: Int, val direct: Boolean)

    class Track {
        val samples = ArrayDeque<Sample>()
        /** The smallest (arrival - send tick time) seen: arrival at the best latency so far; a sample's time is its tick plus this. */
        var offset = Long.MAX_VALUE
        var lastSeq = -1L
        /**
         * Its poses come peer-to-peer, timed in ms: [samples] are then looks only (from every frame and tick), and [ticks] the
         * positions (time, where), with the held item and sneaking from the newest tick.
         */
        var timed = false
        val ticks = ArrayDeque<Pair<Long, Vec3>>()
        var item = 0
        var sneaking = false
        var lastArrived = 0L
        /** Smoothed: how late a pose comes past the best so far, and the gap between poses while they flow (ns). */
        var jitter = 3_000_000.0
        var gap = TICK_NS.toDouble()
         var buffer = 70_000_000L
        var source = 0
        var via: String? = null
        var swingAt = 0L
        @Volatile var gui: Gui? = null
        fun at(s: Sample) = s.time + offset
    }

    /**
     * An open terminal as its holder sees it: Odin's type, the letter (Starts With) or colour (Select), and Odin's solution
     * (slot numbers, in order; Rubix repeats a slot once per click).
     */
    class Gui(val type: TerminalTypes, val extra: Int, val solution: IntArray, val at: Long)

    /** Each terminal's grid of clickable cells (as Odin lays them out), and how many cells of it a solution may light. */
    class Grid(val rows: Int, val cols: Int, val startRow: Int, val startCol: Int, val maxCells: Int) {
        operator fun contains(slot: Int): Boolean { val r = slot / 9 - startRow; val c = slot % 9 - startCol; return r in 0 until rows && c in 0 until cols }
    }

    val GRIDS = mapOf(
        TerminalTypes.PANES to Grid(3, 5, 1, 2, 15),
        TerminalTypes.RUBIX to Grid(3, 3, 1, 3, 9),
        TerminalTypes.NUMBERS to Grid(2, 7, 1, 1, 14),
        // Few items ever match; a cap keeps a grid of lit cells from being drawn into a word.
        TerminalTypes.STARTS_WITH to Grid(3, 7, 1, 1, 10),
        TerminalTypes.SELECT to Grid(4, 7, 1, 1, 14),
        TerminalTypes.MELODY to Grid(5, 9, 0, 0, 3),
    )

    private val tracks = ConcurrentHashMap<String, Track>()

    /** A shared teammate's pose for drawing now: [via] is who relayed it (null when they share it themselves). */
    class Pose(val pos: Vec3, val yaw: Float, val pitch: Float, val item: ItemStack, val swingAt: Long, val sneaking: Boolean, val via: String?, val direct: Boolean, val delayMs: Int, val bufferNs: Long)

    fun pose(name: String, now: Long): Pose? {
        val t = tracks[name.lowercase()] ?: return null
        synchronized(t) {
            val last = t.samples.lastOrNull() ?: return null
            if (now - last.arrived > STALE_NS) return null
            val at = now - t.buffer
            // The two poses either side of the drawing time, or the newest two to run on from (for a little while).
            var a = t.samples.first(); var b = last
            for (s in t.samples) { if (t.at(s) <= at) a = s else { b = s; break } }
            var pos: Vec3; val yaw: Float; val pitch: Float
            if (a === b || t.at(b) == t.at(a)) { pos = b.pos; yaw = b.yaw; pitch = b.pitch }
            else {
                // A look is never run on past the newest (a turn that stopped would swing on); a tick-at-a-time pose is.
                val k = ((minOf(at, t.at(b) + EXTRAPOLATE_NS) - t.at(a)).toDouble() / (t.at(b) - t.at(a))).coerceIn(0.0, if (t.timed) 1.0 else Double.MAX_VALUE)
                pos = a.pos.lerp(b.pos, k)
                yaw = a.yaw + Mth.wrapDegrees(b.yaw - a.yaw) * k.toFloat()
                pitch = Mth.lerp(k.toFloat().coerceAtMost(1f), a.pitch, b.pitch)
            }
            var itemId = b.item; var sneaking = b.flags and 2 != 0
            if (t.timed) {
                // Where they are: between the two ticks either side, or past the newest by its move (as their game would have it).
                val p1 = t.ticks.lastOrNull() ?: return null
                val p0 = t.ticks.lastOrNull { it.first < p1.first }
                val t1 = p1.first + t.offset
                var lo: Pair<Long, Vec3>? = null; var hi = p1
                for (p in t.ticks) { if (p.first + t.offset <= at) lo = p else { hi = p; break } }
                pos = when {
                    lo == null -> t.ticks.first().second
                    at < t1 && hi !== lo -> lo.second.lerp(hi.second, ((at - lo.first - t.offset).toDouble() / (hi.first - lo.first)).coerceIn(0.0, 1.0))
                    p0 == null -> p1.second
                    else -> p0.second.lerp(p1.second, 1.0 + (minOf(at - t1, CARRY_NS)).coerceAtLeast(0L).toDouble() / (p1.first - p0.first))
                }
                itemId = t.item; sneaking = t.sneaking
            }
            val item = BuiltInRegistries.ITEM.byId(itemId).takeIf { itemId != 0 && it !== Items.AIR }?.let { ItemStack(it) } ?: ItemStack.EMPTY
            val delay = (t.buffer / 1_000_000).toInt() + b.delayMs + (if (!b.direct && rttMs > 0) (rttMs / 2).toInt() else 0)
            return Pose(pos, yaw, pitch, item, t.swingAt, sneaking, t.via, b.direct, delay, t.buffer)
        }
    }

    fun gui(name: String, now: Long): Gui? = tracks[name.lowercase()]?.gui?.takeIf { now - it.at < GUI_STALE_NS }

    // ------------------------------------------------------------------ every tick

    /** Connects while in a dungeon, and sends this tick's poses and terminal while someone is watching. */
    fun tick() {
        val lobby = LocationUtils.lobbyId?.takeIf { DungeonUtils.inDungeons && LOBBY.matches(it) }
        val me = mc.player
        teammates = DungeonUtils.dungeonTeammates.mapTo(HashSet()) { it.name.lowercase() }
        ids = idsNow()
        if (lobby == null || me == null) { if (room != null || socket != null) close(); return }
        if (lobby != room) { close(); room = lobby }
        if (socket == null) {
            if (!connecting && System.currentTimeMillis() >= retryAt) connect(lobby)
            return
        }
        if (!ready) return
        seq++
        val nowMs = System.currentTimeMillis()
        val now = System.nanoTime()

        // The party, whenever it changes (the relay takes one every 2 s).
        val party = partyNow()
        if (party.map { it.second } != sentEntries && nowMs - partyAt > 2100) {
            sendText(JsonObject().apply { addProperty("t", "p"); add("p", JsonArray().apply { party.forEach { add(it.second) } }) }.toString())
            sentParty = party.map { it.first }; sentEntries = party.map { it.second }; partyAt = nowMs
        }

        // Whether you're watching: on at once, off only after a moment (so flicking the menu doesn't spam it).
        if (watching) watchAt = nowMs
        val watch = watching || nowMs - watchAt < 1500
        if (watch != sentWatch) { sentWatch = watch; sendBinary(byteArrayOf(5, if (watch) 1 else 0)) }

        // Peer-to-peer: the socket while it's on, and our public port to the relay whenever it changes (0: off).
        if (p2p && !PeerLink.running) PeerLink.start(mySlot, v6)
        if (!p2p && PeerLink.running) PeerLink.stop()
        PeerLink.tick { reportPort(it) }
        if (!p2p) reportPort(0)

        // A ping every 2 s while poses flow (the round trip, for the delays shown), otherwise only to keep the socket open.
        if (nowMs - lastPing > (if (wanted || watch) 2000 else 30_000)) {
            lastPing = nowMs
            val nonce = (nowMs and 0x7FFFFFFF).toInt()
            pings[nonce] = now
            sendBinary(ByteBuffer.allocate(5).put(0).putInt(nonce).array())
        }

        val swinging = me.isSwinging
        val swung = swinging && !wasSwinging
        wasSwinging = swinging
        // The rooms this game has named, for teammates (their views need the rooms where they are, which their games may not
        // have reached): when they change or someone new links. Whether or not anyone is watching.
        if (seq % 10 == 0) shareRooms(nowMs)

        if (!wanted) { wasWanted = false; if (guiFrame != null) { guiFrame = null; share(byteArrayOf(4)) }; return }
        if (!wasWanted) { wasWanted = true; selfAt = 0L; guiAt = 0L }

        // Yourself: every tick while moving, every [STILL_NS] while still, and the still pose again just before moving off
        // (so the move starts from where it did, not drawn out across the gap).
        var flags = if (swung) 1 else 0
        if (me.isShiftKeyDown) flags = flags or 2
        val frame = pose(1, -1, 0, me.x, me.y, me.z, me.yRot, me.xRot, flags, me.mainHandItem)
        val sig = frame.copyOfRange(3, 16) + frame.copyOfRange(17, 20)
        val changed = !sig.contentEquals(lastSig)
        // Peer-to-peer has its own (below); the relay is for whoever that didn't reach.
        val direct = if (p2p) tickDirect(me, flags, now) else emptySet()
        val relay = direct.isEmpty() || !direct.containsAll(roster.keys)
        if (changed && !prevSent && relay) prevFrame?.let { sendBinary(it) }
        prevSent = changed || now - selfAt > STILL_NS
        if (prevSent) { if (relay) sendBinary(frame); selfAt = now }
        prevFrame = frame; lastSig = sig

        // Teammates you can see who don't share for themselves (each tagged by where they are in the party you sent).
        val sharing = roster.values.toSet()
        val level = mc.level
        if (level != null) for ((i, name) in sentParty.withIndex()) {
            if (name in sharing) continue
            val p = level.players().firstOrNull { it.name.string.equals(name, ignoreCase = true) } ?: continue
            val f = if (PosePov.swungSince(p.id, now - TICK_NS)) 1 else 0
            sendBinary(pose(2, i, 0, p.x, p.y, p.z, p.yHeadRot, p.xRot, f, p.mainHandItem))
        }

        // The terminal you have open: its type and Odin's solution only, when it changes (and every second, for whoever
        // started watching since); closed once.
        val term = TerminalUtils.currentTerm
        val grid = term?.let { GRIDS[it.type] }
        if (term != null && grid != null && me.containerMenu !== me.inventoryMenu) {
            val title = mc.gui.screen()?.title?.string ?: ""
            val extra = when (term.type) {
                TerminalTypes.STARTS_WITH -> STARTS_WITH.find(title)?.groupValues?.get(1)?.uppercase()?.get(0)?.minus('A') ?: 0
                TerminalTypes.SELECT -> SELECT.find(title)?.groupValues?.get(1)?.replace("SILVER", "LIGHT GRAY", ignoreCase = true)
                    ?.let { c -> DyeColor.entries.find { it.name.replace("_", " ").equals(c, true) }?.ordinal } ?: 0
                else -> 0
            }
            val sol = solutionFor(term.type, grid, term.solution)
            val f = ByteArray(4 + sol.size)
            f[0] = 3; f[1] = term.type.ordinal.toByte(); f[2] = extra.toByte(); f[3] = sol.size.toByte()
            sol.forEachIndexed { i, s -> f[4 + i] = s.toByte() }
            if (!f.contentEquals(guiFrame) || nowMs - guiAt > 1000) { guiFrame = f; guiAt = nowMs; share(f) }
        } else if (guiFrame != null) { guiFrame = null; share(byteArrayOf(4)) }
    }

    /**
     * Every frame (from the GUI's start, so with the HUD hidden too), peer-to-peer only: your look ([D_LOOK]) to each live
     * peer when it changed, timed in ms, [frameNs] apart at most. A tick pose holds the look still for 50 ms, so a teammate
     * drawing it has to stay a whole tick behind; these let them draw only a few ms behind. Where you are only changes a tick at
     * a time, so that goes with the ticks ([D_TICK]), and they carry it on by its move between them.
     */
    fun frameSafely() = DevgineerClient.safely("pose share frame") { frame() }

    fun frame() {
        if (!p2p || !ready || !wanted || !PeerLink.running) return
        val me = mc.player ?: return
        val now = System.nanoTime()
        if (now - frameAt < frameNs - 1_000_000L) return
        frameAt = now
        val yaw = (Mth.wrapDegrees(me.yRot) / 360f * 65536f).toInt() and 0xFFFF
        val pitch = (me.xRot.coerceIn(-90f, 90f) / 90f * 32767f).toInt() and 0xFFFF
        val look = (yaw.toLong() shl 16) or pitch.toLong()
        val changed = look != lastLook
        // The look before a turn, again, with its own time: otherwise the turn is drawn spread across the still stretch before it.
        if (changed && !lookChanged && lastLook >= 0) PeerLink.broadcast(D_LOOK, lookBody(lastLookMs, lastLook))
        if (changed) PeerLink.broadcast(D_LOOK, lookBody(msNow(), look))
        lookChanged = changed
        lastLook = look; lastLookMs = msNow()
    }

    private var lastLookMs = 0
    private fun msNow() = (System.nanoTime() / 1_000_000).toInt() and 0xFFFF
    private fun lookBody(ms: Int, look: Long) =
        byteArrayOf((ms shr 8).toByte(), ms.toByte(), (look shr 24).toByte(), (look shr 16).toByte(), (look shr 8).toByte(), look.toByte())

    /**
     * This tick's [D_TICK] to each live peer: when it changed, once more when it stops changing (the settled pose), every
     * [STILL_NS] otherwise, and the pose before a change, again, first. The held item rides along when it changes, and once a
     * second. The peers it reached.
     */
    private fun tickDirect(me: net.minecraft.client.player.LocalPlayer, flags: Int, now: Long): Set<Int> {
        if (!PeerLink.running) return emptySet()
        val item = if (me.mainHandItem.isEmpty) 0 else BuiltInRegistries.ITEM.getId(me.mainHandItem.item)
        val withItem = item != tickItem || now - tickItemAt > 1_000_000_000L
        val b = ByteBuffer.allocate(if (withItem) 18 else 16)
        b.putShort(msNow().toShort())
        put24(b, me.x); put24(b, me.y); put24(b, me.z)
        b.putShort((Mth.wrapDegrees(me.yRot) / 360f * 65536f).toInt().toShort())
        b.putShort((me.xRot.coerceIn(-90f, 90f) / 90f * 32767f).toInt().toShort())
        b.put((flags or (if (withItem) 0x80 else 0)).toByte())
        if (withItem) b.putShort(item.toShort())
        val body = b.array()
        // Changed: where, which way, sneaking or swinging (not the time, not the item).
        val changed = lastTick?.let { !body.copyOfRange(2, 15).contentEquals(it.copyOfRange(2, 15)) || (flags and 3) != 0 } ?: true
        val prev = lastTick
        lastTick = body
        val send = changed || tickChanged || now - tickSentAt > STILL_NS
        val wasChanged = tickChanged
        tickChanged = changed
        if (!send) return emptySet()
        if (changed && !wasChanged && prev != null && now - tickSentAt > TICK_NS + 5_000_000L) PeerLink.broadcast(D_TICK, prev)
        tickSentAt = now
        if (withItem) { tickItem = item; tickItemAt = now }
        return PeerLink.broadcast(D_TICK, body)
    }

    /** Your own frame: straight to each live peer, and through the relay unless that reached every linked teammate. */
    private fun share(frame: ByteArray) {
        val direct = if (p2p) PeerLink.broadcast(frame[0].toInt(), frame.copyOfRange(1, frame.size)) else emptySet()
        if (direct.isEmpty() || !direct.containsAll(roster.keys)) sendBinary(frame)
    }

    /**
     * One of [com.devgineerclient.party.PartyProgress]'s frames (kind 7 or 8): to each live peer, and through the relay only once
     * it says it takes that kind (one that doesn't would close the socket on it). Sent whether or not anyone is watching.
     */
    fun shareExtra(frame: ByteArray): Int {
        if (!ready || roster.isEmpty()) return 0
        val direct = if (p2p) PeerLink.broadcast(frame[0].toInt(), frame.copyOfRange(1, frame.size)) else emptySet()
        if (relayTakes(frame[0].toInt() and 255) && !direct.containsAll(roster.keys)) sendBinary(frame)
        return direct.size
    }

    /** Whether the relay passes on frames of [kind] (see [shareExtra]). */
    fun relayTakes(kind: Int) = kind in relayExtras

    /** How many party members are linked with you right now (sharing with you, by the relay or peer-to-peer). */
    val linkedCount get() = roster.size

    private fun reportPort(port: Int) {
        if (port == sentPort || System.currentTimeMillis() - portAt < 3100) return
        sentPort = port; portAt = System.currentTimeMillis()
        sendText(JsonObject().apply { addProperty("t", "e"); addProperty("p", port) }.toString())
    }

    /** The solution as it may be sent: cells of the grid only, each once (Rubix: up to 4 times), within the grid's cap. */
    private fun solutionFor(type: TerminalTypes, grid: Grid, solution: List<Int>): List<Int> {
        val counts = HashMap<Int, Int>()
        val out = ArrayList<Int>()
        for (s in solution) {
            if (s !in grid) continue
            val n = (counts[s] ?: 0) + 1
            if (n > (if (type == TerminalTypes.RUBIX) 4 else 1)) continue
            if (n == 1 && counts.size >= grid.maxCells) continue
            counts[s] = n; out += s
            if (out.size >= 35) break
        }
        return out
    }

    /** A pose frame: [kind]([party index][age])[seq u16][x y z: 24-bit, 1/256 block][yaw u16][pitch i16][flags][half rtt /2 ms][item u16]. */
    private fun pose(kind: Int, index: Int, age: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float, flags: Int, held: ItemStack): ByteArray {
        val b = ByteBuffer.allocate(if (kind == 2) 22 else 20)
        b.put(kind.toByte())
        if (kind == 2) b.put(index.toByte()).put(age.toByte())
        b.putShort(seq.toShort())
        put24(b, x); put24(b, y); put24(b, z)
        b.putShort((Mth.wrapDegrees(yaw) / 360f * 65536f).toInt().toShort())
        b.putShort((pitch.coerceIn(-90f, 90f) / 90f * 32767f).toInt().toShort())
        b.put(flags.toByte())
        b.put((if (rttMs > 0) (rttMs / 4).toInt().coerceIn(0, 255) else 0).toByte())
        b.putShort(if (held.isEmpty) 0 else BuiltInRegistries.ITEM.getId(held.item).toShort())
        return b.array()
    }

    private fun put24(b: ByteBuffer, v: Double) {
        val i = (v * 256).toInt().coerceIn(-0x800000, 0x7FFFFF)
        b.put((i shr 16).toByte()).put((i shr 8).toByte()).put(i.toByte())
    }

    private fun get24(b: ByteBuffer): Double {
        val i = ((b.get().toInt() shl 16) or ((b.get().toInt() and 255) shl 8) or (b.get().toInt() and 255))
        return i / 256.0
    }

    // ------------------------------------------------------------------ the socket

    private val version by lazy {
        net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("engineerclient").map { it.metadata.version.friendlyString }.orElse("dev")
            .replace(Regex("[^\\w.+-]"), "").take(64).ifEmpty { "dev" }
    }

    private fun connect(lobby: String) {
        connecting = true
        // URL-encoded: a dev build's version has a '+', which a query string would read as a space.
        val v = java.net.URLEncoder.encode(version, Charsets.UTF_8)
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create("wss://$SITE/pose/api/$lobby?v=$v"), Listener(lobby))
            .whenComplete { _, err ->
                connecting = false
                if (err != null) { problem("couldn't connect: ${err.cause?.message ?: err.message}"); retryLater() }
            }
    }

    /**
     * The socket is open: it's ours from here, before anything on it is read (the relay's challenge comes at once, and
     * signing in answers it straight away). One for a dungeon left meanwhile is closed again.
     */
    private fun opened(ws: WebSocket, lobby: String) {
        if (room != lobby) { runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }; return }
        sending = CompletableFuture.completedFuture(null)
        ready = false; wanted = false; sentWatch = false; guiFrame = null; lastSig = null; prevFrame = null
        PeerLink.stop(); sentPort = 0
        roster.clear(); subjects.clear(); sentParty = emptyList(); sentEntries = emptyList(); partyAt = 0L
        socket = ws
    }

    private var lastProblem: String? = null

    /** Into the log, once for each different thing that went wrong (a socket that keeps failing the same way says it once). */
    private fun problem(what: String) {
        if (what == lastProblem) return
        lastProblem = what
        DevgineerClient.logger.warn("[pose share] $what")
    }

    /**
     * Each teammate's entity id this run (lower-case name -> id), from when they were last in sight: with the name, it is
     * how the relay knows a player (see adr/src/pose.js). It is never on screen and only games in the run have it, so a
     * stream of the run, or knowing the server, isn't enough to pose as anyone.
     */
    private val eids = HashMap<String, Int>()

    /** "name:entity id" -> name (lower case) of your teammates: what the relay names, matched to who they are here. */
    @Volatile private var ids: Map<String, String> = emptyMap()

    private fun idsNow(): Map<String, String> {
        val level = mc.level ?: return emptyMap()
        val names = DungeonUtils.dungeonTeammates.mapTo(HashSet()) { it.name.lowercase() }
        eids.keys.retainAll(names)
        for (p in level.players()) { val n = p.name.string.lowercase(); if (n in names) eids[n] = p.id }
        return eids.entries.associate { (n, id) -> "$n:$id" to n }
    }

    /** Your teammates but you (name, "name:entity id"), in the order sent: kind 2 frames point into it. */
    private fun partyNow(): List<Pair<String, String>> {
        val me = mc.user.name.lowercase()
        return DungeonUtils.dungeonTeammates.mapNotNull { t ->
            val n = t.name.lowercase()
            if (n == me) null else eids[n]?.let { n to "$n:$it" }
        }.take(6)
    }

    /** The relay's challenge: who we are ("name:entity id") and our party, the same. */
    private fun signIn(ws: WebSocket, @Suppress("UNUSED_PARAMETER") challenge: String) {
        val me = mc.player ?: return
        val party = partyNow()
        sentParty = party.map { it.first }; sentEntries = party.map { it.second }; partyAt = System.currentTimeMillis()
        if (socket === ws) sendText(JsonObject().apply {
            addProperty("t", "a"); addProperty("u", "${me.name.string.lowercase()}:${me.id}")
            add("p", JsonArray().apply { party.forEach { add(it.second) } })
        }.toString())
    }

    private fun retryLater(minMs: Long = 0L) {
        socket = null; ready = false; wanted = false
        // Spread out, so a relay restart doesn't bring every game back in the same instant.
        retryAt = System.currentTimeMillis() + maxOf(backoff, minMs) + (Math.random() * 1000).toLong()
        backoff = (backoff * 2).coerceAtMost(60_000L)
    }

    fun close() {
        val ws = socket
        eids.clear()
        socket = null; ready = false; wanted = false; room = null
        roster.clear(); subjects.clear(); tracks.clear()
        guiFrame = null; sentWatch = false
        PeerLink.stop(); sentPort = 0
        runCatching { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }
    }

    // The JDK socket takes one frame at a time; each waits for the one before it.
    private fun sendText(text: String) {
        val ws = socket ?: return
        synchronized(this) { sending = sending.handle { _, _ -> Unit }.thenCompose { ws.sendText(text, true) } }
    }

    private fun sendBinary(bytes: ByteArray) {
        val ws = socket ?: return
        synchronized(this) { sending = sending.handle { _, _ -> Unit }.thenCompose { ws.sendBinary(ByteBuffer.wrap(bytes), true) } }
    }

    // ------------------------------------------------------------------ what comes in

    private fun onText(ws: WebSocket, text: String) {
        val m = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        when (m["t"]?.asString) {
            "c" -> m["s"]?.asString?.takeIf { Regex("[0-9a-f]{40}").matches(it) }?.let { s -> mc.execute { DevgineerClient.safely("pose share") { signIn(ws, s) } } }
            "ok" -> {
                ready = true; backoff = 2_000L
                lastProblem = null
                DevgineerClient.logger.info("[pose share] signed in (slot ${m["slot"]?.asInt})")
                mySlot = m["slot"]?.asInt ?: 0; v6 = m["v6"]?.asBoolean == true
                relayExtras = m["x"]?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e -> runCatching { e.asInt }.getOrNull() }?.toSet() ?: emptySet()
            }
            "w" -> wanted = m["w"]?.asInt == 1
            "pe" -> {
                val s = m["s"]?.asInt ?: return
                if (p2p && roster.containsKey(s)) PeerLink.add(s, m["a"]?.asString ?: return, m["k"]?.asString ?: return)
            }
            "px" -> m["s"]?.asInt?.let { PeerLink.remove(it) }
            "r" -> {
                roster.clear()
                m["m"]?.asJsonArray?.forEach { e ->
                    val a = e.asJsonArray
                    val slot = a[0].asInt; val name = ids[a[1].asString] ?: return@forEach
                    if (slot in 1..255 && name in teammates) roster[slot] = name
                }
                PeerLink.retain(roster.keys)
            }
            "s" -> {
                val i = m["i"]?.asInt ?: return; val n = m["n"]?.asString?.let { ids[it] } ?: return
                if (i in 1..255 && n in teammates) subjects[i] = n
            }
        }
    }

    private fun onBinary(data: ByteBuffer) {
        val now = System.nanoTime()
        if (data.remaining() == 5 && data.get(data.position()).toInt() == 0) {
            data.get(); val sent = pings.remove(data.getInt()) ?: return
            val ms = (now - sent) / 1e6
            rttMs = if (rttMs < 0) ms else rttMs * 0.7 + ms * 0.3
            return
        }
        if (data.remaining() < 2) return
        val from = data.get().toInt() and 255
        val sender = roster[from] ?: return
        when (val kind = data.get().toInt()) {
            1 -> if (data.remaining() == 19) heardRelayed(sender, null, from, data, 0, now)
            2 -> if (data.remaining() == 21) {
                val subject = subjects[data.get().toInt() and 255] ?: return
                val age = data.get().toInt() and 255
                if (subject == mc.user.name.lowercase()) return
                heardRelayed(subject, sender, from, data, age * 4, now)
            }
            3 -> gui(sender, data, now)
            9 -> rooms(sender, data)
            4 -> tracks[sender]?.gui = null
            7, 8 -> com.devgineerclient.party.PartyProgress.heard(sender, kind, data)
        }
    }

    /** A frame straight from a peer ([PeerLink]): its own pose, or its terminal (relayed teammates only come by the relay). */
    fun direct(slot: Int, kind: Int, body: ByteArray) {
        val sender = roster[slot] ?: return
        val now = System.nanoTime()
        val b = ByteBuffer.wrap(body)
        when (kind) {
            D_TICK -> if (body.size == 16 || (body.size == 18 && body[15].toInt() and 0x80 != 0)) {
                val ms = b.short.toLong() and 0xFFFF
                val pos = Vec3(get24(b), get24(b), get24(b))
                val yaw = (b.short.toInt() and 0xFFFF) / 65536f * 360f
                val pitch = b.short / 32767f * 90f
                val flags = b.get().toInt() and 255
                val item = if (flags and 0x80 != 0) b.short.toInt() and 0xFFFF else -1
                heard(sender, null, slot, now, true, true, ms, pos, yaw, pitch, flags and 0x7F, item, 0)
            }
            D_LOOK -> if (body.size == 6) {
                val ms = b.short.toLong() and 0xFFFF
                val yaw = (b.short.toInt() and 0xFFFF) / 65536f * 360f
                val pitch = b.short / 32767f * 90f
                heard(sender, null, slot, now, true, true, ms, null, yaw, pitch, 0, -1, 0)
            }
            3 -> gui(sender, b, now)
            9 -> rooms(sender, b)
            4 -> if (body.isEmpty()) tracks[sender]?.gui = null
            7, 8 -> com.devgineerclient.party.PartyProgress.heard(sender, kind, b)
        }
    }

    /** A relay pose frame's body (after its kind, and a seen one's index and age). */
    private fun heardRelayed(name: String, via: String?, source: Int, b: ByteBuffer, ageMs: Int, now: Long) {
        val seq = b.short.toLong() and 0xFFFF
        val pos = Vec3(get24(b), get24(b), get24(b))
        val yaw = (b.short.toInt() and 0xFFFF) / 65536f * 360f
        val pitch = b.short / 32767f * 90f
        val flags = b.get().toInt() and 255
        val half = (b.get().toInt() and 255) * 2
        val item = b.short.toInt() and 0xFFFF
        heard(name, via, source, now, false, false, seq, pos, yaw, pitch, flags, item, half + ageMs)
    }

    /**
     * A pose heard: [raw] the sender's tick (relayed) or its ms (peer-to-peer, [timed]), both 16 bits; [pos] null for a look
     * alone; [item] -1 when it didn't come.
     */
    private fun heard(name: String, via: String?, source: Int, now: Long, direct: Boolean, timed: Boolean, raw: Long, pos: Vec3?, yaw: Float, pitch: Float, flags: Int, item: Int, delayMs: Int) {
        if (name !in teammates) return
        if (pos != null && (pos.y < -64 || pos.y > 400)) return
        val t = tracks.getOrPut(name) { Track() }
        synchronized(t) {
            val fresh = t.samples.lastOrNull()?.let { now - it.arrived <= STALE_NS } == true
            // One source at a time: another (someone else seeing them) takes over only once this one has gone quiet.
            if (t.source != source) {
                if (fresh) return
                t.source = source; reset(t)
            }
            // Peer-to-peer over the relay's ticks: while it comes, the same pose a tick at a time (relayed for others) is
            // skipped; it starting (or ticks after it stopped) starts the timing over.
            if (t.timed != timed) {
                if (!timed && fresh) return
                t.timed = timed; reset(t)
                t.gap = if (timed) 16_000_000.0 else TICK_NS.toDouble()
            }
            t.via = via
            // 16 bits unwrapped against the newest (ticks wrap after 55 minutes, ms after 65 s).
            val s = if (t.lastSeq < 0) raw else t.lastSeq + ((raw - (t.lastSeq and 0xFFFF) + 0x8000) and 0xFFFF) - 0x8000
            // Relayed ticks only ever move on; peer-to-peer, a pose sent again from before a move comes after later looks.
            if (!timed && t.lastSeq >= 0 && s <= t.lastSeq) return
            if (timed && t.lastSeq >= 0 && s < t.lastSeq - 1000) return
            val time = if (timed) s * 1_000_000L else s * TICK_NS
            val off = now - time
            // A gap (they were still, or nobody was watching), or a clock that jumped: timing starts over from here.
            if (!fresh || (t.offset != Long.MAX_VALUE && kotlin.math.abs(off - t.offset) > 1_000_000_000L)) reset(t)
            if (s > t.lastSeq) t.lastSeq = s
            // Best latency so far, let drift upward slowly (4 ms a second) so a clock that runs a little apart doesn't strand it.
            val since = if (t.lastArrived == 0L) 0L else (now - t.lastArrived).coerceIn(0L, 1_000_000_000L)
            t.offset = if (t.offset == Long.MAX_VALUE) off else minOf(off, t.offset + since / 250)
            // How far behind to draw: the usual gap between poses (while they flow; a still pose's 100 ms isn't one) plus
            // twice the usual lateness, eased towards so the drawing time never jumps.
            val late = (off - t.offset).toDouble().coerceAtLeast(0.0)
            t.jitter = t.jitter * 0.9 + late * 0.1
            val gap = now - t.lastArrived
            if (t.lastArrived != 0L && gap < 80_000_000L) t.gap = t.gap * 0.9 + gap * 0.1
            t.lastArrived = now
            val target = (t.gap + 2 * t.jitter + 2_000_000).toLong().coerceIn(BUFFER_MIN_NS, BUFFER_MAX_NS)
            t.buffer = (t.buffer * 0.95 + target * 0.05).toLong()
            val delay = if (direct) PeerLink.rttMs(source).let { if (it > 0) (it / 2).toInt() else 0 } else delayMs
            // Kept in time order (a pose from before a move can come after it).
            val sample = Sample(s, time, now, pos ?: Vec3.ZERO, yaw, pitch.coerceIn(-90f, 90f), flags, maxOf(item, 0), delay, direct)
            var i = t.samples.size
            while (i > 0 && t.samples[i - 1].time > time) i--
            t.samples.add(i, sample)
            while (t.samples.size > 32) t.samples.removeFirst()
            if (timed && pos != null) {
                var j = t.ticks.size
                while (j > 0 && t.ticks[j - 1].first > time) j--
                if (j == 0 || t.ticks[j - 1].first != time) t.ticks.add(j, time to pos)
                while (t.ticks.size > 8) t.ticks.removeFirst()
                if (j >= t.ticks.size - 1) { if (item >= 0) t.item = item; t.sneaking = flags and 2 != 0 }
            }
            if (flags and 1 != 0) t.swingAt = now
        }
    }

    private fun reset(t: Track) { t.samples.clear(); t.ticks.clear(); t.offset = Long.MAX_VALUE; t.lastSeq = -1 }

    /** A terminal frame, kept only if it is one a real terminal could be: its grid's cells, no more than it has. */
    private fun gui(sender: String, b: ByteBuffer, now: Long) {
        if (b.remaining() < 3) return
        val type = TerminalTypes.entries.getOrNull(b.get().toInt() and 255) ?: return
        val grid = GRIDS[type] ?: return
        val extra = b.get().toInt() and 255
        when (type) {
            TerminalTypes.STARTS_WITH -> if (extra > 25) return
            TerminalTypes.SELECT -> if (extra >= DyeColor.entries.size) return
            else -> if (extra != 0) return
        }
        val n = b.get().toInt() and 255
        if (n > 35 || b.remaining() != n) return
        val sol = IntArray(n) { b.get().toInt() and 255 }
        if (solutionFor(type, grid, sol.asList()).size != n) return
        tracks.getOrPut(sender) { Track() }.gui = Gui(type, extra, sol, now)
    }

    private class Listener(private val lobby: String) : WebSocket.Listener {
        private val text = StringBuilder()
        private var bin: ByteBuffer? = null
        private var tooBig = false

        override fun onOpen(ws: WebSocket) {
            DevgineerClient.safely("pose share") { opened(ws, lobby) }
            ws.request(1)
        }

        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            if (text.length + data.length <= 4096) text.append(data)
            if (last) { val whole = text.toString(); text.setLength(0); DevgineerClient.safely("pose share") { onText(ws, whole) } }
            ws.request(1)
            return null
        }

        override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            // Frames are small: one in parts is gathered (to the relay's limit) before it's read, and a bigger one dropped.
            val prev = bin
            if (prev == null && last && !tooBig) DevgineerClient.safely("pose share") { onBinary(data) }
            else if (!tooBig) {
                if ((prev?.remaining() ?: 0) + data.remaining() > 64) tooBig = true
                else bin = ByteBuffer.allocate((prev?.remaining() ?: 0) + data.remaining()).also { n -> prev?.let { n.put(it) }; n.put(data); n.flip() }
                if (last && !tooBig) bin?.let { DevgineerClient.safely("pose share") { onBinary(it) } }
            }
            if (last) { bin = null; tooBig = false }
            ws.request(1)
            return null
        }

        override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (socket !== ws) return null
            if (statusCode != WebSocket.NORMAL_CLOSURE) problem("closed by the relay: $statusCode")
            when (statusCode) {
                // Replaced by another game on this account: that one shares; this one waits a while.
                4002 -> retryLater(60_000L)
                4001 -> retryLater(10_000L)
                // Mojang busy: well back off.
                4005 -> retryLater(30_000L)
                else -> retryLater()
            }
            return null
        }

        override fun onError(ws: WebSocket, error: Throwable) { if (socket === ws) { problem("socket error: ${error.message}"); retryLater() } }
    }

    // --- rooms -------------------------------------------------------------------------------------------------------------

    private var roomsSig = ""
    private var roomsRoster: Set<Int> = emptySet()
    private var roomsAt = 0L

    /** [9][count]([core i32][turn][tile count][tiles: z * 6 + x])*: this game's named rooms, to every linked teammate. */
    private fun shareRooms(nowMs: Long) {
        if (!ready || roster.isEmpty() || nowMs - roomsAt < 1100) return
        val mine = KnownWorld.mine().filter { it.tiles.size in 1..4 }
        val sig = mine.joinToString(";") { "${it.core},${it.rot.ordinal},${it.tiles}" }
        val linked = roster.keys.toSet()
        if (mine.isEmpty() || (sig == roomsSig && linked == roomsRoster)) return
        roomsSig = sig; roomsRoster = linked; roomsAt = nowMs
        val b = ByteBuffer.allocate(256)
        b.put(9).put(0)
        var n = 0
        for (r in mine) {
            if (b.remaining() < 6 + r.tiles.size || n >= 36) break
            b.putInt(r.core).put(r.rot.ordinal.toByte()).put(r.tiles.size.toByte())
            for (t in r.tiles) b.put((t.z * 6 + t.x).toByte())
            n++
        }
        b.put(1, n.toByte())
        shareExtra(b.array().copyOf(b.position()))
    }

    /** A teammate's rooms, as far as they read as rooms (the room pack checks the rest when they're placed: KnownWorld). */
    private fun rooms(sender: String, b: ByteBuffer) {
        if (sender !in teammates || !b.hasRemaining()) return
        val n = b.get().toInt() and 255
        if (n > 36) return
        val out = ArrayList<KnownWorld.SharedRoom>()
        val used = HashSet<Int>()
        repeat(n) {
            if (b.remaining() < 6) return
            val core = b.int
            val rot = com.engineerclient.p3sim.pack.Rot.entries.getOrNull(b.get().toInt() and 255) ?: return
            val count = b.get().toInt() and 255
            if (count !in 1..4 || b.remaining() < count) return
            val tiles = List(count) { b.get().toInt() and 255 }
            if (tiles.any { it !in 0 until 36 || !used.add(it) }) return
            out += KnownWorld.SharedRoom(core, rot, tiles.map { com.engineerclient.p3sim.pack.Tile(it % 6, it / 6) })
        }
        if (b.hasRemaining()) return
        KnownWorld.shared(sender, out)
    }
}
