package com.engineerclient.misc

/**
 * Zpew's clicks still on their way to the server, and where the camera goes. No Minecraft here.
 *
 * [origin] is where the server will cast the oldest waiting click from: the player's spot when the first click went
 * out, then each teleport's real landing. The camera is the end of the chain: every waiting click the server is
 * expected to cast, worked out in turn from [origin] ([predict]).
 *
 * A teleport is matched against the waiting clicks, not just the oldest one: the first click that, cast from [origin],
 * lands there is the one answered, and the clicks before it were dropped by the server (a Wither Impact recast
 * too soon, too little mana, a refusal). With no click landing there (a wrong guess, or a teleport that isn't ours),
 * the oldest click is taken as answered. Either way the chain is worked out again from the real landing, so one wrong
 * guess never sends the camera back to a player who is still several teleports behind.
 *
 * Each answered click's wait (click to teleport, in ticks) feeds a running estimate of the round trip; a click left
 * waiting [GRACE] ticks past it is taken as dropped (up to the timeout). With the same look clicked again and again a
 * dropped cast can't be told from the others, so this is what stops the camera sitting one teleport too far.
 */
class ZpewQueue<C>(private val predict: (from: DoubleArray, click: C) -> DoubleArray?) {
    private class Waiting<C>(val click: C, val tick: Long, val expected: Boolean)

    private val waiting = ArrayDeque<Waiting<C>>()
    private var origin: DoubleArray? = null

    /** Where the camera goes (feet), or null: at the player. */
    var camera: DoubleArray? = null
        private set

    val size: Int get() = waiting.size

    /** The running round trip estimate in ticks (NaN until a click has been answered). */
    var roundTrip: Double = Double.NaN
        private set

    /**
     * A click sent at server tick [tick]. [player] is the player's spot now (the start when nothing is waiting).
     * [expected]: the server should cast it (false: a Wither Impact recast too soon or too little mana; still matched).
     */
    fun click(click: C, tick: Long, player: DoubleArray, expected: Boolean = true) {
        if (waiting.isEmpty()) origin = player
        waiting.addLast(Waiting(click, tick, expected))
        rechain()
    }

    /** A teleport to [to] was applied at tick [now]. */
    fun teleport(to: DoubleArray, now: Long) {
        if (waiting.isEmpty()) return
        val from = origin ?: to
        val answered = waiting.indexOfFirst { same(predict(from, it.click), to) }
        if (answered >= 0) {
            val wait = (now - waiting[answered].tick).toDouble()
            roundTrip = if (roundTrip.isNaN()) wait else roundTrip * 0.7 + wait * 0.3
        }
        repeat(if (answered >= 0) answered + 1 else 1) { waiting.removeFirst() }
        origin = to
        rechain()
    }

    /** Clicks older than [timeout] ticks, or [GRACE] past the round trip, are taken as never answered. */
    fun expire(now: Long, timeout: Int) {
        val limit = if (roundTrip.isNaN()) timeout.toDouble() else minOf(timeout.toDouble(), roundTrip + GRACE)
        var dropped = false
        while (waiting.isNotEmpty() && now - waiting.first().tick >= limit) { waiting.removeFirst(); dropped = true }
        if (dropped) rechain()
    }

    fun clear() {
        waiting.clear()
        origin = null
        camera = null
    }

    companion object {
        /** Ticks past the round trip estimate before a waiting click is taken as dropped. */
        const val GRACE = 4
    }

    private fun rechain() {
        val start = origin
        if (waiting.isEmpty() || start == null) { camera = null; return }
        var pos: DoubleArray = start
        var moved = false
        for (w in waiting) if (w.expected) predict(pos, w.click)?.let { pos = it; moved = true }
        camera = if (moved) pos else null
    }

    /** Equal to a millionth of a block: an etherwarp's y + 1.05 can differ from ours in the last bit. */
    private fun same(a: DoubleArray?, b: DoubleArray) = a != null && (0..2).all { kotlin.math.abs(a[it] - b[it]) < 1e-6 }
}
