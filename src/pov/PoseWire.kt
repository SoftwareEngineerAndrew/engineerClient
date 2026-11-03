package com.engineerclient.pov

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import java.nio.ByteBuffer

/**
 * [PoseShare]'s frames, byte for byte, and when to send them ([Sender]): no game state, so test/pov/sim can drive the real
 * thing. Kinds over TURN ([TurnLink]'s header): [D_TICK] [time u16, ms][x y z: 24-bit][yaw u16][pitch i16][flags]([item u16]
 * when flag 0x80), a tick at a time; [D_LOOK] [time u16][yaw u16][pitch i16], every frame the look changes in. Through the
 * relay: [relay]'s kind 1 (yourself) and 2 (a teammate you see).
 */
object PoseWire {
    const val D_TICK = 9
    const val D_LOOK = 10
    const val TICK_NS = 50_000_000L
    /** While still, your pose is sent this often rather than every tick (well inside [PoseTrack.STALE_NS]). */
    const val STILL_NS = 100_000_000L

    fun yaw16(yaw: Float) = (Mth.wrapDegrees(yaw) / 360f * 65536f).toInt()
    fun pitch16(pitch: Float) = (pitch.coerceIn(-90f, 90f) / 90f * 32767f).toInt()
    /** A look as one number: yaw (16 bits) over pitch (16 bits). */
    fun look(yaw: Float, pitch: Float): Long = ((yaw16(yaw) and 0xFFFF).toLong() shl 16) or (pitch16(pitch) and 0xFFFF).toLong()

    fun put24(b: ByteBuffer, v: Double) {
        val i = (v * 256).toInt().coerceIn(-0x800000, 0x7FFFFF)
        b.put((i shr 16).toByte()).put((i shr 8).toByte()).put(i.toByte())
    }

    fun get24(b: ByteBuffer): Double {
        val i = ((b.get().toInt() shl 16) or ((b.get().toInt() and 255) shl 8) or (b.get().toInt() and 255))
        return i / 256.0
    }

    /** A [D_TICK] body; [item] null leaves it out. */
    fun tickBody(ms: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float, flags: Int, item: Int?): ByteArray {
        val b = ByteBuffer.allocate(if (item != null) 18 else 16)
        b.putShort(ms.toShort())
        put24(b, x); put24(b, y); put24(b, z)
        b.putShort(yaw16(yaw).toShort())
        b.putShort(pitch16(pitch).toShort())
        b.put((flags or (if (item != null) 0x80 else 0)).toByte())
        if (item != null) b.putShort(item.toShort())
        return b.array()
    }

    fun lookBody(ms: Int, look: Long) =
        byteArrayOf((ms shr 8).toByte(), ms.toByte(), (look shr 24).toByte(), (look shr 16).toByte(), (look shr 8).toByte(), look.toByte())

    /** A relay pose frame: [kind]([party index][age])[seq u16][x y z: 24-bit, 1/256 block][yaw u16][pitch i16][flags][half rtt /2 ms][item u16]. */
    fun relay(kind: Int, index: Int, age: Int, seq: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float, flags: Int, rttMs: Double, item: Int): ByteArray {
        val b = ByteBuffer.allocate(if (kind == 2) 22 else 20)
        b.put(kind.toByte())
        if (kind == 2) b.put(index.toByte()).put(age.toByte())
        b.putShort(seq.toShort())
        put24(b, x); put24(b, y); put24(b, z)
        b.putShort(yaw16(yaw).toShort())
        b.putShort(pitch16(pitch).toShort())
        b.put(flags.toByte())
        b.put((if (rttMs > 0) (rttMs / 4).toInt().coerceIn(0, 255) else 0).toByte())
        b.putShort(item.toShort())
        return b.array()
    }

    /** A pose as read: [raw] its 16-bit tick or ms; [pos] null for a look; [item] -1 when it didn't come; [halfMs] the sender's half round trip. */
    class Heard(val raw: Long, val pos: Vec3?, val yaw: Float, val pitch: Float, val flags: Int, val item: Int, val halfMs: Int)

    /** A [D_TICK] body, or null when it isn't one. */
    fun readTick(body: ByteArray): Heard? {
        if (!(body.size == 16 || (body.size == 18 && body[15].toInt() and 0x80 != 0))) return null
        val b = ByteBuffer.wrap(body)
        val ms = b.short.toLong() and 0xFFFF
        val pos = Vec3(get24(b), get24(b), get24(b))
        val yaw = (b.short.toInt() and 0xFFFF) / 65536f * 360f
        val pitch = b.short / 32767f * 90f
        val flags = b.get().toInt() and 255
        val item = if (flags and 0x80 != 0) b.short.toInt() and 0xFFFF else -1
        return Heard(ms, pos, yaw, pitch, flags and 0x7F, item, 0)
    }

    /** A [D_LOOK] body, or null when it isn't one. */
    fun readLook(body: ByteArray): Heard? {
        if (body.size != 6) return null
        val b = ByteBuffer.wrap(body)
        val ms = b.short.toLong() and 0xFFFF
        val yaw = (b.short.toInt() and 0xFFFF) / 65536f * 360f
        val pitch = b.short / 32767f * 90f
        return Heard(ms, null, yaw, pitch, 0, -1, 0)
    }

    /** A relay pose frame's body (after its kind, and a seen one's index and age). */
    fun readRelay(b: ByteBuffer): Heard {
        val seq = b.short.toLong() and 0xFFFF
        val pos = Vec3(get24(b), get24(b), get24(b))
        val yaw = (b.short.toInt() and 0xFFFF) / 65536f * 360f
        val pitch = b.short / 32767f * 90f
        val flags = b.get().toInt() and 255
        val half = (b.get().toInt() and 255) * 2
        val item = b.short.toInt() and 0xFFFF
        return Heard(seq, pos, yaw, pitch, flags, item, half)
    }

    /**
     * Whether a teammate's frames go over TURN (else the relay): only while TURN is live to them, and with "Optimize server
     * location" whichever is quicker (half TURN's round trip against your way to the relay plus theirs from it), switching only
     * for a clear 5 ms so it doesn't flap. [was] the last choice (null at first).
     */
    fun viaTurn(was: Boolean?, live: Boolean, optimize: Boolean, turnMs: Double, relayMs: Double): Boolean {
        var fast = live
        if (fast && optimize && turnMs > 0 && relayMs > 0) fast = if (was != false) turnMs <= relayMs + 5 else turnMs + 5 < relayMs
        return fast
    }

    /** Within 0.05° of [b] in yaw and pitch (yaw 65536 to the turn, pitch 32767 to 90°). */
    fun tiny(a: Long, b: Long): Boolean {
        val dy = (((a shr 16) - (b shr 16) + 32768) and 0xFFFF) - 32768
        val dp = (a and 0xFFFF).toInt().toShort() - (b and 0xFFFF).toInt().toShort()
        return kotlin.math.abs(dy) < 0.05 / 360 * 65536 && kotlin.math.abs(dp) < 0.05 / 90 * 32767
    }

    /** When a game sends what, for one game: [tick] and [look] over TURN, [relayFrames] through the relay. Times are System.nanoTime's; [ms] its ms, 16 bits. */
    class Sender {
        // TURN: the last tick's body and whether it was a change (a change is followed by one more, the settled pose, so
        // nobody carries on a move that has stopped), when it last went, and the item last sent with one.
        private var lastTick: ByteArray? = null
        private var tickChanged = false
        private var tickSentAt = 0L
        private var tickItem = -1
        private var tickItemAt = 0L
        // [look]'s own: when it last ran, its last look and whether that was a change.
        private var frameAt = 0L
        private var lastLook = -1L
        private var lookChanged = false
        private var lastLookMs = 0
        // The relay's: the last frame, whether it went, and when yours last did.
        private var lastSig: ByteArray? = null
        private var prevFrame: ByteArray? = null
        private var prevSent = false
        var selfAt = 0L
        /** Look frames sent and skipped as tiny (for the debug info). */
        var looksSent = 0L; private set
        var looksSkipped = 0L; private set

        /**
         * This tick's [D_TICK]s: when it changed, once more when it stops changing (the settled pose), every [STILL_NS]
         * otherwise, and the pose before a change, again, first. The held item ([item], a registry id) rides along when it
         * changes, and once a second. Null when none is due.
         */
        fun tick(now: Long, ms: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float, flags: Int, item: Int): List<ByteArray>? {
            val withItem = item != tickItem || now - tickItemAt > 1_000_000_000L
            val body = tickBody(ms, x, y, z, yaw, pitch, flags, if (withItem) item else null)
            // Changed: where, which way, sneaking or swinging (not the time, not the item).
            val changed = lastTick?.let { !body.copyOfRange(2, 15).contentEquals(it.copyOfRange(2, 15)) || (flags and 3) != 0 } ?: true
            val prev = lastTick
            lastTick = body
            val send = changed || tickChanged || now - tickSentAt > STILL_NS
            val wasChanged = tickChanged
            tickChanged = changed
            if (!send) return null
            val out = ArrayList<ByteArray>(2)
            if (changed && !wasChanged && prev != null && now - tickSentAt > TICK_NS + 5_000_000L) out += prev
            tickSentAt = now
            if (withItem) { tickItem = item; tickItemAt = now }
            out += body
            return out
        }

        /**
         * This frame's [D_LOOK]s, [frameNs] apart at most (null when too soon): the look when it changed (one under 0.05° from
         * the last sent isn't, with [skipTiny]), and the look before a turn again first, with its own time (otherwise the turn
         * is drawn spread across the still stretch before it).
         */
        fun look(now: Long, ms: Int, yaw: Float, pitch: Float, frameNs: Long, skipTiny: Boolean): List<ByteArray>? {
            if (now - frameAt < frameNs - 1_000_000L) return null
            frameAt = now
            val look = look(yaw, pitch)
            val changed = look != lastLook && !(skipTiny && lastLook >= 0 && tiny(look, lastLook))
            if (changed) looksSent++ else if (look != lastLook) looksSkipped++
            val out = ArrayList<ByteArray>(2)
            if (changed && !lookChanged && lastLook >= 0) out += lookBody(lastLookMs, lastLook)
            if (changed) out += lookBody(ms, look)
            lookChanged = changed
            if (changed) lastLook = look
            lastLookMs = ms
            return out
        }

        /**
         * Your relay [frame] this tick (kind 1): sent every tick while moving, every [STILL_NS] while still, and the still pose
         * again just before moving off (so the move starts from where it did, not drawn out across the gap). Only when [relay]
         * (someone it doesn't reach over TURN); either way it counts. The frames to send, in order.
         */
        fun relayFrames(frame: ByteArray, now: Long, relay: Boolean): List<ByteArray> {
            val sig = frame.copyOfRange(3, 16) + frame.copyOfRange(17, 20)
            val changed = !sig.contentEquals(lastSig)
            val out = ArrayList<ByteArray>(2)
            if (changed && !prevSent && relay) prevFrame?.let { out += it }
            prevSent = changed || now - selfAt > STILL_NS
            if (prevSent) { if (relay) out += frame; selfAt = now }
            prevFrame = frame; lastSig = sig
            return out
        }

        /** A new socket: the relay's last frame is forgotten. */
        fun forgetRelay() { lastSig = null; prevFrame = null }
    }
}
