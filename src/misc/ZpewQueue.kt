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
 * Each answered click's wait (click to teleport, in ticks) feeds a running round trip and its spread (TCP's smoothed
 * RTT and RTTVAR); a click left waiting past round trip + max([GRACE], 4 × spread) is taken as dropped ([FIRST] ticks
 * until a round trip is known, never more than [MAX]). There is no user timeout: one shorter than the round trip
 * expired every click before its teleport and sent the camera back and forth on each one. With the same look clicked again and again a dropped cast can't be told from the others, so this is what
 * stops the camera sitting one teleport too far; the spread keeps a jittery connection's late teleports from being
 * expired. A teleport that arrives for an expired click anyway still matches it ([late]), so it doesn't eat the next one.
 */
class ZpewQueue<C>(private val predict: (from: DoubleArray, click: C) -> DoubleArray?) {
    private class Waiting<C>(val click: C, val tick: Long, val expected: Boolean)

    private val waiting = ArrayDeque<Waiting<C>>()
    /** Clicks expired without an answer, kept [LATE_KEEP] ticks in case their teleport is only late. */
    private val late = ArrayDeque<Waiting<C>>()
    private var origin: DoubleArray? = null

    /** Where the camera goes (feet), or null: at the player. */
    var camera: DoubleArray? = null
        private set

    val size: Int get() = waiting.size

    /** The running round trip estimate in ticks (NaN until a click has been answered). */
    var roundTrip: Double = Double.NaN
        private set
    /** The round trip's running spread (mean deviation) in ticks. */
    var spread: Double = 0.0
        private set

    /** How long a click may wait before it is taken as dropped, in ticks. Never under the round trip. */
    fun limit(): Double =
        if (roundTrip.isNaN()) FIRST.toDouble() else minOf(MAX.toDouble(), roundTrip + maxOf(GRACE.toDouble(), 4 * spread))

    /**
     * A click sent at server tick [tick]. [player] is the player's spot now (the start when nothing is waiting).
     * [expected]: the server should cast it (false: a Wither Impact recast too soon or too little mana; still matched).
     */
    fun click(click: C, tick: Long, player: DoubleArray, expected: Boolean = true) {
        if (waiting.isEmpty()) origin = player
        waiting.addLast(Waiting(click, tick, expected))
        rechain()
    }

    /**
     * A teleport to [to] was applied at tick [now]. Returns which waiting click it answered (0 = the oldest), [LATE] for a
     * click already expired, [UNMATCHED] when none lands there (the oldest is then taken as answered), [NONE] when
     * nothing was waiting.
     */
    fun teleport(to: DoubleArray, now: Long): Int {
        if (waiting.isEmpty() && late.isEmpty()) return NONE
        val from = origin ?: to
        val answered = waiting.indexOfFirst { same(predict(from, it.click), to) }
        val wasLate = if (answered >= 0) -1 else late.indexOfFirst { same(predict(from, it.click), to) }
        val result = when {
            answered >= 0 -> {
                sample((now - waiting[answered].tick).toDouble())
                repeat(answered + 1) { waiting.removeFirst() }
                answered
            }
            wasLate >= 0 -> {
                // Only late, not dropped: the wait still counts, so the limit grows to fit this connection.
                sample((now - late[wasLate].tick).toDouble())
                repeat(wasLate + 1) { late.removeFirst() }
                LATE
            }
            waiting.isEmpty() -> return NONE
            else -> { waiting.removeFirst(); UNMATCHED }
        }
        origin = to
        rechain()
        return result
    }

    private fun sample(wait: Double) {
        if (roundTrip.isNaN()) { roundTrip = wait; spread = wait / 2; return }
        spread = spread * 0.75 + kotlin.math.abs(wait - roundTrip) * 0.25
        roundTrip = roundTrip * 0.875 + wait * 0.125
    }

    /** Clicks waiting longer than [limit] are taken as never answered; returns how many. */
    fun expire(now: Long): Int {
        val limit = limit()
        var dropped = 0
        while (waiting.isNotEmpty() && now - waiting.first().tick >= limit) { late.addLast(waiting.removeFirst()); dropped++ }
        while (late.isNotEmpty() && now - late.first().tick >= LATE_KEEP) late.removeFirst()
        if (dropped > 0) rechain()
        return dropped
    }

    fun clear() {
        waiting.clear()
        late.clear()
        origin = null
        camera = null
    }

    companion object {
        /** Ticks past the round trip estimate before a waiting click is taken as dropped. */
        const val GRACE = 4
        /** The wait before any round trip is known. */
        const val FIRST = 20
        /** The longest wait, however slow the connection. */
        const val MAX = 60
        /** How long an expired click can still be matched by a late teleport. */
        const val LATE_KEEP = 60
        const val LATE = -1
        const val UNMATCHED = -2
        const val NONE = -3
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
