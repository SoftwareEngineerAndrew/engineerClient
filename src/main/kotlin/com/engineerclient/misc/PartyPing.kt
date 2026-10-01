package com.engineerclient.misc

/**
 * Hypixel's chat ping - an experience orb pickup, players category, volume 1, pitch 1 - which it
 * sends just before a party chat line (Devonian's Mute Party Spam goes by the same). [RandomStuff]'s
 * Mute Party Chat In Boss stops it for party lines only: a ping is held back until its line comes,
 * dropped if that's party chat, played after all if it's anything else or nothing comes within
 * [WAIT_MS]. Pure apart from the clock; called from the network thread and the client thread.
 */
class PartyPing {

    data class Ping(val at: Long, val x: Double, val y: Double, val z: Double)

    private var held: Ping? = null

    /** True: hold this sound back (it's the chat ping). */
    @Synchronized
    fun sound(isPing: Boolean, x: Double, y: Double, z: Double, now: Long): Boolean {
        if (!isPing) return false
        held = Ping(now, x, y, z)
        return true
    }

    /** A chat line arrived. Returns a held ping to play now (the line wasn't party chat), or null. */
    @Synchronized
    fun chat(party: Boolean, now: Long): Ping? {
        val h = held ?: return null
        held = null
        return if (party && now - h.at <= WAIT_MS) null else h
    }

    /** Each client tick: a held ping that no line followed is played after all. */
    @Synchronized
    fun expired(now: Long): Ping? {
        val h = held?.takeIf { now - it.at > WAIT_MS } ?: return null
        held = null
        return h
    }

    companion object {
        const val WAIT_MS = 150L
        fun isPartyLine(text: String) = text.startsWith("Party > ")
    }
}
