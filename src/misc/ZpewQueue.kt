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
    private class Waiting<C>(val click: C, val tick: Long, val wall: Long, var counts: Boolean) {
        /** The server tick a "There are blocks in the way!" line came for this click, or -1. */
        var blockedAt = -1L
    }

    private val waiting = ArrayDeque<Waiting<C>>()
    private var origin: DoubleArray? = null
    private var roundTrip = Double.NaN
    private var lastTeleportTick = -1L
    private var spread = 0.0

    /** Where the camera goes (feet), or null: at the player. */
    var camera: DoubleArray? = null
        private set

    val isEmpty get() = waiting.isEmpty()

    /** Called with each click that stops counting without an answer (the server dropped or refused it silently). */
    var onDrop: ((C) -> Unit)? = null

    /**
     * The server synced you to [to] (a position packet that answers no click) while clicks were waiting: it casts them from
     * there, so the chain is worked out again from it.
     */
    fun synced(to: DoubleArray) {
        if (waiting.isEmpty()) return
        origin = to
        rechain()
    }

    /** Whether [to] is where one of the waiting clicks lands. */
    fun matches(to: DoubleArray): Boolean {
        val from = origin ?: return false
        return waiting.any { same(predict(from, it.click), to) }
    }

    /** The oldest click still counting: the one a teleport that matches nothing is taken for. */
    fun oldestCounting(): C? = waiting.firstOrNull { it.counts }?.click
    val size get() = waiting.size

    /** The last [teleport]: which waiting click it matched (-1: none did, the oldest was taken), and where the oldest counting one was predicted. */
    var lastMatch = -1
        private set
    var lastExpected: DoubleArray? = null
        private set

    /** [counts]: the server should cast it; false still lets a teleport match it. */
    fun click(click: C, tick: Long, player: DoubleArray, counts: Boolean, wall: Long = tick) {
        if (waiting.isEmpty()) origin = player
        waiting.addLast(Waiting(click, tick, wall, counts))
        rechain()
    }

    /** A teleport to [to] was applied at [tick]. False when nothing was waiting for it. */
    fun teleport(to: DoubleArray, tick: Long): Boolean {
        lastTeleportTick = tick
        if (waiting.isEmpty()) return false
        val from = origin ?: to
        var i = waiting.indexOfFirst { same(predict(from, it.click), to) }
        lastMatch = i
        lastExpected = waiting.firstOrNull { it.counts }?.let { predict(from, it.click) }
        if (i >= 0) sample((tick - waiting[i].tick).toDouble()) else i = waiting.indexOfFirst { it.counts }
        repeat(i + 1) { waiting.removeFirst() }
        origin = to
        rechain()
        return true
    }

    /**
     * "There are blocks in the way!" came at [tick]. It also comes just before a blink cut short by a wall that still moves you
     * (alldata #items.blink-refusal: in the move's tick, or up to 4 ticks earlier for a Hyperion), so it is only marked here;
     * [settleBlocked] refuses the click once no move followed.
     */
    fun blocked(tick: Long) {
        if (lastTeleportTick == tick) return // the cut-short blink this line came with was already applied
        waiting.firstOrNull { it.counts && it.blockedAt < 0 }?.blockedAt = tick
    }

    /** Refuses the oldest counting click whose blocked line wasn't followed by its move within [early] ticks. True when one was. */
    fun settleBlocked(tick: Long, early: (C) -> Int): Boolean {
        val i = waiting.indexOfFirst { it.counts }
        if (i < 0) return false
        val w = waiting[i]
        if (w.blockedAt < 0 || tick - w.blockedAt <= early(w.click)) return false
        waiting.removeAt(i)
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
        for (w in waiting) if (w.counts && (tick - w.tick >= limit || wall - w.wall >= HARD)) { w.counts = false; changed = true; onDrop?.invoke(w.click) }
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
