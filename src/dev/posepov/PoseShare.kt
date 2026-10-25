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
    /** How far behind the newest pose a shared player is drawn, to ride out uneven arrival. */
    const val BUFFER_NS = 70_000_000L
    private const val EXTRAPOLATE_NS = 100_000_000L
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
    private var wasWanted = false


    /** Whether you're looking at your teammates' views, or about to (set by [PosePov] each tick). */
    @Volatile var watching = false
    /** "Peer-to-peer sharing": straight to teammates who have it on too, over UDP (see [PeerLink]); set by [PosePov]. */
    @Volatile var p2p = false
    @Volatile private var mySlot = 0
    @Volatile private var v6 = false
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

    class Sample(val seq: Long, val arrived: Long, val pos: Vec3, val yaw: Float, val pitch: Float, val flags: Int, val item: Int, val delayMs: Int, val direct: Boolean)

    class Track {
        val samples = ArrayDeque<Sample>()
        /** The smallest (arrival - send tick time) seen: arrival at the best latency so far; a sample's time is its tick plus this. */
        var offset = Long.MAX_VALUE
        var lastSeq = -1L
        var source = 0
        var via: String? = null
        var swingAt = 0L
        @Volatile var gui: Gui? = null
        fun at(s: Sample) = s.seq * TICK_NS + offset
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
    class Pose(val pos: Vec3, val yaw: Float, val pitch: Float, val item: ItemStack, val swingAt: Long, val sneaking: Boolean, val via: String?, val direct: Boolean, val delayMs: Int)

    fun pose(name: String, now: Long): Pose? {
        val t = tracks[name.lowercase()] ?: return null
        synchronized(t) {
            val last = t.samples.lastOrNull() ?: return null
            if (now - last.arrived > STALE_NS) return null
            val at = now - BUFFER_NS
            // The two poses either side of the drawing time, or the newest two to run on from (for a little while).
            var a = t.samples.first(); var b = last
            for (s in t.samples) { if (t.at(s) <= at) a = s else { b = s; break } }
            val pos: Vec3; val yaw: Float; val pitch: Float
            if (a === b || t.at(b) == t.at(a)) { pos = b.pos; yaw = b.yaw; pitch = b.pitch }
            else {
                val k = ((minOf(at, t.at(b) + EXTRAPOLATE_NS) - t.at(a)).toDouble() / (t.at(b) - t.at(a))).coerceAtLeast(0.0)
                pos = a.pos.lerp(b.pos, k)
                yaw = a.yaw + Mth.wrapDegrees(b.yaw - a.yaw) * k.toFloat()
                pitch = Mth.lerp(k.toFloat().coerceAtMost(1f), a.pitch, b.pitch)
            }
            val item = BuiltInRegistries.ITEM.byId(b.item).takeIf { b.item != 0 && it !== Items.AIR }?.let { ItemStack(it) } ?: ItemStack.EMPTY
            val delay = (BUFFER_NS / 1_000_000).toInt() + b.delayMs + (if (!b.direct && rttMs > 0) (rttMs / 2).toInt() else 0)
            return Pose(pos, yaw, pitch, item, t.swingAt, b.flags and 2 != 0, t.via, b.direct, delay)
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
        if (!wanted) { wasWanted = false; if (guiFrame != null) { guiFrame = null; share(byteArrayOf(4)) }; return }
        if (!wasWanted) { wasWanted = true; selfAt = 0L; guiAt = 0L }

        // Yourself: every tick while moving, every [STILL_NS] while still, and the still pose again just before moving off
        // (so the move starts from where it did, not drawn out across the gap).
        var flags = if (swung) 1 else 0
        if (me.isShiftKeyDown) flags = flags or 2
        val frame = pose(1, -1, 0, me.x, me.y, me.z, me.yRot, me.xRot, flags, me.mainHandItem)
        val sig = frame.copyOfRange(3, 16) + frame.copyOfRange(17, 20)
        val changed = !sig.contentEquals(lastSig)
        if (changed && !prevSent) prevFrame?.let { share(it) }
        prevSent = changed || now - selfAt > STILL_NS
        if (prevSent) { share(frame); selfAt = now }
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

    /** Your own frame: straight to each live peer, and through the relay unless that reached every linked teammate. */
    private fun share(frame: ByteArray) {
        val direct = if (p2p) PeerLink.broadcast(frame) else emptySet()
        if (direct.isEmpty() || !direct.containsAll(roster.keys)) sendBinary(frame)
    }

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
        when (data.get().toInt()) {
            1 -> if (data.remaining() == 19) heard(sender, null, from, data, 0, now, false)
            2 -> if (data.remaining() == 21) {
                val subject = subjects[data.get().toInt() and 255] ?: return
                val age = data.get().toInt() and 255
                if (subject == mc.user.name.lowercase()) return
                heard(subject, sender, from, data, age * 4, now, false)
            }
            3 -> gui(sender, data, now)
            4 -> tracks[sender]?.gui = null
        }
    }

    /** A frame straight from a peer ([PeerLink]): its own pose, or its terminal (relayed teammates only come by the relay). */
    fun direct(slot: Int, frame: ByteArray) {
        val sender = roster[slot] ?: return
        val now = System.nanoTime()
        val b = ByteBuffer.wrap(frame, 1, frame.size - 1).slice()
        when (frame[0].toInt()) {
            1 -> if (frame.size == 20) heard(sender, null, slot, b, 0, now, true)
            3 -> gui(sender, b, now)
            4 -> if (frame.size == 1) tracks[sender]?.gui = null
        }
    }

    private fun heard(name: String, via: String?, source: Int, b: ByteBuffer, ageMs: Int, now: Long, direct: Boolean) {
        if (name !in teammates) return
        val rawSeq = b.short.toInt() and 0xFFFF
        val x = get24(b); val y = get24(b); val z = get24(b)
        val yaw = (b.short.toInt() and 0xFFFF) / 65536f * 360f
        val pitch = b.short / 32767f * 90f
        val flags = b.get().toInt() and 255
        val half = (b.get().toInt() and 255) * 2
        val item = b.short.toInt() and 0xFFFF
        if (y < -64 || y > 400) return
        val t = tracks.getOrPut(name) { Track() }
        synchronized(t) {
            // One source at a time: another (someone else seeing them) takes over only once this one has gone quiet.
            if (t.source != source) {
                val quiet = t.samples.lastOrNull()?.let { now - it.arrived > 300_000_000L } ?: true
                if (!quiet) return
                t.source = source; t.samples.clear(); t.offset = Long.MAX_VALUE; t.lastSeq = -1
            }
            t.via = via
            // The sender's ticks, unwrapped (16 bits wrap after 55 minutes).
            val s = if (t.lastSeq < 0) rawSeq.toLong() else t.lastSeq + ((rawSeq - (t.lastSeq and 0xFFFF).toInt() + 0x8000) and 0xFFFF) - 0x8000
            if (t.lastSeq >= 0 && s <= t.lastSeq) return
            // A gap (they were still, or nobody was watching): timing starts over from here.
            if (t.samples.lastOrNull()?.let { now - it.arrived > STALE_NS } == true) { t.samples.clear(); t.offset = Long.MAX_VALUE }
            t.lastSeq = s
            val off = now - s * TICK_NS
            // Best latency so far, let drift upward slowly so a clock that runs a little apart doesn't strand it.
            t.offset = if (t.offset == Long.MAX_VALUE) off else minOf(off, t.offset + 200_000L)
            t.samples.addLast(Sample(s, now, Vec3(x, y, z), yaw, pitch.coerceIn(-90f, 90f), flags, item, if (direct) PeerLink.rttMs(source).let { if (it > 0) (it / 2).toInt() else 0 } else half + ageMs, direct))
            while (t.samples.size > 16) t.samples.removeFirst()
            if (flags and 1 != 0) t.swingAt = now
        }
    }

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
}
