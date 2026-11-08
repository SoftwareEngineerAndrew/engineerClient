package com.engineerclient.poi

import java.nio.ByteBuffer

/**
 * Where you are, as far as P3's points of interest go, for teammates over Pose POV's party link ([com.engineerclient.pov.PoseShare.shareExtra]):
 * `[13][spot id, 0 none][section area 0-5][tenths of a second on it, 0-255]`. The area is the P3 section whose box you're in
 * (1-4, [com.engineerclient.rotation.P3Sections]), 5 the core, 0 neither. Sent on each change and once a second through P3.
 *
 * Kind 13 is free on both paths: TURN's header has room for kinds up to 15 ([com.engineerclient.pov.TurnLink]), where 0-4, 7-12
 * are taken; the relay closes the socket on a kind it doesn't know, so it goes there only once the relay's sign-in lists it.
 */
object PoiWire {
    const val KIND = 13
    const val AREA_CORE = 5

    class Heard(val poi: Int, val area: Int, val heldMs: Long)

    fun encode(poi: Int, area: Int, heldMs: Long): ByteArray =
        byteArrayOf(KIND.toByte(), poi.coerceIn(0, 255).toByte(), area.coerceIn(0, AREA_CORE).toByte(), (heldMs / 100).coerceIn(0, 255).toByte())

    /**
     * A frame's body (just past its kind), or null if it isn't one a game could send: the size, an area there is. A spot this
     * game doesn't know (a newer one's) reads as none, keeping the area.
     */
    fun decode(b: ByteBuffer): Heard? {
        if (b.remaining() != 3) return null
        val id = b.get().toInt() and 255
        val area = b.get().toInt() and 255
        val tenths = b.get().toInt() and 255
        if (area > AREA_CORE) return null
        val poi = if (id != 0 && PointsOfInterest.byId(id) != null) id else 0
        return Heard(poi, area, tenths * 100L)
    }
}
