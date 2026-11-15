package com.engineerclient.misc

/**
 * Teleport clicks the server hasn't answered yet, and where the camera goes. No Minecraft here.
 *
 * The server casts each click from where the one before it put you, so the camera is the end of that chain. A click no
 * earlier waiting click moves you from is cast from where you were when it went out (you walk, jump and fall between
 * clicks, and a click the server refused or dropped leaves you there), or from the last real landing or sync for clicks
 * sent before it reached us (the server had already moved you). A teleport answers the first click that lands there
 * (the ones before it were dropped by the server); when none does, the law missed and the oldest is taken. Either way the
 * chain is worked out again from the real landing.
 *
 * A dropped click can't be told from the others, so a click waiting longer than your ping + 100 ms stops counting. It can
 * still be matched for a while ([KEEP] server ticks, [HARD] client ticks), in case it was late.
 */
class ZpewQueue<C>(private val predict: (from: DoubleArray, click: C) -> DoubleArray?) {
    /** [from]: where the server has you for this click when no earlier waiting click moves you. */
    private class Waiting<C>(val click: C, val tick: Long, val wall: Long, var counts: Boolean, var from: DoubleArray, val ms: Long = System.currentTimeMillis()) {
        /** The server tick a "There are blocks in the way!" line came for this click, or -1. */
        var blockedAt = -1L
    }

    private val waiting = ArrayDeque<Waiting<C>>()
    private var lastTeleportTick = -1L

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
        for (w in waiting) w.from = to
        rechain()
    }

    /** Whether [to] is where one of the waiting clicks lands. */
    fun matches(to: DoubleArray): Boolean = answers(to) >= 0

    /**
     * The first waiting click that lands at [to]: cast from its own start (the ones before it dropped) or from the chain's
     * (the ones before it moved you), or -1. A click still counting goes before one that stopped: two clicks from about the
     * same spot land in the same place, and the one that stopped counting was most likely dropped.
     */
    private fun answers(to: DoubleArray): Int {
        var pos: DoubleArray? = null
        var late = -1
        for ((i, w) in waiting.withIndex()) {
            val own = predict(w.from, w.click)
            val chained = pos?.let { predict(it, w.click) }
            if (same(own, to) || same(chained, to)) { if (w.counts) return i; if (late < 0) late = i }
            if (w.counts) (if (pos == null) own else chained)?.let { pos = it }
        }
        return late
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
    /** [player]: where you are as the click goes out (the server's position: your last move packet). */
    fun click(click: C, tick: Long, player: DoubleArray, counts: Boolean, wall: Long = tick) {
        waiting.addLast(Waiting(click, tick, wall, counts, player))
        rechain()
    }

    /** A teleport to [to] was applied at [tick]. False when nothing was waiting for it. */
    fun teleport(to: DoubleArray, tick: Long): Boolean {
        lastTeleportTick = tick
        if (waiting.isEmpty()) return false
        var i = answers(to)
        lastMatch = i
        lastExpected = waiting.firstOrNull { it.counts }?.let { predict(it.from, it.click) }
        if (i < 0) i = waiting.indexOfFirst { it.counts }
        repeat(i + 1) { waiting.removeFirst() }
        // the clicks still waiting went out before this landing reached us: the server casts them from it
        for (w in waiting) w.from = to
        rechain()
        return true
    }

    /**
     * "There are blocks in the way!" came at [tick]. It also comes just before a blink cut short by a wall that still moves you
     * (alldata #items.blink-refusal: in the move's tick or just before it), so it is only marked here;
     * [settleBlocked] refuses the click once no move followed.
     */
    fun blocked(tick: Long) {
        if (lastTeleportTick == tick) return // the cut-short blink this line came with was already applied
        waiting.firstOrNull { it.counts && it.blockedAt < 0 }?.blockedAt = tick
    }

    /** Refuses the oldest counting click whose blocked line wasn't followed by its move within a tick. True when one was. */
    fun settleBlocked(tick: Long): Boolean {
        val i = waiting.indexOfFirst { it.counts }
        if (i < 0) return false
        val w = waiting[i]
        if (w.blockedAt < 0 || tick - w.blockedAt <= 1) return false
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

    /** Clicks waiting longer than [limitMs] stop counting, and go after [KEEP]. True when the camera moved. */
    fun expire(tick: Long, wall: Long, now: Long, limitMs: Long): Boolean {
        var changed = false
        for (w in waiting) if (w.counts && now - w.ms >= limitMs) { w.counts = false; changed = true; onDrop?.invoke(w.click) }
        while (waiting.isNotEmpty() && (tick - waiting.first().tick >= KEEP || wall - waiting.first().wall >= HARD + KEEP)) waiting.removeFirst()
        if (!changed) return false
        rechain()
        return true
    }

    fun clear() {
        waiting.clear()
        camera = null
    }

    private fun rechain() {
        var pos: DoubleArray? = null
        for (w in waiting) if (w.counts) predict(pos ?: w.from, w.click)?.let { pos = it }
        camera = pos
    }

    /** An etherwarp's y + 0.05 can differ from the server's in the last bit. */
    private fun same(a: DoubleArray?, b: DoubleArray) = a != null && (0..2).all { kotlin.math.abs(a[it] - b[it]) < 1e-6 }

    private companion object {
        const val KEEP = 60
        const val HARD = 200
    }
}
