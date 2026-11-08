package com.engineerclient.poi

import java.nio.ByteBuffer

/**
 * Which point of interest you're on, for teammates over Pose POV's party link ([com.engineerclient.pov.PoseShare.shareExtra]):
 * `[13][sequence 0-255, wrapping][spot id, 0 none][tenths of a second on it, 0-255]`. Sent on each change and four times a
 * second through S1-S3. TURN is UDP, so frames can be lost or come out of order: the sequence lets a teammate drop one older
 * than the newest they have, so an old "on EE3" can't come in after "off it".
 *
 * Kind 13 is free on both paths: TURN's header has room for kinds up to 15 ([com.engineerclient.pov.TurnLink]), where 0-4, 7-12
 * are taken; the relay closes the socket on a kind it doesn't know, so it goes there only once the relay's sign-in lists it.
 */
object PoiWire {
    const val KIND = 13
    /** The most time on a spot a frame can say (255 tenths). */
    const val MAX_HELD_MS = 25_500L

    class Heard(val seq: Int, val poi: Int, val heldMs: Long)

    fun encode(seq: Int, poi: Int, heldMs: Long): ByteArray =
        byteArrayOf(KIND.toByte(), (seq and 255).toByte(), poi.coerceIn(0, 255).toByte(), (heldMs / 100).coerceIn(0, 255).toByte())

    /**
     * A frame's body (just past its kind), or null if it isn't one a game could send (its size). A spot this game doesn't
     * know (a newer one's) reads as none: not on a spot it could highlight.
     */
    fun decode(b: ByteBuffer): Heard? {
        if (b.remaining() != 3) return null
        val seq = b.get().toInt() and 255
        val id = b.get().toInt() and 255
        val tenths = b.get().toInt() and 255
        val poi = if (id != 0 && PointsOfInterest.byId(id) != null) id else 0
        return Heard(seq, poi, tenths * 100L)
    }

    /** Whether sequence [a] comes after [b], allowing for the wrap (they're never more than half the range apart while fresh). */
    fun after(a: Int, b: Int): Boolean = ((a - b) and 255) in 1..127
}
