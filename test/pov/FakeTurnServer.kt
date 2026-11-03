package com.engineerclient.pov

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * A TURN server on loopback, as much of RFC 8656 as [TurnLink] uses: Allocate (after a 401 with a realm and nonce, as
 * Cloudflare's does), Refresh, ChannelBind, Binding, and relaying ChannelData between its allocations (as a Data indication
 * when the receiver has no channel to the sender yet). Relayed addresses are made-up ports on 127.0.0.1; frames between
 * allocations go through [impair] (delay, loss, reordering, outages), each way on its own.
 */
class FakeTurnServer(private val seed: Long = 1) : AutoCloseable {
    /** What the network does to a relayed frame: its delay (ms), or null when it's lost. Changeable while running. */
    @Volatile var impair: (Random) -> Double? = { 0.0 }
    /** One route keeps one flow in order (a held frame holds the ones behind it); off, each frame takes its own time. */
    @Volatile var fifo = true
    /** Frames from this allocation (its relayed "ip:port") are lost: an outage one way only. */
    @Volatile var cutFrom: String? = null
    /** The NAT moved every client to another port: the server sees new addresses, so their allocations are lost to them. */
    @Volatile var rebound = false
    private fun key(from: InetSocketAddress) = if (rebound) InetSocketAddress(from.address, from.port xor 0x2000) else from
    private val lastDue = ConcurrentHashMap<InetSocketAddress, Long>()
    private val rnd = Random(seed)
    private val socket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    val port: Int get() = socket.localPort
    /** Frames on their way: run in order of (due, sent), so equal times keep their order (a scheduled executor doesn't). */
    private class Due(val at: Long, val seq: Long, val run: Runnable)
    private val pending = java.util.concurrent.PriorityBlockingQueue<Due>(64, compareBy<Due>({ it.at }, { it.seq }))
    private val seq = java.util.concurrent.atomic.AtomicLong()
    private val deliverer = Thread({
        while (running) {
            val d = pending.peek()
            if (d == null) { java.util.concurrent.locks.LockSupport.parkNanos(200_000); continue }
            val wait = d.at - System.nanoTime()
            if (wait > 0) { java.util.concurrent.locks.LockSupport.parkNanos(minOf(wait, 500_000)); continue }
            pending.poll()?.run?.run()
        }
    }, "fake-turn-delay").apply { isDaemon = true }

    private class Allocation(val client: InetSocketAddress, val relayed: InetSocketAddress) {
        /** channel -> peer relayed address, and back. */
        val channels = ConcurrentHashMap<Int, InetSocketAddress>()
        val byPeer = ConcurrentHashMap<InetSocketAddress, Int>()
    }
    private val byClient = ConcurrentHashMap<InetSocketAddress, Allocation>()
    private val byRelayed = ConcurrentHashMap<InetSocketAddress, Allocation>()
    private var nextPort = 40000
    @Volatile private var running = true
    @Volatile var allocations = 0; private set
    @Volatile var challenged = 0; private set
    @Volatile var relayedFrames = 0L; private set
    @Volatile var droppedFrames = 0L; private set

    init {
        Thread({ loop() }, "fake-turn").apply { isDaemon = true }.start()
        deliverer.start()
    }

    private fun loop() {
        val buf = ByteArray(2048)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try { socket.receive(p) } catch (_: Exception) { break }
            val from = InetSocketAddress(p.address, p.port)
            val d = p.data.copyOf(p.length)
            runCatching { handle(from, d) }
        }
    }

    private fun handle(from: InetSocketAddress, d: ByteArray) {
        val first = d[0].toInt() and 255
        if (first in 0x40..0x7F) { channelData(key(from), d); return }
        val b = ByteBuffer.wrap(d)
        val type = b.short.toInt() and 0x3FFF
        b.short; val cookie = b.int
        if (cookie != COOKIE) return
        val tx = ByteArray(12).also { b.get(it) }
        val attrs = attrs(d)
        val signed = attrs.containsKey(MESSAGE_INTEGRITY)
        when (type) {
            ALLOCATE -> {
                if (!signed) { challenged++; send(from, error(type, tx, 401) { attr(REALM, "fake".toByteArray()); attr(NONCE, "n0nce".toByteArray()) }); return }
                val a = byClient.getOrPut(key(from)) {
                    allocations++
                    Allocation(from, InetSocketAddress(InetAddress.getLoopbackAddress(), synchronized(this) { nextPort++ })).also { byRelayed[it.relayed] = it }
                }
                send(from, success(type, tx) { xorAddress(XOR_RELAYED_ADDRESS, a.relayed); attr(LIFETIME, ByteBuffer.allocate(4).putInt(600).array()) })
            }
            REFRESH -> if (byClient[key(from)] == null) send(from, error(type, tx, 437) {}) else send(from, success(type, tx) { attr(LIFETIME, ByteBuffer.allocate(4).putInt(600).array()) })
            CHANNEL_BIND -> {
                val a = byClient[key(from)] ?: run { send(from, error(type, tx, 437) {}); return }
                val ch = attrs[CHANNEL_NUMBER]?.let { ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255) } ?: return
                val peer = attrs[XOR_PEER_ADDRESS]?.let { unXor(it) } ?: return
                a.channels[ch] = peer; a.byPeer[peer] = ch
                send(from, success(type, tx) {})
            }
            BINDING -> send(from, success(type, tx) { xorAddress(XOR_MAPPED_ADDRESS, key(from)) })
        }
    }

    /** A client's ChannelData: to the peer its channel is bound to, through the network's impairment. */
    private fun channelData(from: InetSocketAddress, d: ByteArray) {
        val a = byClient[from] ?: return
        val ch = ((d[0].toInt() and 255) shl 8) or (d[1].toInt() and 255)
        val n = ((d[2].toInt() and 255) shl 8) or (d[3].toInt() and 255)
        val peer = a.channels[ch] ?: return
        val to = byRelayed[peer] ?: return
        if (cutFrom == "${a.relayed.address.hostAddress}:${a.relayed.port}") { droppedFrames++; return }
        val data = d.copyOfRange(4, 4 + n)
        val delay = synchronized(rnd) { impair(rnd) }
        if (delay == null) { droppedFrames++; return }
        val deliver = Runnable {
            relayedFrames++
            val back = to.byPeer[a.relayed]
            if (back != null) {
                val out = ByteArray(4 + data.size)
                out[0] = (back shr 8).toByte(); out[1] = back.toByte(); out[2] = (data.size shr 8).toByte(); out[3] = data.size.toByte()
                data.copyInto(out, 4)
                send(to.client, out)
            } else send(to.client, indication(DATA_INDICATION) { xorAddress(XOR_PEER_ADDRESS, a.relayed); attr(DATA, data) })
        }
        var due = System.nanoTime() + (delay * 1_000_000).toLong()
        if (fifo) synchronized(lastDue) { due = maxOf(due, lastDue[a.relayed] ?: 0L); lastDue[a.relayed] = due }
        pending.add(Due(due, seq.incrementAndGet(), deliver))
    }

    private fun send(to: InetSocketAddress, bytes: ByteArray) { runCatching { socket.send(DatagramPacket(bytes, bytes.size, to)) } }

    // ------------------------------------------------------------------ STUN

    private class Msg(type: Int, tx: ByteArray) {
        val b: ByteBuffer = ByteBuffer.allocate(1024).putShort(type.toShort()).putShort(0).putInt(COOKIE).put(tx)
        val tx = tx
        fun attr(t: Int, v: ByteArray) { b.putShort(t.toShort()).putShort(v.size.toShort()).put(v); repeat((4 - v.size % 4) % 4) { b.put(0) } }
        fun xorAddress(t: Int, a: InetSocketAddress) {
            val ip = a.address.address
            val v = ByteBuffer.allocate(8).put(0).put(1).putShort((a.port xor (COOKIE ushr 16)).toShort())
            val mask = ByteBuffer.allocate(4).putInt(COOKIE).array()
            for (i in 0 until 4) v.put((ip[i].toInt() xor mask[i].toInt()).toByte())
            attr(t, v.array())
        }
        fun bytes(): ByteArray { b.putShort(2, (b.position() - 20).toShort()); return b.array().copyOf(b.position()) }
    }

    private fun success(method: Int, tx: ByteArray, body: Msg.() -> Unit) = Msg(method or 0x0100, tx).apply(body).bytes()
    private fun error(method: Int, tx: ByteArray, code: Int, body: Msg.() -> Unit) =
        Msg(method or 0x0110, tx).apply { attr(ERROR_CODE, byteArrayOf(0, 0, (code / 100).toByte(), (code % 100).toByte())); body() }.bytes()
    private fun indication(type: Int, body: Msg.() -> Unit) = Msg(type, ByteArray(12).also { synchronized(rnd) { rnd.nextBytes(it) } }).apply(body).bytes()

    private fun attrs(d: ByteArray): Map<Int, ByteArray> {
        val out = HashMap<Int, ByteArray>()
        val b = ByteBuffer.wrap(d)
        b.position(2); val n = b.short.toInt() and 0xFFFF; b.position(20)
        while (b.position() + 4 <= minOf(d.size, 20 + n)) {
            val t = b.short.toInt() and 0xFFFF; val len = b.short.toInt() and 0xFFFF
            if (b.position() + len > d.size) break
            out[t] = ByteArray(len).also { b.get(it) }
            b.position(minOf(d.size, b.position() + (4 - len % 4) % 4))
        }
        return out
    }

    private fun unXor(v: ByteArray): InetSocketAddress {
        val port = (((v[2].toInt() and 255) shl 8) or (v[3].toInt() and 255)) xor (COOKIE ushr 16)
        val mask = ByteBuffer.allocate(4).putInt(COOKIE).array()
        val ip = ByteArray(4) { (v[4 + it].toInt() xor mask[it].toInt()).toByte() }
        return InetSocketAddress(InetAddress.getByAddress(ip), port)
    }

    override fun close() { running = false; socket.close() }

    companion object {
        private const val COOKIE = 0x2112A442
        private const val BINDING = 0x0001
        private const val ALLOCATE = 0x0003
        private const val REFRESH = 0x0004
        private const val CHANNEL_BIND = 0x0009
        private const val DATA_INDICATION = 0x0017
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
    }
}
