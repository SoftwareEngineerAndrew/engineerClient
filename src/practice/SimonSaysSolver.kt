package com.engineerclient.practice

/**
 * Simon Says' answer, worked out from how the device behaves in a couple hundred recorded F7 runs
 * (replayed against those rounds it gets noticeably more right than Odin's own solver).
 * Pure: fed every block packet for a lamp and the grid's buttons and presses, with server ticks;
 * [C] is whatever names a cell (a BlockPos in game).
 *
 * The device: a start task clears every lamp and starts a chain that lights the pattern's lamp j at
 * +8j ticks (the pattern is read as it lights, and redrawn by every start press); each lighting goes
 * out 8 ticks later; chains are never cancelled. A skip (several start presses) leaves several chains
 * running, and the buttons come back on the FIRST chain's timer while the LAST chain - the round that
 * has to be put in - is still lighting lamps. The answer is the last chain.
 *
 * Seen per lamp packet: on, off, and two packets that change nothing on the client (so a block
 * update event never reports them): a lamp sent lit while lit (cleared and lit again in one tick)
 * and a lamp sent unlit while unlit (lit and cleared in one tick). A lighting is an on, a re-send or a
 * flash packet. A clear is a flash packet, or a re-send or off that no lighting 8 ticks earlier
 * explains; a chain start is a clear with a lamp lit in the same tick.
 *
 * A show is the lights between the buttons going and coming back.
 *  - No chain start in it (an ordinary round): lights are taken as they come on, repeats kept. When
 *    the buttons come back the answer is settled: lights at the front that were on for 2 ticks or less
 *    are strays; then, if the buttons came back while the last light was still on and there are 3 or
 *    more lights, the first is a stray too.
 *  - A chain start in it (a skip): the answer is the last start's chain, the lamp lit at start + 8j
 *    for j = 0, 1, ... as far as time has got, until a step finds no lamp, at most [MAX_ROUNDS]. Lights
 *    after that never start a new show; the buttons going away ends the round, and a light in that same
 *    tick is the next show's first.
 * While a show is on, [answer] is the lights so far (front flashes dropped) to draw the in-between solution.
 */
class SimonSaysSolver<C> {

    private class Light<C>(val cell: C, val on: Long) { var off = -1L }
    private enum class Kind { ON, AGAIN, OFF, FLASH }
    private class Packet<C>(val tick: Long, val cell: C, val kind: Kind)

    private val shown = ArrayList<Light<C>>()
    private val packets = ArrayList<Packet<C>>()
    private var showing = false
    /** While the buttons are up after a skip: the tick its last chain started; else -1. */
    private var skip = -1L

    /** The answer, in order: while a show is on, the lights so far; empty between rounds. */
    var answer: List<C> = emptyList()
        private set

    /** How many of [answer] have been pressed. */
    var next = 0
        private set

    /** A block packet for a lamp, before it is applied: [lit] is the packet's state, [was] the lamp's then. */
    fun lamp(cell: C, lit: Boolean, was: Boolean, tick: Long) {
        val kind = if (lit) (if (was) Kind.AGAIN else Kind.ON) else (if (was) Kind.OFF else Kind.FLASH)
        if (kind == Kind.ON && skip < 0 && !showing) packets.clear()
        if (kind != Kind.FLASH || showing || skip >= 0) packets += Packet(tick, cell, kind)
        when (kind) {
            Kind.ON -> if (skip >= 0) { shown += Light(cell, tick); answer = lastChain(tick) } else lightOn(cell, tick)
            Kind.OFF -> { lightOff(cell, tick); if (skip >= 0) answer = lastChain(tick) }
            else -> { if (skip >= 0) answer = lastChain(tick) }
        }
    }

    /** Every server tick: a step of the last chain may light a lamp that is already lit (no packet). */
    fun serverTick(tick: Long) {
        if (skip >= 0) answer = lastChain(tick)
    }

    private fun lightOn(cell: C, tick: Long) {
        if (!showing) { showing = true; shown.clear(); answer = emptyList(); next = 0 }
        shown += Light(cell, tick)
        answer = soFar()
    }

    private fun lightOff(cell: C, tick: Long) {
        shown.lastOrNull { it.cell == cell && it.off < 0 }?.off = tick
        if (showing) answer = soFar()
    }

    /** The show so far, flashes at the front dropped (the skip rule needs the show's end). */
    private fun soFar(): List<C> {
        val a = shown.toMutableList()
        while (a.size > 1 && a[0].off >= 0 && a[0].off - a[0].on <= 2) a.removeAt(0)
        return a.map { it.cell }
    }

    /** The tick of the last chain start before [before], or -1. */
    private fun lastStart(before: Long): Long {
        var last = -1L
        for (p in packets) {
            if (p.tick >= before) break
            val clear = p.kind == Kind.FLASH || ((p.kind == Kind.AGAIN || p.kind == Kind.OFF) &&
                packets.none { it.tick == p.tick - 8 && it.cell == p.cell && it.kind != Kind.OFF })
            if (clear && packets.any { it.tick == p.tick && (it.kind == Kind.ON || it.kind == Kind.AGAIN) }) last = p.tick
        }
        return last
    }

    /** The last chain as far as [now]: its lamp j is the one lit at skip + 8j (a packet that tick, else still lit). */
    private fun lastChain(now: Long): List<C> {
        val out = ArrayList<C>()
        for (j in 0 until MAX_ROUNDS) {
            val t = skip + 8L * j
            if (t > now) break
            val exact = packets.filter { it.tick == t && (it.kind == Kind.ON || it.kind == Kind.AGAIN) }.map { it.cell }
            if (t == now && exact.isEmpty()) break // this tick's packets may still be coming
            val cell = exact.lastOrNull() ?: if (j == 0) null else litAt(t).lastOrNull()
            out += cell ?: break
        }
        return out
    }

    /** Lamps lit at [t]: last lit in the 8 ticks up to [t] and not gone out since. */
    private fun litAt(t: Long): List<C> {
        val last = LinkedHashMap<C, Long>()
        for (p in packets) {
            if (p.tick > t) break
            if (p.kind != Kind.OFF) last[p.cell] = p.tick
            if (p.kind == Kind.OFF || p.kind == Kind.FLASH) last.remove(p.cell)
        }
        return last.filterValues { it > t - 8 }.keys.toList()
    }

    /** The grid's buttons are back: the show is over, and its answer settled (or, after a skip, still coming). */
    fun buttonsUp(tick: Long) {
        if (!showing) return
        showing = false
        next = 0
        val start = lastStart(tick)
        if (start >= 0) { skip = start; answer = lastChain(tick); return }
        val a = shown.toMutableList()
        while (a.size > 1 && a[0].off >= 0 && a[0].off - a[0].on <= 2) a.removeAt(0)
        if (shown.any { it.off < 0 } && a.size >= 3) a.removeAt(0)
        answer = a.map { it.cell }
    }

    /** The buttons went away (a round done or failed): no answer until the next show. */
    fun buttonsGone(tick: Long) {
        if (skip >= 0) {
            skip = -1
            // A start task lights its first lamp in the same tick it takes the buttons, lamp packet first.
            val fresh = shown.filter { it.on == tick }
            packets.removeAll { it.tick != tick }
            shown.clear(); shown.addAll(fresh)
            showing = fresh.isNotEmpty()
            answer = if (showing) soFar() else emptyList(); next = 0
            return
        }
        if (showing) return
        answer = emptyList(); next = 0
    }

    /**
     * A button pressed (its lamp [cell]). The next one moves on; one further along means presses
     * were missed, so it catches up; anything else is left to the device (a wrong press ends the round).
     */
    fun pressed(cell: C, tick: Long) {
        if (showing) buttonsUp(tick) // the buttons came back without it being seen
        if (answer.getOrNull(next) == cell) { next++; return }
        val i = answer.indexOf(cell)
        if (i >= next) next = i + 1
    }

    fun reset() { shown.clear(); packets.clear(); showing = false; skip = -1; answer = emptyList(); next = 0 }

    companion object {
        /** Rounds since Hypixel's October 2026 update (5 before). */
        const val MAX_ROUNDS = 4
    }
}
