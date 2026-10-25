package com.devgineerclient.posepov

import com.devgineerclient.DevgineerClient
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Peer-to-peer for [PoseShare] ("Peer-to-peer sharing"): your poses and terminal straight to each linked teammate who also
 * has it on, over UDP, instead of round the relay. The relay still introduces you: it hands each pair the other's address
 * (the one it sees them connect from, with the port they learned from STUN) and a fresh key, so only teammates who are
 * linked with you and have it on ever learn your address, and nobody without the key can send you anything that's read.
 *
 * Each packet, as small as it goes (a look frame is 18 bytes inside UDP): [sender slot << 4 | kind][counter, low 24 bits]
 * [AES-GCM of the body, tag 8 bytes]. The header is the associated data; the nonce is the slot plus the whole counter (worked
 * out from its low bits and the last one heard), so the two directions never share one. A counter not past the last one heard
 * is dropped (replays). Kinds: [PING], [PONG], and PoseShare's (its D_ ones).
 * Two NATs are opened to each other by both sides sending first (hole punching); when that fails the relay is all there is.
 */
object PeerLink {
    const val PING = 0
    const val PONG = 1
    private const val HEAD = 4
    private const val TAG = 8
    private const val MAX_PACKET = 96
    private const val PUNCH_MS = 200L
    private const val KEEP_MS = 1000L
    /** A peer heard from within this is live: frames go to it directly. */
    private const val LIVE_MS = 2500L
    private const val STUN_MS = 25_000L
    private val STUN = listOf("stun.cloudflare.com" to 3478, "stun.l.google.com" to 19302)
    private const val COOKIE = 0x2112A442

    class Peer(val slot: Int, @Volatile var addr: InetSocketAddress, val key: SecretKeySpec) {
        var sent = 0L
        @Volatile var heard = 0L
        @Volatile var heardAt = 0L
        @Volatile var rttMs = -1.0
        var lastOut = 0L
        val pings = ConcurrentHashMap<Int, Long>()
        fun live(now: Long) = now - heardAt < LIVE_MS
    }

    private val peers = ConcurrentHashMap<Int, Peer>()
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var v6 = false
    private var mySlot = 0
    @Volatile private var port = 0
    private var stunAt = 0L
    private val stunTx = ByteArray(12)
    private val random = SecureRandom()
    /** Packets read this second, for dropping a flood (only a teammate who has your address could send one). */
    private var window = 0L
    private var count = 0
    /** One cipher per thread (the game's sends, the reader's answers): made once, not per packet. */
    private val ciphers = ThreadLocal.withInitial { Cipher.getInstance("AES/GCM/NoPadding") }

    val running get() = socket != null

    /** Opens the socket (once signed in: [slot] is ours, [ipv6] how the relay sees us) and finds our public port. */
    fun start(slot: Int, ipv6: Boolean) {
        mySlot = slot; v6 = ipv6
        if (socket != null) { stunAt = 0L; return }
        val s = runCatching { DatagramSocket(0) }.getOrNull() ?: return
        socket = s
        port = 0; stunAt = 0L
        Thread.ofPlatform().name("devgineerclient-pose-p2p").daemon(true).start { read(s) }
    }

    fun stop() {
        val s = socket
        socket = null; port = 0
        peers.clear()
        runCatching { s?.close() }
    }

    fun add(slot: Int, address: String, keyHex: String) {
        if (socket == null || slot == mySlot) return
        val addr = parse(address) ?: return
        if (!Regex("[0-9a-f]{64}").matches(keyHex)) return
        val key = SecretKeySpec(ByteArray(32) { keyHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, "AES")
        peers[slot] = Peer(slot, addr, key)
    }

    fun remove(slot: Int) { peers.remove(slot) }
    fun retain(slots: Set<Int>) { peers.keys.retainAll(slots) }

    fun live(slot: Int) = peers[slot]?.live(System.currentTimeMillis()) == true
    fun rttMs(slot: Int) = peers[slot]?.rttMs ?: -1.0

    /** "a.b.c.d:port" or "[v6]:port", as the relay writes them. */
    private fun parse(a: String): InetSocketAddress? = runCatching {
        val i = a.lastIndexOf(':')
        val host = a.substring(0, i).removePrefix("[").removeSuffix("]")
        val p = a.substring(i + 1).toInt()
        // A literal address only: never a name to look up.
        if (!Regex("[0-9a-fA-F.:]{2,45}").matches(host) || p !in 1..65535) return null
        InetSocketAddress(InetAddress.ofLiteral(host), p)
    }.getOrNull()

    /**
     * Every tick: STUN now and then (which also keeps our NAT's mapping open; a changed port goes to the relay through
     * [report]), punching towards peers not yet heard, and a ping a second to live ones (the round trip, for the delay shown).
     */
    fun tick(report: (Int) -> Unit) {
        val s = socket ?: return
        val now = System.currentTimeMillis()
        if (now - stunAt > (if (port == 0) 3000 else STUN_MS)) { stunAt = now; stun(s) }
        if (port != 0) report(port)
        for (p in peers.values) {
            if (now - p.lastOut < (if (p.live(now)) KEEP_MS else PUNCH_MS)) continue
            p.lastOut = now
            val nonce = random.nextInt()
            p.pings[nonce] = System.nanoTime()
            if (p.pings.size > 8) p.pings.keys.firstOrNull()?.let { p.pings.remove(it) }
            send(p, PING, ByteBuffer.allocate(4).putInt(nonce).array())
        }
    }

    /** A [kind] of frame to every live peer; the slots it went to (so the relay can be skipped when it reached everyone). */
    fun broadcast(kind: Int, body: ByteArray): Set<Int> {
        val now = System.currentTimeMillis()
        val out = HashSet<Int>()
        for (p in peers.values) if (p.live(now)) { send(p, kind, body); out += p.slot }
        return out
    }

    private fun send(p: Peer, kind: Int, body: ByteArray) {
        val s = socket ?: return
        runCatching {
            val ctr = ++p.sent
            val out = ByteArray(HEAD + body.size + TAG)
            out[0] = ((mySlot shl 4) or kind).toByte()
            out[1] = (ctr shr 16).toByte(); out[2] = (ctr shr 8).toByte(); out[3] = ctr.toByte()
            val c = ciphers.get()
            c.init(Cipher.ENCRYPT_MODE, p.key, GCMParameterSpec(TAG * 8, nonce(mySlot, ctr)))
            c.updateAAD(out, 0, HEAD)
            c.doFinal(body, 0, body.size, out, HEAD)
            s.send(DatagramPacket(out, out.size, p.addr))
        }
    }

    private fun nonce(slot: Int, ctr: Long) = ByteBuffer.allocate(12).putInt(slot).putLong(ctr).array()

    private fun read(s: DatagramSocket) {
        val buf = ByteArray(1500)
        while (socket === s) {
            val pk = DatagramPacket(buf, buf.size)
            try { s.receive(pk) } catch (_: Exception) { break }
            DevgineerClient.safely("pose p2p") { packet(pk) }
        }
    }

    private fun packet(pk: DatagramPacket) {
        val now = System.currentTimeMillis()
        if (now / 1000 != window) { window = now / 1000; count = 0 }
        if (++count > 1500) return
        val d = pk.data; val len = pk.length
        // STUN's answer: a binding success, with the magic cookie where it puts it.
        if (len >= 20 && d[0].toInt() == 0x01 && d[1].toInt() == 0x01 && ByteBuffer.wrap(d, 4, 4).int == COOKIE) { stunReply(d, len); return }
        if (len < HEAD + TAG || len > MAX_PACKET) return
        val slot = (d[0].toInt() and 255) ushr 4
        val kind = d[0].toInt() and 15
        val p = peers[slot] ?: return
        // The whole counter: the low 24 bits sent, the rest from the last one heard (the nearest that fits).
        val low = ((d[1].toLong() and 255) shl 16) or ((d[2].toLong() and 255) shl 8) or (d[3].toLong() and 255)
        var ctr = (p.heard and 0xFFFFFFL.inv()) or low
        if (ctr < p.heard - 0x800000) ctr += 0x1000000 else if (ctr > p.heard + 0x800000 && ctr >= 0x1000000) ctr -= 0x1000000
        if (ctr <= p.heard) return
        val frame = runCatching {
            val c = ciphers.get()
            c.init(Cipher.DECRYPT_MODE, p.key, GCMParameterSpec(TAG * 8, nonce(slot, ctr)))
            c.updateAAD(d, 0, HEAD)
            c.doFinal(d, HEAD, len - HEAD)
        }.getOrNull() ?: return
        p.heard = ctr; p.heardAt = now
        // Wherever it really comes from (a NAT may map it differently than STUN said), that's where to answer.
        p.addr = pk.socketAddress as InetSocketAddress
        when (kind) {
            PING -> if (frame.size == 4) send(p, PONG, frame)
            PONG -> if (frame.size == 4) {
                val sent = p.pings.remove(ByteBuffer.wrap(frame).int) ?: return
                val ms = (System.nanoTime() - sent) / 1e6
                p.rttMs = if (p.rttMs < 0) ms else p.rttMs * 0.7 + ms * 0.3
            }
            else -> PoseShare.direct(slot, kind, frame)
        }
    }

    // ------------------------------------------------------------------ STUN (RFC 5389: a binding request, the mapped port)

    private fun stun(s: DatagramSocket) {
        Thread.ofPlatform().name("devgineerclient-pose-stun").daemon(true).start {
            for ((host, p) in STUN) {
                val addr = runCatching { InetAddress.getAllByName(host).firstOrNull { if (v6) it is Inet6Address else it is Inet4Address } }.getOrNull() ?: continue
                random.nextBytes(stunTx)
                val req = ByteBuffer.allocate(20).putShort(0x0001).putShort(0).putInt(COOKIE).put(stunTx).array()
                runCatching { s.send(DatagramPacket(req, 20, InetSocketAddress(addr, p))) }
                return@start
            }
        }
    }

    private fun stunReply(d: ByteArray, len: Int) {
        val b = ByteBuffer.wrap(d, 0, len)
        b.position(4)
        if (b.int != COOKIE) return
        val tx = ByteArray(12).also { b.get(it) }
        if (!tx.contentEquals(stunTx)) return
        while (b.remaining() >= 4) {
            val type = b.short.toInt() and 0xFFFF; val alen = b.short.toInt() and 0xFFFF
            if (alen > b.remaining()) return
            val at = b.position()
            // XOR-MAPPED-ADDRESS: the port, xored with the cookie's top half.
            if (type == 0x0020 && alen >= 4) {
                b.get(); b.get()
                port = (b.short.toInt() and 0xFFFF) xor (COOKIE ushr 16)
                return
            }
            b.position(at + ((alen + 3) and 3.inv()))
        }
    }
}
