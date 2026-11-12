package com.engineerclient.misc

/**
 * Teleport clicks the server hasn't answered yet, and where the camera goes. No Minecraft here.
 *
 * The server casts each click from where the one before it put you, so the camera is the end of that chain, worked
 * out from [origin]: the player when the first click went out, then each real landing. A teleport answers the first
 * click that lands there from [origin] (the ones before it were dropped by the server); when none does, the law
 * missed and the oldest is taken. Either way the chain is worked out again from the real landing.
 *
 * A dropped click can't be told from the others, so a click waiting longer than the round trip plus its spread
 * (measured from answered clicks, like TCP) stops counting. It can still be matched for a while, in case it was late.
 * Ticks are the server's as they reach us, so a lag spike (no ticks arriving) doesn't run the clock out; [HARD] client
 * ticks ([wall]) is the cap if the server never answers.
 */
class ZpewQueue<C>(private val predict: (from: DoubleArray, click: C) -> DoubleArray?) {
    private class Waiting<C>(val click: C, val tick: Long, val wall: Long, var counts: Boolean)

    private val waiting = ArrayDeque<Waiting<C>>()
    private var origin: DoubleArray? = null
    private var roundTrip = Double.NaN
    private var spread = 0.0

    /** Where the camera goes (feet), or null: at the player. */
    var camera: DoubleArray? = null
        private set

    val isEmpty get() = waiting.isEmpty()

    /** [counts]: the server should cast it; false still lets a teleport match it. */
    fun click(click: C, tick: Long, player: DoubleArray, counts: Boolean, wall: Long = tick) {
        if (waiting.isEmpty()) origin = player
        waiting.addLast(Waiting(click, tick, wall, counts))
        rechain()
    }

    /** A teleport to [to] was applied at [tick]. False when nothing was waiting for it. */
    fun teleport(to: DoubleArray, tick: Long): Boolean {
        if (waiting.isEmpty()) return false
        val from = origin ?: to
        var i = waiting.indexOfFirst { same(predict(from, it.click), to) }
        if (i >= 0) sample((tick - waiting[i].tick).toDouble()) else i = waiting.indexOfFirst { it.counts }
        repeat(i + 1) { waiting.removeFirst() }
        origin = to
        rechain()
        return true
    }

    /** The server refused the oldest click that counts (a refusal line). */
    fun refuse() {
        val i = waiting.indexOfFirst { it.counts }
        if (i < 0) return
        waiting.removeAt(i)
        rechain()
    }

    /** Clicks waiting too long stop counting, and go after [KEEP]. True when the camera moved. */
    fun expire(tick: Long, wall: Long = tick): Boolean {
        val limit = if (roundTrip.isNaN()) FIRST.toDouble() else minOf(MAX.toDouble(), roundTrip + maxOf(GRACE.toDouble(), 4 * spread))
        var changed = false
        for (w in waiting) if (w.counts && (tick - w.tick >= limit || wall - w.wall >= HARD)) { w.counts = false; changed = true }
        while (waiting.isNotEmpty() && (tick - waiting.first().tick >= KEEP || wall - waiting.first().wall >= HARD + KEEP)) waiting.removeFirst()
        if (!changed) return false
        rechain()
        return true
    }

    fun clear() {
        waiting.clear()
        origin = null
        camera = null
    }

    private fun sample(wait: Double) {
        if (roundTrip.isNaN()) { roundTrip = wait; spread = wait / 2; return }
        spread = spread * 0.75 + kotlin.math.abs(wait - roundTrip) * 0.25
        roundTrip = roundTrip * 0.875 + wait * 0.125
    }

    private fun rechain() {
        var pos = origin ?: run { camera = null; return }
        var moved = false
        for (w in waiting) if (w.counts) predict(pos, w.click)?.let { pos = it; moved = true }
        camera = if (moved) pos else null
    }

    /** An etherwarp's y + 0.05 can differ from the server's in the last bit. */
    private fun same(a: DoubleArray?, b: DoubleArray) = a != null && (0..2).all { kotlin.math.abs(a[it] - b[it]) < 1e-6 }

    private companion object {
        const val GRACE = 4
        const val FIRST = 20
        const val MAX = 60
        const val KEEP = 60
        const val HARD = 200
    }
}
