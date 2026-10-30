package com.devgineerclient.betterrecorder

import com.devgineerclient.recorder.Rec
import com.devgineerclient.recorder.RecorderSession
import com.devgineerclient.recorder.WireTap
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.ProtocolInfo
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.login.ServerboundKeyPacket

/**
 * Better Recorder's copy of the wire: every frame of the game connection both ways, as raw bytes (packet id and payload,
 * after decompression on the way in, before compression on the way out), from BrecDecoderMixin and BrecEncoderMixin on
 * the connection's netty thread. Only copies bytes and takes a lock held for a list append.
 *
 * Three places a frame can go:
 *  - the prelude: the configuration phase (registries, tags, known packs, both ways) and the play login packet, kept per
 *    connection so a recording that starts mid-world can still be decoded (the login phase, with its keys, is never kept);
 *  - the world buffer: the play frames since this world began (login or respawn), while [buffering] (Hypixel), so the
 *    chunks and entities sent before ADR knows it's a dungeon run are in the recording; capped at [WORLD_CAP], newest
 *    dropped past it (counted);
 *  - the live session, once [goLive] has written the prelude and the world buffer into it.
 * Each frame takes its sequence number ([Rec.nextSeq]), its time and the client tick as it is copied.
 */
object BrecWire {
    class Frame(val seq: Long, val ns: Long, val tick: Int, val dir: Int, val phase: Int, val bytes: ByteArray?, val len: Int)

    private val lock = Any()
    private val prelude = ArrayList<Frame>()
    private var preludeBytes = 0L
    private val world = ArrayList<Frame>()
    private var worldBytes = 0L
    /** Frames the world buffer had no room for since it began. */
    var worldDropped = 0L; private set
    private var lastPhase: ConnectionProtocol? = null
    /** The last inbound play frame copied (the one being decoded, when [decoded] runs). */
    private var lastIn: Frame? = null

    /** Copy frames at all (the module is on). Written by the game thread. */
    @Volatile var on = false
    /** Keep the world buffer (on Hypixel). Written by the game thread. */
    @Volatile var buffering = false
    /** The client tick frames are stamped with. Written by the game thread. */
    @Volatile var tick = 0

    private var live: RecorderSession? = null
    /** Live frames' (seq, ns) since the last [drainTimes], for the tick line. */
    private var times = LongArray(1024)
    private var timesN = 0

    /** BrecDecoderMixin, at PacketDecoder.decode HEAD. */
    fun inbound(info: ProtocolInfo<*>, buf: ByteBuf) {
        if (!on || info.flow() != PacketFlow.CLIENTBOUND) return
        val phase = info.id()
        if (phase == ConnectionProtocol.STATUS || phase == ConnectionProtocol.HANDSHAKING) return
        frame(0, phase, buf, buf.readerIndex(), buf.readableBytes(), false)
    }

    /** BrecDecoderMixin, at PacketDecoder.decode RETURN: [out] holds what the frame copied at HEAD decoded to. */
    fun decoded(out: List<Any?>) {
        if (on && out.lastOrNull() is ClientboundRespawnPacket) respawned()
    }

    /** BrecEncoderMixin, at PacketEncoder.encode RETURN: [out] holds the frame from [start]. */
    fun outbound(info: ProtocolInfo<*>, packet: Packet<*>, out: ByteBuf, start: Int) {
        if (!on || info.flow() != PacketFlow.SERVERBOUND) return
        val phase = info.id()
        if (phase == ConnectionProtocol.STATUS || phase == ConnectionProtocol.HANDSHAKING) return
        val len = out.writerIndex() - start
        if (len < 0) return
        // The encryption key and cookies are left out (their length kept); everything else is the record.
        val withheld = packet is ServerboundKeyPacket || packet is ServerboundCookieResponsePacket
        frame(1, phase, out, start, len, withheld)
    }

    /** Copies [len] bytes of [buf] from [at] (none when [withheld]), only once it is known the frame is kept. */
    private fun frame(dir: Int, phase: ConnectionProtocol, buf: ByteBuf, at: Int, len: Int, withheld: Boolean) {
        synchronized(lock) {
            val was = lastPhase
            lastPhase = phase
            if (phase == ConnectionProtocol.LOGIN) {
                // A new connection: nothing from the last one applies.
                if (was != ConnectionProtocol.LOGIN) { clearPrelude(); clearWorld() }
                return
            }
            // A play frame nobody wants (not live, the world buffer off or full) is not even copied.
            val s = live
            if (phase == ConnectionProtocol.PLAY && was == ConnectionProtocol.PLAY && s == null) {
                if (dir == 0) lastIn = null
                if (!buffering) return
                if (worldBytes + len > WORLD_CAP) {
                    worldDropped++
                    // Still kept aside if small: it may be the respawn that starts the next world (warping from a full hub).
                    if (dir == 0 && len <= 1024) lastIn = Frame(Rec.nextSeq(), System.nanoTime(), tick, 0, WireTap.phaseId(phase), ByteBufUtil.getBytes(buf, at, len), len)
                    return
                }
            }
            val bytes = if (withheld) null else ByteBufUtil.getBytes(buf, at, len)
            val f = Frame(Rec.nextSeq(), System.nanoTime(), tick, dir, WireTap.phaseId(phase), bytes, len)
            if (phase == ConnectionProtocol.CONFIGURATION) {
                // A reconfiguration starts the prelude over.
                if (was == ConnectionProtocol.PLAY) { clearPrelude(); clearWorld() }
                addPrelude(f)
                return
            }
            if (was == ConnectionProtocol.CONFIGURATION) {
                // The first play frame is the login packet: the world starts with it.
                addPrelude(f)
                clearWorld()
                return
            }
            if (dir == 0) lastIn = f
            if (s != null) {
                s.raw(f.seq, dir, f.phase, bytes, withheld, len)
                if (timesN + 2 > times.size) times = times.copyOf(times.size * 2)
                times[timesN++] = f.seq; times[timesN++] = f.ns
                return
            }
            world += f; worldBytes += len + 48
        }
    }

    /** A respawn packet was just decoded (from the last inbound frame): the world buffer starts again from it. */
    private fun respawned() {
        synchronized(lock) {
            clearWorld()
            val f = lastIn
            if (live == null && buffering && f != null) { world += f; worldBytes = f.len + 48L }
        }
    }

    /**
     * Writes the prelude and the world buffer into [s] and sends every frame from now on straight to it, all under the
     * frame lock so nothing is missed or reordered. Returns the frames written (for the `pre` line) and how many the world
     * buffer dropped.
     */
    fun goLive(s: RecorderSession): Pair<List<Frame>, Long> = synchronized(lock) {
        val out = ArrayList<Frame>(prelude.size + world.size)
        out += prelude; out += world
        for (f in out) s.raw(f.seq, f.dir, f.phase, f.bytes, f.bytes == null && f.len > 0, f.len)
        val dropped = worldDropped
        clearWorld()
        live = s
        timesN = 0
        out to dropped
    }

    /** Stops sending frames to [s] (if it is the live one). */
    fun stopLive(s: RecorderSession) = synchronized(lock) { if (live === s) { live = null; timesN = 0 } }

    /** The live frames' (seq, ns) pairs since the last call. */
    fun drainTimes(): LongArray = synchronized(lock) { times.copyOf(timesN).also { timesN = 0 } }

    /** Off Hypixel: the world buffer is not wanted. */
    fun dropWorld() { if (worldBytes > 0) synchronized(lock) { if (live == null) clearWorld() } }

    /** The module was turned off: nothing held is wanted. */
    fun reset() = synchronized(lock) { clearPrelude(); clearWorld(); lastPhase = null; lastIn = null; live = null; timesN = 0 }

    private fun addPrelude(f: Frame) {
        if (preludeBytes + f.len > PRELUDE_CAP) return
        prelude += f; preludeBytes += f.len + 48
    }

    private fun clearPrelude() { prelude.clear(); preludeBytes = 0; lastIn = null }
    private fun clearWorld() { world.clear(); worldBytes = 0; worldDropped = 0 }

    private const val PRELUDE_CAP = 16L shl 20
    private const val WORLD_CAP = 48L shl 20
}
