package com.coffeeclient.misc

import kotlin.math.abs

/**
 * The ding Hypixel plays with every P3 completion message - "X activated a terminal! (n/7)",
 * levers, devices, and the gate and core opening: a note block pling at volume 8, pitch 4.05
 * (the same one Odin's Terminal Sounds mutes while you're in a terminal, and re-plays for the gate
 * and core). [RandomStuff]'s Mute Completion Ding cancels it; with Keep Gate & Core Ding, the one
 * that comes with those two messages still plays.
 *
 * The ding and its chat line come as separate packets in either order, so which message a ding
 * belongs to is only known once both are in. A ding that arrives first is held back; if the gate or
 * core line follows within [PAIR_MS] it's played then. A gate or core line that arrives first lets
 * the next ding through. Pure: [now] in ms, both packets in arrival order (the network thread).
 */
class CompletionDing {

    /** A ding that was stopped: what to play if it turns out to be the gate's or core's. */
    data class Ding(val at: Long, val volume: Float, val pitch: Float)

    private var held: Ding? = null
    private var keepUntil: Long? = null

    /** True: cancel this sound. Only Hypixel's completion ding is ever touched. */
    fun sound(isPling: Boolean, volume: Float, pitch: Float, now: Long): Boolean {
        if (!isDing(isPling, volume, pitch)) return false
        keepUntil?.let { until -> keepUntil = null; if (now <= until) return false }
        held = Ding(now, volume, pitch)
        return true
    }

    /** The gate or core line. Returns a held-back ding to play now, if one came just before it. */
    fun keptMessage(now: Long): Ding? {
        val h = held?.takeIf { now - it.at <= PAIR_MS }
        held = null
        if (h == null) keepUntil = now + PAIR_MS
        return h
    }

    companion object {
        const val PAIR_MS = 250L
        val KEPT = listOf("The gate has been destroyed!", "The Core entrance is opening!")

        fun isDing(isPling: Boolean, volume: Float, pitch: Float) = isPling && volume >= 7.9f && abs(pitch - 4.047619f) < 0.01f
    }
}
