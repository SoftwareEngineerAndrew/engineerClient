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
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.Mth
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
 *
 * Sent every tick: where you stand and look (and what you hold, and your swings), the same for each teammate you can see
 * who doesn't share for themselves (so one out of someone's range still shows if you can see them), and the terminal you
 * have open with Odin's solution. The relay passes it only to party members who list you as you list them, each signed
 * in through Mojang (your session token goes to Mojang alone, as when joining a server).
 *
 * What arrives is never trusted past what it can show: names must be your own teammates, positions are clamped to the
 * dungeon, items are looked up by id (unknown ones are dropped), and a terminal is only drawn as one of Odin's six with
 * its own title; nothing a sender writes is ever shown as text.
 */
object PoseShare {
    private const val SITE = "undonecoffee.com"
    private const val TICK_NS = 50_000_000L
    /** How far behind the newest pose a shared player is drawn, to ride out uneven arrival. */
    const val BUFFER_NS = 70_000_000L
    private const val EXTRAPOLATE_NS = 100_000_000L
    private const val STALE_NS = 600_000_000L
    private const val GUI_STALE_NS = 3_000_000_000L

    private val mc get() = Minecraft.getInstance()
    private val http: HttpClient = HttpClient.newHttpClient()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    private var sending: CompletableFuture<*> = CompletableFuture.completedFuture(null)
    private var connecting = false
    private var room: String? = null
    private var retryAt = 0L
    private var backoff = 2_000L
    private var seq = 0
    private var sentParty: List<String> = emptyList()
    private var partyAt = 0L
    private var lastPing = 0L
    private var wasSwinging = false
    private var guiHash = 0
    private var guiAt = 0L
    private var guiOpen = false

    /** slot -> name (lower case) of the members linked with you, and the relay's subject ids. */
    private val roster = ConcurrentHashMap<Int, String>()
    private val subjects = ConcurrentHashMap<Int, String>()
    /** Your teammates' names (lower case), as of the last tick: what arrives must be about one of them. */
    @Volatile private var teammates: Set<String> = emptySet()
    private val pings = ConcurrentHashMap<Int, Long>()
    /** Your round trip to the relay, smoothed (ms); -1 until measured. */
    @Volatile var rttMs = -1.0; private set

    // ------------------------------------------------------------------ what's been heard

    class Sample(val seq: Long, val arrived: Long, val pos: Vec3, val yaw: Float, val pitch: Float, val flags: Int, val item: Int, val delayMs: Int)

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

    /** An open terminal as its holder sees it: Odin's type, the slots, and Odin's solution (slot numbers, in order). */
    class Gui(val type: TerminalTypes, val size: Int, val items: Array<ItemStack>, val solution: IntArray, val at: Long)

    private val tracks = ConcurrentHashMap<String, Track>()

    /** A shared teammate's pose for drawing now: [via] is who relayed it (null when they share it themselves). */
    class Pose(val pos: Vec3, val yaw: Float, val pitch: Float, val item: ItemStack, val swingAt: Long, val sneaking: Boolean, val via: String?, val delayMs: Int)

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
            val item = BuiltInRegistries.ITEM.byId(b.item).takeIf { b.item != 0 }?.let { ItemStack(it) } ?: ItemStack.EMPTY
            val delay = (BUFFER_NS / 1_000_000).toInt() + b.delayMs + (if (rttMs > 0) (rttMs / 2).toInt() else 0)
            return Pose(pos, yaw, pitch, item, t.swingAt, b.flags and 2 != 0, t.via, delay)
        }
    }

    fun gui(name: String, now: Long): Gui? = tracks[name.lowercase()]?.gui?.takeIf { now - it.at < GUI_STALE_NS }

    // ------------------------------------------------------------------ every tick

    /** Connects while in a dungeon (and [on]), and sends this tick's poses and terminal. */
    fun tick(on: Boolean) {
        val lobby = LocationUtils.lobbyId?.takeIf { on && DungeonUtils.inDungeons && Regex("[A-Za-z0-9]{1,24}").matches(it) }
        val me = mc.player
        teammates = DungeonUtils.dungeonTeammates.mapTo(HashSet()) { it.name.lowercase() }
        if (lobby == null || me == null) { close(); return }
        if (lobby != room) { close(); room = lobby }
        if (socket == null) {
            if (!connecting && System.currentTimeMillis() >= retryAt) connect(lobby)
            return
        }
        if (!ready) return
        seq++

        // The party, whenever it changes (the relay takes one every 2 s).
        val party = DungeonUtils.dungeonTeammates.map { it.name.lowercase() }.filter { it != me.name.string.lowercase() }.take(6)
        if (party != sentParty && System.currentTimeMillis() - partyAt > 2100) {
            sendText(JsonObject().apply { addProperty("t", "p"); add("p", JsonArray().apply { party.forEach { add(it) } }) }.toString())
            sentParty = party; partyAt = System.currentTimeMillis()
        }

        // Yourself.
        val swinging = me.isSwinging
        var flags = if (swinging && !wasSwinging) 1 else 0
        wasSwinging = swinging
        if (me.isShiftKeyDown) flags = flags or 2
        sendBinary(pose(1, -1, 0, me.x, me.y, me.z, me.yRot, me.xRot, flags, me.mainHandItem))

        // Teammates you can see who don't share for themselves (each tagged by where they are in the party you sent).
        val sharing = roster.values.toSet()
        val now = System.nanoTime()
        for ((i, name) in sentParty.withIndex()) {
            if (name in sharing) continue
            val p = mc.level?.players()?.firstOrNull { it.name.string.equals(name, ignoreCase = true) } ?: continue
            val f = if (PosePov.swungSince(p.id, now - TICK_NS)) 1 else 0
            sendBinary(pose(2, i, 0, p.x, p.y, p.z, p.yHeadRot, p.xRot, f, p.mainHandItem))
        }

        // The terminal you have open, when it changes (and every second, for whoever joined since); closed once.
        val term = TerminalUtils.currentTerm
        val menu = me.containerMenu
        if (term != null && menu !== me.inventoryMenu) {
            val size = term.type.windowSize.coerceIn(0, 54).coerceAtMost(menu.slots.size)
            val buf = ByteBuffer.allocate(4 + size * 4 + 1 + 120)
            buf.put(3).put(term.type.ordinal.toByte()).put(size.toByte())
            for (i in 0 until size) {
                val s = menu.slots[i].item
                buf.putShort(if (s.isEmpty) 0 else BuiltInRegistries.ITEM.getId(s.item).toShort())
                buf.put(s.count.coerceIn(0, 127).toByte()).put(if (s.hasFoil()) 1 else 0)
            }
            val sol = term.solution.filter { it in 0 until size }.take(120)
            buf.put(sol.size.toByte()); sol.forEach { buf.put(it.toByte()) }
            buf.flip()
            val hash = buf.hashCode()
            if (hash != guiHash || System.currentTimeMillis() - guiAt > 1000) {
                guiHash = hash; guiAt = System.currentTimeMillis(); guiOpen = true
                sendBinary(ByteArray(buf.remaining()).also { buf.get(it) })
            }
        } else if (guiOpen) { guiOpen = false; guiHash = 0; sendBinary(byteArrayOf(4)) }

        // A ping every 2 s: the round trip, for the delays shown.
        if (System.currentTimeMillis() - lastPing > 2000) {
            lastPing = System.currentTimeMillis()
            val nonce = (lastPing and 0x7FFFFFFF).toInt()
            pings[nonce] = System.nanoTime()
            sendBinary(ByteBuffer.allocate(5).put(0).putInt(nonce).array())
        }
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

    private fun connect(lobby: String) {
        connecting = true
        val v = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("engineerclient").map { it.metadata.version.friendlyString }.orElse("dev").replace(Regex("[^\\w.+-]"), "").take(64).ifEmpty { "dev" }
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create("wss://$SITE/pose/api/$lobby?v=$v"), Listener())
            .whenComplete { ws, err ->
                connecting = false
                if (err != null) { retryLater(); return@whenComplete }
                sending = CompletableFuture.completedFuture(null)
                ready = false; roster.clear(); subjects.clear(); sentParty = emptyList(); partyAt = 0L
                socket = ws
            }
    }

    /** The relay's challenge: Mojang's join with its serverId (off the game thread), then who we are and our party. */
    private fun signIn(ws: WebSocket, serverId: String) {
        Thread.ofPlatform().name("devgineerclient-pose-signin").daemon(true).start {
            try {
                val u = mc.user
                mc.services().sessionService().joinServer(u.profileId, u.accessToken, serverId)
                val party = DungeonUtils.dungeonTeammates.map { it.name.lowercase() }.filter { it != u.name.lowercase() }.take(6)
                sentParty = party; partyAt = System.currentTimeMillis()
                if (socket === ws) sendText(JsonObject().apply {
                    addProperty("t", "a"); addProperty("n", u.name); add("p", JsonArray().apply { party.forEach { add(it) } })
                }.toString())
            } catch (e: Exception) {
                DevgineerClient.logger.warn("[pose share] sign-in failed: ${e.javaClass.simpleName}")
                if (socket === ws) { runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }; retryLater() }
            }
        }
    }

    private fun retryLater() {
        socket = null; ready = false
        retryAt = System.currentTimeMillis() + backoff
        backoff = (backoff * 2).coerceAtMost(60_000L)
    }

    fun close() {
        val ws = socket
        socket = null; ready = false; room = null
        roster.clear(); subjects.clear(); tracks.clear()
        if (guiOpen) guiOpen = false
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
            "c" -> m["s"]?.asString?.takeIf { Regex("[0-9a-f]{40}").matches(it) }?.let { signIn(ws, it) }
            "ok" -> { ready = true; backoff = 2_000L }
            "r" -> {
                roster.clear()
                m["m"]?.asJsonArray?.forEach { e ->
                    val a = e.asJsonArray
                    val slot = a[0].asInt; val name = a[1].asString.lowercase()
                    if (slot in 1..255 && name in teammates) roster[slot] = name
                }
            }
            "s" -> {
                val i = m["i"]?.asInt ?: return; val n = m["n"]?.asString?.lowercase() ?: return
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
            1 -> if (data.remaining() == 19) heard(sender, null, from, data, 0, now)
            2 -> if (data.remaining() == 21) {
                val subject = subjects[data.get().toInt() and 255] ?: return
                val age = data.get().toInt() and 255
                if (subject == mc.user.name.lowercase()) return
                heard(subject, sender, from, data, age * 4, now)
            }
            3 -> gui(sender, data, now)
            4 -> tracks[sender]?.gui = null
        }
    }

    private fun heard(name: String, via: String?, source: Int, b: ByteBuffer, ageMs: Int, now: Long) {
        if (name !in teammates) return
        val rawSeq = b.short.toInt() and 0xFFFF
        val x = get24(b); val y = get24(b); val z = get24(b)
        val yaw = (b.short.toInt() and 0xFFFF) / 65536f * 360f
        val pitch = b.short / 32767f * 90f
        val flags = b.get().toInt() and 255
        val half = (b.get().toInt() and 255) * 2
        val item = b.short.toInt() and 0xFFFF
        if (!x.isFinite() || y < -64 || y > 400) return
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
            t.lastSeq = s
            val off = now - s * TICK_NS
            // Best latency so far, let drift upward slowly so a clock that runs a little apart doesn't strand it.
            t.offset = if (t.offset == Long.MAX_VALUE) off else minOf(off, t.offset + 200_000L)
            t.samples.addLast(Sample(s, now, Vec3(x, y, z), yaw, pitch.coerceIn(-90f, 90f), flags, item, half + ageMs))
            while (t.samples.size > 16) t.samples.removeFirst()
            if (flags and 1 != 0) t.swingAt = now
        }
    }

    private fun gui(sender: String, b: ByteBuffer, now: Long) {
        if (b.remaining() < 3) return
        val type = TerminalTypes.entries.getOrNull(b.get().toInt() and 255) ?: return
        val size = b.get().toInt() and 255
        if (size > 54 || size != type.windowSize || b.remaining() < size * 4 + 1) return
        val items = Array(size) {
            val id = b.short.toInt() and 0xFFFF; val count = b.get().toInt().coerceIn(1, 64); val foil = b.get().toInt() == 1
            if (id == 0) ItemStack.EMPTY
            else BuiltInRegistries.ITEM.byId(id).takeIf { it !== Items.AIR }?.let { ItemStack(it, count).apply { if (foil) set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) } } ?: ItemStack.EMPTY
        }
        val n = b.get().toInt() and 255
        if (n > 120 || b.remaining() < n) return
        val sol = IntArray(n) { b.get().toInt() and 255 }.filter { it < size }.toIntArray()
        tracks.getOrPut(sender) { Track() }.gui = Gui(type, size, items, sol, now)
    }

    private class Listener : WebSocket.Listener {
        private val text = StringBuilder()
        private var bin: ByteBuffer? = null

        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            if (text.length + data.length <= 4096) text.append(data)
            if (last) { val whole = text.toString(); text.setLength(0); DevgineerClient.safely("pose share") { onText(ws, whole) } }
            ws.request(1)
            return null
        }

        override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            // Frames are small; a part-frame is gathered (up to the relay's limit) before it's read.
            val joined = bin?.let { prev -> if (prev.remaining() + data.remaining() > 512) null else ByteBuffer.allocate(prev.remaining() + data.remaining()).put(prev).put(data).flip() }
                ?: if (bin == null) data else null
            if (last) { bin = null; if (joined != null) DevgineerClient.safely("pose share") { onBinary(joined) } }
            else bin = joined?.let { ByteBuffer.allocate(it.remaining()).put(it).flip() } ?: ByteBuffer.allocate(0)
            ws.request(1)
            return null
        }

        override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            // Replaced by another game on this account: that one shares; this one waits a minute before trying again.
            if (socket === ws) { retryLater(); if (statusCode == 4002) retryAt = System.currentTimeMillis() + 60_000L }
            return null
        }

        override fun onError(ws: WebSocket, error: Throwable) { if (socket === ws) retryLater() }
    }
}
