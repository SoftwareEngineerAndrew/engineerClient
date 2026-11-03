package com.engineerclient.pov

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Teammates' views over Cloudflare's TURN (turn.cloudflare.com), for [PoseShare]: each game allocates a relayed address at
 * its own nearest Cloudflare location (anycast), and sends to a teammate's relayed address through it, so a frame crosses
 * Cloudflare's network on the shortest path between those two players, and neither ever learns the other's own address:
 * the relay hands each pair only the other's relayed address (Cloudflare's) and a fresh key. This socket only ever talks
 * to the TURN server it allocated at.
 *
 * Each frame, inside TURN's ChannelData (4 bytes): [sender slot << 4 | kind][counter, low 24 bits][AES-GCM of the body, tag
 * 12 bytes, the least Java's GCM takes]. The header is the associated data; the nonce is the slot plus the whole counter (worked out from its low bits
 * and the last one heard), so the two directions never share one. A counter heard before is dropped (replays), and so is
 * one behind the newest unless its kind is in [anyOrder] (then up to 64 back, once each: [Peer.fresh]). Kinds: [PING], [PONG]
 * (each with the sender's one-way time to the pose relay, for picking the faster way), [MISSING] and PoseShare's (its D_ ones).
 *
 * Two ways a link in trouble is noticed quickly: a teammate not heard from for [SURE_MS], or one that says it isn't hearing
 * us ([MISSING]), isn't [Peer.sure], and what goes to it goes by the relay too; and every teammate quiet at once, or the
 * server seeing us at another address (a NAT that moved us), gets a new allocation rather than waiting for its renewal.
 *
 * TURN (RFC 8656), as little of it as this needs: Allocate (long-term credentials, MESSAGE-INTEGRITY), Refresh, ChannelBind
 * per teammate (which also gives the permission), Binding now and then to keep the NAT's mapping open and time the server.
 */
class TurnLink(
    /** A frame from linked teammate [slot] (any thread). */
    private val sink: (slot: Int, kind: Int, body: ByteArray) -> Unit,
    /** Something worth a log line. */
    private val log: (String) -> Unit,
    private val host: String = "turn.cloudflare.com",
    private val port: Int = 3478,
    /**
     * Kinds that are still worth having when they come late, behind a later frame (poses: [PoseTrack] puts each in its
     * place by its own time). Any other kind that comes late is dropped, as a replay would be, so it can never undo a newer one.
     */
    private val anyOrder: Set<Int> = emptySet(),
) {
    enum class State { OFF, ALLOCATING, READY, FAILED }

    class Peer(val slot: Int, val addr: InetSocketAddress, val key: SecretKeySpec) {
        val channel = 0x4000 + slot
        val sent = AtomicLong()
        @Volatile var heard = 0L
        @Volatile var heardAt = 0L
        @Volatile var rttMs = -1.0
        /** Their one-way time to the pose relay (ms), from their pings; -1 until heard. */
        @Volatile var theirRelayMs = -1
        @Volatile var bound = false
        var bindAt = 0L
        var lastOut = 0L
        val pings = ConcurrentHashMap<Int, Long>()
        /** Frames in and out, and frames that never came (gaps in the counter), for the debug info. */
        @Volatile var framesIn = 0L
        @Volatile var lost = 0L
        /** Which of the 64 counters up to [heard] have come (bit i: heard - i), so a late one is taken once and a replay never. */
        var seen = 0L
        fun live(now: Long) = now - heardAt < LIVE_MS
        /** Heard from lately enough that what's sent is surely getting there: past this, the relay carries it too. */
        fun sure(now: Long) = now - heardAt < SURE_MS && now - missedAt >= MISSED_FOR_MS
        /** When they last said they aren't hearing us ([MISSING]). */
        @Volatile var missedAt = Long.MIN_VALUE / 2

        /** Whether counter [ctr] may be opened: past the newest, or (when [late] is fine) one of the last 64 not yet heard. */
        fun fresh(ctr: Long, late: Boolean): Boolean {
            if (ctr > heard) return true
            val d = heard - ctr
            return late && d in 1..63 && (seen ushr d.toInt()) and 1L == 0L
        }

        /** Counter [ctr] has come (opened and checked). */
        fun mark(ctr: Long) {
            if (ctr > heard) {
                val shift = ctr - heard
                if (heard > 0 && shift > 1) lost += shift - 1
                seen = (if (shift >= 64) 0L else seen shl shift.toInt()) or 1L
                heard = ctr
            } else {
                seen = seen or (1L shl (heard - ctr).toInt())
                if (lost > 0) lost--
            }
        }
    }

    private class Tx(val method: Int, val slot: Int, val sentAt: Long, val tries: Int)

    @Volatile var state = State.OFF; private set
    /** Why it last failed, for the debug info. */
    @Volatile var problem: String? = null; private set
    /** Round trip to the TURN server (ms, smoothed); -1 until measured. */
    @Volatile var serverRttMs = -1.0; private set
    @Volatile var relayed: String? = null; private set
    @Volatile var lifetimeS = 0; private set
    /** The credentials were turned down (expired): new ones are needed before trying again. */
    @Volatile var needsCredentials = false; private set
    /** Your one-way time to the pose relay (ms), told to teammates in pings; set by [PoseShare]. */
    @Volatile var myRelayMs = -1
    /** Frames sent and received (all peers), for the debug info's rates. */
    val framesOut = AtomicLong()
    val bytesOut = AtomicLong()
    val bytesIn = AtomicLong()

    private val peers = ConcurrentHashMap<Int, Peer>()
    private val byChannel = ConcurrentHashMap<Int, Peer>()
    private val txs = ConcurrentHashMap<ByteBuffer, Tx>()
    @Volatile private var socket: DatagramSocket? = null
    private var mySlot = 0
    private var user = ""
    private var pass = ""
    @Volatile private var realm: ByteArray? = null
    @Volatile private var nonce: ByteArray? = null
    @Volatile private var authKey: ByteArray? = null
    private var authTried = false
    private var refreshAt = 0L
    private var bindingAt = 0L
    private var retryAt = 0L
    private var backoff = 5_000L
    private var reported: String? = null
    private val random = SecureRandom()
    private var window = 0L
    private var count = 0
    private val ciphers = ThreadLocal.withInitial { Cipher.getInstance("AES/GCM/NoPadding") }

    val running get() = state != State.OFF
    val ready get() = state == State.READY

    /** Allocates with these credentials (once signed in to the relay: [slot] is ours); the same ones again just keep it. */
    @Synchronized
    fun start(user: String, pass: String, slot: Int) {
        mySlot = slot
        if (state != State.OFF && user == this.user && pass == this.pass) return
        stop()
        this.user = user; this.pass = pass
        needsCredentials = false; backoff = 5_000L
        open()
    }

    @Synchronized
    private fun open() {
        state = State.ALLOCATING; problem = null
        realm = null; nonce = null; authKey = null; authTried = false; mapped = null
        relayed = null; txs.clear()
        for (p in peers.values) p.bound = false
        Thread.ofPlatform().name("engineerclient-pov-turn").daemon(true).start {
            // The server's address (prefer IPv4: the relayed addresses are), then the socket, talking to it alone.
            val addr = runCatching { InetAddress.getAllByName(host).let { a -> a.firstOrNull { it is Inet4Address } ?: a.first() } }.getOrNull()
            if (addr == null) { fail("couldn't look up $host"); return@start }
            val s = runCatching { DatagramSocket().also { it.connect(InetSocketAddress(addr, port)) } }.getOrNull()
            if (s == null) { fail("couldn't open a UDP socket"); return@start }
            synchronized(this) {
                if (state != State.ALLOCATING || socket != null) { s.close(); return@start }
                socket = s
                request(ALLOCATE)
            }
            read(s)
        }
    }

    @Synchronized
    fun stop() {
        val s = socket
        // Let the allocation go at once rather than in 10 minutes.
        if (s != null && state == State.READY) runCatching { send(s, message(REFRESH, 0) { attr(LIFETIME, int(0)) }) }
        socket = null
        state = State.OFF; relayed = null; reported = null
        peers.clear(); byChannel.clear(); txs.clear()
        runCatching { s?.close() }
    }

    /** Down until a retry; [now]: at once (the network under it changed, which a new allocation fixes), not after the backoff. */
    @Synchronized
    private fun fail(why: String, now: Boolean = false) {
        log("TURN: $why")
        problem = why
        val s = socket
        socket = null; relayed = null; txs.clear()
        for (p in peers.values) p.bound = false
        runCatching { s?.close() }
        state = State.FAILED
        retryAt = System.currentTimeMillis() + (if (now) 0L else backoff)
        if (!now) backoff = (backoff * 2).coerceAtMost(120_000L)
    }

    /** Our address as the TURN server sees it (its Binding answers): another one means the NAT moved us, and the allocation is lost. */
    @Volatile private var mapped: InetSocketAddress? = null
    private var quietCheckAt = 0L

    fun add(slot: Int, address: String, keyHex: String) {
        if (slot == mySlot || slot !in 1..15) return
        val addr = parse(address) ?: return
        if (!Regex("[0-9a-f]{64}").matches(keyHex)) return
        val key = SecretKeySpec(ByteArray(32) { keyHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, "AES")
        val p = Peer(slot, addr, key)
        peers[slot] = p; byChannel[p.channel] = p
    }

    fun remove(slot: Int) { peers.remove(slot)?.let { byChannel.remove(it.channel) } }
    fun retain(slots: Set<Int>) { for (s in peers.keys.toList()) if (s !in slots) remove(s) }

    fun peer(slot: Int) = peers[slot]
    fun live(slot: Int) = peers[slot]?.live(System.currentTimeMillis()) == true
    fun rttMs(slot: Int) = peers[slot]?.rttMs ?: -1.0

    /** "a.b.c.d:port", as the relay writes them: a literal IPv4 address only, never a name to look up. */
    private fun parse(a: String): InetSocketAddress? = runCatching {
        val i = a.lastIndexOf(':')
        val host = a.substring(0, i)
        val p = a.substring(i + 1).toInt()
        if (!Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(host) || p !in 1024..65535) return null
        InetSocketAddress(InetAddress.ofLiteral(host), p)
    }.getOrNull()

    /**
     * Every tick: retries and renewals (the allocation, each teammate's channel), a Binding now and then (keeps the NAT's
     * mapping to the server open, and times it), pings to teammates (a second apart once live, quicker until then), and the
     * relayed address to [report] whenever it changes ("" when there is none).
     */
    fun tick(report: (String) -> Unit) {
        val now = System.currentTimeMillis()
        val addr = relayed ?: ""
        if (addr != (reported ?: "")) { reported = addr; report(addr) }
        when (state) {
            State.OFF -> return
            State.FAILED -> { if (!needsCredentials && now >= retryAt) open(); return }
            else -> {}
        }
        val s = socket ?: return
        synchronized(this) {
            // Unanswered requests: again, a little later each time, then given up on.
            for ((id, t) in txs) {
                if (now - t.sentAt < RETRY_MS shl t.tries) continue
                txs.remove(id)
                if (t.tries >= 5) {
                    when (t.method) {
                        ALLOCATE -> { fail("no answer from $host"); return }
                        REFRESH -> { fail("the allocation's renewal went unanswered"); return }
                        CHANNEL_BIND -> peers[t.slot]?.bindAt = 0L
                    }
                } else request(t.method, t.slot, t.tries + 1)
            }
            if (state != State.READY) return
            if (now >= refreshAt) { refreshAt = now + 30_000L; request(REFRESH) }
            for (p in peers.values) if (now - p.bindAt > (if (p.bound) BIND_MS else 2_000L)) { p.bindAt = now; request(CHANNEL_BIND, p.slot) }
            if (now - bindingAt > BINDING_MS) { bindingAt = now; request(BINDING) }
            // Every teammate that was live gone quiet at once: more likely ours than all of theirs, so the allocation is
            // checked now (a Refresh: "allocation mismatch" when the NAT dropped us) rather than at its renewal minutes away.
            val heardOf = peers.values.filter { it.bound && it.heardAt > 0 }
            if (heardOf.isNotEmpty() && heardOf.all { now - it.heardAt in QUIET_MS..QUIET_MS + 5_000L } && now - quietCheckAt > 10_000L) {
                quietCheckAt = now; bindingAt = now
                request(BINDING); request(REFRESH)
            }
        }
        for (p in peers.values) {
            if (!p.bound || now - p.lastOut < (if (p.live(now)) KEEP_MS else PUNCH_MS)) continue
            p.lastOut = now
            val n = random.nextInt()
            p.pings[n] = System.nanoTime()
            if (p.pings.size > 8) p.pings.keys.firstOrNull()?.let { p.pings.remove(it) }
            frame(s, p, PING, pingBody(n))
            // Not hearing them (though they may hear us, and so think all is well): told, so they send by the relay too.
            val silent = now - p.heardAt
            if (p.heardAt > 0 && silent > MISS_MS) frame(s, p, MISSING, ByteBuffer.allocate(2).putShort(silent.coerceAtMost(65_535L).toInt().toShort()).array())
        }
    }

    private fun pingBody(n: Int) = ByteBuffer.allocate(6).putInt(n).putShort(myRelayMs.coerceIn(-1, 65534).toShort()).array()

    /**
     * A [kind] of frame to each live teammate in [to] (all when null); the slots it surely reached ([Peer.sure]: heard from
     * lately). One that's live but not sure still gets it, and the caller sends it through the relay as well: TURN going
     * quiet costs well under a second, not [LIVE_MS].
     */
    fun broadcast(kind: Int, body: ByteArray, to: Set<Int>? = null): Set<Int> {
        val s = socket ?: return emptySet()
        if (state != State.READY) return emptySet()
        val now = System.currentTimeMillis()
        val out = HashSet<Int>()
        for (p in peers.values) if (p.bound && p.live(now) && (to == null || p.slot in to)) { frame(s, p, kind, body); if (p.sure(now)) out += p.slot }
        return out
    }

    /** Whether frames to [slot] surely get there this way ([Peer.sure]). */
    fun sure(slot: Int) = peers[slot]?.let { it.bound && it.sure(System.currentTimeMillis()) } == true

    /** A frame to [p]: [channel][length], then the sealed frame. */
    private fun frame(s: DatagramSocket, p: Peer, kind: Int, body: ByteArray) {
        runCatching {
            val ctr = p.sent.incrementAndGet()
            val n = HEAD + body.size + TAG
            val out = ByteArray(4 + n)
            out[0] = (p.channel shr 8).toByte(); out[1] = p.channel.toByte(); out[2] = (n shr 8).toByte(); out[3] = n.toByte()
            out[4] = ((mySlot shl 4) or kind).toByte()
            out[5] = (ctr shr 16).toByte(); out[6] = (ctr shr 8).toByte(); out[7] = ctr.toByte()
            val c = ciphers.get()
            c.init(Cipher.ENCRYPT_MODE, p.key, GCMParameterSpec(TAG * 8, nonce(mySlot, ctr)))
            c.updateAAD(out, 4, HEAD)
            c.doFinal(body, 0, body.size, out, 4 + HEAD)
            s.send(DatagramPacket(out, out.size))
            framesOut.incrementAndGet(); bytesOut.addAndGet(out.size + 28L)
        }.onFailure { if (sendFailed++ == 0) log("TURN: couldn't send: $it") }
    }

    private var sendFailed = 0

    private fun nonce(slot: Int, ctr: Long) = ByteBuffer.allocate(12).putInt(slot).putLong(ctr).array()

    private fun send(s: DatagramSocket, bytes: ByteArray) { s.send(DatagramPacket(bytes, bytes.size)); bytesOut.addAndGet(bytes.size + 28L) }

    // ------------------------------------------------------------------ in

    private fun read(s: DatagramSocket) {
        val buf = ByteArray(1500)
        while (socket === s) {
            val pk = DatagramPacket(buf, buf.size)
            try { s.receive(pk) } catch (_: Exception) { break }
            try { packet(pk.data, pk.length) } catch (e: Exception) { log("TURN: ${e.message}") }
        }
    }

    private fun packet(d: ByteArray, len: Int) {
        val now = System.currentTimeMillis()
        if (now / 1000 != window) { window = now / 1000; count = 0 }
        if (++count > 1500 || len < 4) return
        bytesIn.addAndGet(len + 28L)
        val first = d[0].toInt() and 255
        if (first in 0x40..0x7F) {
            // ChannelData: a teammate's frame, on the channel bound to them.
            val ch = ((d[0].toInt() and 255) shl 8) or (d[1].toInt() and 255)
            val n = ((d[2].toInt() and 255) shl 8) or (d[3].toInt() and 255)
            if (n > len - 4) return
            byChannel[ch]?.let { opened(it, d, 4, n, now) }
            return
        }
        if (first >= 0x40 || len < 20 || ByteBuffer.wrap(d, 4, 4).int != COOKIE) return
        val m = parseMessage(d, len) ?: return
        if (m.type == DATA_INDICATION) {
            // A teammate's frame before its channel was bound: who it is by where it came from.
            val from = m.peer ?: return; val data = m.data ?: return
            peers.values.firstOrNull { it.addr == from }?.let { opened(it, data, 0, data.size, now) }
            return
        }
        val t = txs.remove(ByteBuffer.wrap(m.tx)) ?: return
        synchronized(this) { answered(t, m, now) }
    }

    /** A sealed frame from [p] at [d][off, off + n): opened, checked, and handed on. */
    private fun opened(p: Peer, d: ByteArray, off: Int, n: Int, now: Long) {
        if (n < HEAD + TAG || n > MAX_PACKET) return
        val slot = (d[off].toInt() and 255) ushr 4
        val kind = d[off].toInt() and 15
        if (slot != p.slot) return
        // The whole counter: the low 24 bits sent, the rest from the last one heard (the nearest that fits).
        val low = ((d[off + 1].toLong() and 255) shl 16) or ((d[off + 2].toLong() and 255) shl 8) or (d[off + 3].toLong() and 255)
        val ctr = counter(p.heard, low)
        if (!p.fresh(ctr, kind in anyOrder)) return
        val body = runCatching {
            val c = ciphers.get()
            c.init(Cipher.DECRYPT_MODE, p.key, GCMParameterSpec(TAG * 8, nonce(slot, ctr)))
            c.updateAAD(d, off, HEAD)
            c.doFinal(d, off + HEAD, n - HEAD)
        }.getOrNull() ?: return
        p.mark(ctr); p.heardAt = now; p.framesIn++
        when (kind) {
            PING -> if (body.size == 6) {
                p.theirRelayMs = ByteBuffer.wrap(body, 4, 2).short.toInt()
                socket?.let { s -> frame(s, p, PONG, ByteBuffer.allocate(6).put(body, 0, 4).putShort(myRelayMs.coerceIn(-1, 65534).toShort()).array()) }
            }
            PONG -> if (body.size == 6) {
                p.theirRelayMs = ByteBuffer.wrap(body, 4, 2).short.toInt()
                val sent = p.pings.remove(ByteBuffer.wrap(body, 0, 4).int) ?: return
                val ms = (System.nanoTime() - sent) / 1e6
                p.rttMs = if (p.rttMs < 0) ms else p.rttMs * 0.7 + ms * 0.3
            }
            MISSING -> if (body.size == 2) p.missedAt = now
            else -> sink(slot, kind, body)
        }
    }

    /** A request's answer (under the lock). */
    private fun answered(t: Tx, m: Message, now: Long) {
        if (m.cls == ERROR) {
            when (m.error) {
                // Unauthorised: the realm and nonce to sign with (once; twice means the credentials are wrong or expired).
                401 -> {
                    if (authTried) { needsCredentials = true; fail("credentials turned down"); return }
                    authTried = true
                    realm = m.realm ?: return; nonce = m.nonce ?: return
                    authKey = MessageDigest.getInstance("MD5").digest("$user:${String(realm!!)}:$pass".toByteArray())
                    request(t.method, t.slot)
                }
                // Stale nonce: the new one, and again.
                438 -> { nonce = m.nonce ?: return; request(t.method, t.slot, t.tries + 1) }
                // An allocation from before (a quick restart): a new socket, so a new one.
                437 -> fail("allocation mismatch", now = true)
                else -> when (t.method) {
                    ALLOCATE, REFRESH -> fail("${methodName(t.method)} refused (${m.error})")
                    CHANNEL_BIND -> { log("TURN: channel to teammate ${t.slot} refused (${m.error})"); peers[t.slot]?.bindAt = now + 30_000L }
                }
            }
            return
        }
        if (m.cls != SUCCESS) return
        when (t.method) {
            ALLOCATE -> {
                val r = m.relayed ?: return fail("no relayed address")
                relayed = "${r.address.hostAddress}:${r.port}"
                lifetimeS = m.lifetime ?: 600
                refreshAt = now + lifetimeS * 500L
                state = State.READY; backoff = 5_000L
                log("TURN: allocated (lifetime ${lifetimeS}s, server ${"%.0f".format((now - t.sentAt).toDouble())} ms away)")
            }
            REFRESH -> { lifetimeS = m.lifetime ?: lifetimeS; refreshAt = now + lifetimeS * 500L }
            CHANNEL_BIND -> peers[t.slot]?.let { it.bound = true; it.bindAt = now }
            BINDING -> {
                val ms = (now - t.sentAt).toDouble(); serverRttMs = if (serverRttMs < 0) ms else serverRttMs * 0.7 + ms * 0.3
                val was = mapped
                m.mapped?.let { mapped = it }
                if (was != null && m.mapped != null && m.mapped != was) fail("the network changed (the server sees us at another address now)", now = true)
            }
        }
    }

    // ------------------------------------------------------------------ STUN/TURN messages

    /** Sends a [method] request (signed once the server's realm is known); [slot]: the teammate a ChannelBind is for. */
    private fun request(method: Int, slot: Int = 0, tries: Int = 0) {
        val s = socket ?: return
        val bytes = when (method) {
            ALLOCATE -> message(method, 0) { attr(REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)) }
            REFRESH -> message(method, 0) {}
            CHANNEL_BIND -> {
                val p = peers[slot] ?: return
                message(method, 0) { attr(CHANNEL_NUMBER, byteArrayOf((p.channel shr 8).toByte(), p.channel.toByte(), 0, 0)); xorAddress(XOR_PEER_ADDRESS, p.addr) }
            }
            else -> message(method, 0, signed = false) {}
        }
        txs[ByteBuffer.wrap(bytes, 8, 12).slice()] = Tx(method, slot, System.currentTimeMillis(), tries)
        runCatching { send(s, bytes) }
    }

    private inner class Builder(type: Int) {
        val b: ByteBuffer = ByteBuffer.allocate(512)
        init {
            val tx = ByteArray(12).also { random.nextBytes(it) }
            b.putShort(type.toShort()).putShort(0).putInt(COOKIE).put(tx)
        }
        fun attr(type: Int, v: ByteArray) {
            b.putShort(type.toShort()).putShort(v.size.toShort()).put(v)
            repeat((4 - v.size % 4) % 4) { b.put(0) }
        }
        fun xorAddress(type: Int, a: InetSocketAddress) {
            val ip = a.address.address
            val v = ByteBuffer.allocate(4 + ip.size)
            v.put(0).put(if (ip.size == 4) 1 else 2).putShort((a.port xor (COOKIE ushr 16)).toShort())
            val mask = ByteBuffer.allocate(16).putInt(COOKIE).put(b.array(), 8, 12).array()
            for (i in ip.indices) v.put((ip[i].toInt() xor mask[i].toInt()).toByte())
            attr(type, v.array())
        }
    }

    private fun int(v: Int) = ByteBuffer.allocate(4).putInt(v).array()

    private fun message(method: Int, cls: Int, signed: Boolean = true, body: Builder.() -> Unit): ByteArray {
        val m = Builder(method or cls)
        m.body()
        val b = m.b
        val key = authKey
        if (signed && key != null) {
            m.attr(USERNAME, user.toByteArray()); m.attr(REALM, realm!!); m.attr(NONCE, nonce!!)
            // MESSAGE-INTEGRITY over everything before it, with the length already counting it.
            b.putShort(2, (b.position() - 20 + 24).toShort())
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
            mac.update(b.array(), 0, b.position())
            m.attr(MESSAGE_INTEGRITY, mac.doFinal())
        }
        b.putShort(2, (b.position() - 20 + 8).toShort())
        val crc = CRC32().apply { update(b.array(), 0, b.position()) }
        m.attr(FINGERPRINT, int(crc.value.toInt() xor 0x5354554E))
        return b.array().copyOf(b.position())
    }

    private class Message(val type: Int, val tx: ByteArray) {
        val cls get() = type and 0x0110
        var error = 0
        var realm: ByteArray? = null
        var nonce: ByteArray? = null
        var relayed: InetSocketAddress? = null
        var peer: InetSocketAddress? = null
        var lifetime: Int? = null
        var mapped: InetSocketAddress? = null
        var data: ByteArray? = null
    }

    private fun parseMessage(d: ByteArray, len: Int): Message? {
        val b = ByteBuffer.wrap(d, 0, len)
        val type = b.short.toInt() and 0x3FFF
        val n = b.short.toInt() and 0xFFFF
        if (20 + n > len) return null
        b.int
        val tx = ByteArray(12).also { b.get(it) }
        val m = Message(type, tx)
        val end = 20 + n
        while (b.position() + 4 <= end) {
            val at = b.short.toInt() and 0xFFFF; val alen = b.short.toInt() and 0xFFFF
            val start = b.position()
            if (start + alen > end) return null
            val v = ByteArray(alen).also { b.get(it) }
            when (at) {
                ERROR_CODE -> if (alen >= 4) m.error = (v[2].toInt() and 7) * 100 + (v[3].toInt() and 255)
                REALM -> m.realm = v
                NONCE -> m.nonce = v
                XOR_RELAYED_ADDRESS -> m.relayed = xorAddress(v, tx)
                XOR_PEER_ADDRESS -> m.peer = xorAddress(v, tx)
                XOR_MAPPED_ADDRESS -> m.mapped = xorAddress(v, tx)
                LIFETIME -> if (alen == 4) m.lifetime = ByteBuffer.wrap(v).int
                DATA -> m.data = v
            }
            b.position(start + ((alen + 3) and 3.inv()).coerceAtMost(end - start))
        }
        return m
    }

    private fun xorAddress(v: ByteArray, tx: ByteArray): InetSocketAddress? {
        if (v.size < 8) return null
        val port = (((v[2].toInt() and 255) shl 8) or (v[3].toInt() and 255)) xor (COOKIE ushr 16)
        val size = if (v[1].toInt() == 1) 4 else 16
        if (v.size < 4 + size) return null
        val mask = ByteBuffer.allocate(16).putInt(COOKIE).put(tx).array()
        val ip = ByteArray(size) { (v[4 + it].toInt() xor mask[it].toInt()).toByte() }
        return runCatching { InetSocketAddress(InetAddress.getByAddress(ip), port) }.getOrNull()
    }

    private fun methodName(m: Int) = when (m) { ALLOCATE -> "allocation"; REFRESH -> "renewal"; CHANNEL_BIND -> "channel"; else -> "binding" }

    companion object {
        const val PING = 0
        const val PONG = 1
        /** "Nothing from you for [the u16, ms]": sent with each ping while it's so, past [MISS_MS] (older games ignore the kind). */
        const val MISSING = 2
        /** Silent this long is worth saying: past a ping's gap and some jitter. */
        const val MISS_MS = 450L
        /** A teammate isn't [Peer.sure] for this long after it said it misses us (it says so every [KEEP_MS] while it does). */
        const val MISSED_FOR_MS = 700L
        private const val HEAD = 4

        /** A frame's whole counter from its low 24 bits and the last one heard (the nearest that fits). */
        fun counter(heard: Long, low: Long): Long {
            var ctr = (heard and 0xFFFFFFL.inv()) or low
            if (ctr < heard - 0x800000) ctr += 0x1000000 else if (ctr > heard + 0x800000 && ctr >= 0x1000000) ctr -= 0x1000000
            return ctr
        }
        private const val TAG = 12
        private const val MAX_PACKET = 300
        /** Pings to a teammate: this often until it's live, and this often once it is (each side's keep the other's [SURE_MS]). */
        const val PUNCH_MS = 200L
        const val KEEP_MS = 250L
        /** A teammate heard from within this is live: frames go to it this way. */
        const val LIVE_MS = 2500L
        /** ... and heard from within this, surely getting them (a few pings lost in a row is a link in trouble). */
        const val SURE_MS = 700L
        /** Every live teammate silent this long: the allocation is checked at once (a NAT that dropped its mapping loses it). */
        private const val QUIET_MS = 3000L
        private const val RETRY_MS = 250L
        /** Channels last 10 minutes and the permission they bring 5: renewed well inside. */
        private const val BIND_MS = 120_000L
        private const val BINDING_MS = 10_000L
        private const val COOKIE = 0x2112A442

        private const val BINDING = 0x0001
        private const val ALLOCATE = 0x0003
        private const val REFRESH = 0x0004
        private const val CHANNEL_BIND = 0x0009
        private const val DATA_INDICATION = 0x0017
        private const val SUCCESS = 0x0100
        private const val ERROR = 0x0110

        private const val USERNAME = 0x0006
        private const val MESSAGE_INTEGRITY = 0x0008
        private const val ERROR_CODE = 0x0009
        private const val CHANNEL_NUMBER = 0x000C
        private const val LIFETIME = 0x000D
        private const val XOR_PEER_ADDRESS = 0x0012
        private const val DATA = 0x0013
        private const val REALM = 0x0014
        private const val NONCE = 0x0015
        private const val XOR_RELAYED_ADDRESS = 0x0016
        private const val XOR_MAPPED_ADDRESS = 0x0020
        private const val REQUESTED_TRANSPORT = 0x0019
        private const val FINGERPRINT = 0x8028
    }
}
