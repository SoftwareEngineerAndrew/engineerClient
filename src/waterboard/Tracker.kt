package com.engineerclient.waterboard

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The server's board, kept in step from the block packets it sends: a [WaterSim] run on the server's tick, with every lever
 * flip at the tick it happened. What the planner plans from, so it plans from what the player really did.
 *
 * The client can't count the server's ticks (Hypixel's pings aren't one per tick, and a lagging server freezes for seconds
 * while the wall clock runs on), but the water can: every write the server makes on the board comes to the client, at
 * 5-tick steps the simulation predicts exactly. So each packet is matched to the tick the simulation sends it on, the lag
 * between that and the wall clock is followed ([drift]), and after a freeze (packets the simulation sent long ago arriving
 * late) it is found again from the packets that follow. A lever packet comes with its tick's packets: the board is wound back
 * and that tick played again with the flip; a few ticks later the flip is moved to the tick the packets after it agree on.
 * If the board still stops agreeing (packets missed, the room re-sent), it is rebuilt from what the client sees.
 *
 * Times: [ms] arguments are the client's clock when the packet arrived (any origin, same for all calls).
 */
class Tracker(val geo: Geometry, start: WaterSim, private val originMs: Double) {
    /** The board at its latest simulated tick; a little ahead of the server so early packets have something to match. */
    var head: WaterSim = start.copy(); private set
    /** Wall-clock ticks behind (negative) or ahead of the simulation's: sim tick = wall tick + drift. */
    var drift = 0.0; private set
    /** Lever flips so far: tick to lever bits. */
    private val flipAt = HashMap<Int, Int>()
    /** Random ticks the packets showed (tick to cells): Hypixel's flowing water gets them, and no one can predict them. */
    private val randomAt = HashMap<Int, IntArray>()
    var randomTicks = 0; private set
    /** Per tick, the board at the end of it (to wind back to), oldest first, consecutive. */
    private val snaps = ArrayDeque<WaterSim>()
    /** Per snapshot, the cells written after its tick's send (they go out with the next tick's). */
    private val carries = ArrayDeque<IntArray>()
    private var carry = LinkedHashSet<Int>()
    /** Per tick, what the server sends on it: cell to view. */
    private val sends = HashMap<Int, HashMap<Int, Int>>()
    private val used = HashSet<Long>()
    private class Obs(val raw: Double, val cell: Int, val view: Int, var tick: Int)
    /** The lag (sim tick - wall tick) of the latest matched packets: [drift] is their median. */
    private val lags = ArrayDeque<Double>()
    private val recent = ArrayDeque<Obs>()
    private var streak = 0
    /** Flips whose tick is still to be checked against the packets after it: tick, lever, check when the sim passes this. */
    private class Pending(var tick: Int, val lever: Int, val checkAt: Int)
    private val pending = ArrayList<Pending>()
    /** What the client last saw per cell (for a rebuild). */
    private val seen = IntArray(geo.w * geo.h) { Int.MIN_VALUE }
    var resyncs = 0; private set
    /** Debug trace (tests). */
    var trace: ((String) -> Unit)? = null
    var relocks = 0; private set
    var matched = 0; private set
    var observed = 0; private set
    /** Lever packets in the order they came (tick as first placed, lever): what the player did, for the solver. */
    val arrivals = ArrayList<Pair<Int, Int>>()
    /** Every flip the tracker has placed (tick, lever), for the solver to tell what was done. */
    val flips: List<Pair<Int, Int>> get() = flipAt.entries.sortedBy { it.key }.flatMap { (t, b) -> (0..6).filter { b and (1 shl it) != 0 }.map { t to it } }

    init { snaps.addLast(head.copy()); carries.addLast(IntArray(0)) }

    fun raw(ms: Double): Double = (ms - originMs) / 50.0
    /** The server's tick now, as best known. */
    fun now(ms: Double): Int = (raw(ms) + drift).roundToInt()

    /** Simulates on to [ms]'s tick plus [AHEAD]; checks flips that are due a second look. */
    fun advance(ms: Double) {
        advanceTo(now(ms) + AHEAD)
        refine()
    }

    private fun advanceTo(t: Int) {
        while (head.time < t) stepHead()
    }

    private fun stepHead() {
        val log = HashMap<Int, Int>()
        // writes after the last send (block events, landings) go out with this tick's
        val dirty = LinkedHashSet<Int>(carry)
        head.onWrite = { c, _ -> dirty += c }
        head.onSend = { for (c in dirty) log[c] = view(head.cells[c]); dirty.clear() }
        head.step(flipAt[head.time + 1] ?: 0, randomAt[head.time + 1])
        head.onWrite = null; head.onSend = null
        carry = dirty
        sends[head.time] = log
        snaps.addLast(head.copy()); carries.addLast(carry.toIntArray())
        while (snaps.size > KEEP) { val old = snaps.removeFirst(); carries.removeFirst(); sends.remove(old.time) }
    }

    /** The board as it was at the end of tick [t], made the head (later ticks are simulated again when needed). */
    private fun rewind(t: Int): Boolean {
        val first = snaps.first().time
        if (t < first || t > head.time) return false
        while (snaps.last().time > t) { val s = snaps.removeLast(); carries.removeLast(); sends.remove(s.time) }
        head = snaps.last().copy()
        carry = LinkedHashSet(carries.last().toList())
        used.removeIf { (it shr 32).toInt() > t }
        return true
    }

    /** A lever's state changed on the server (its block packet arrived at [ms]). */
    fun lever(ms: Double, lever: Int) {
        val c = now(ms)
        val top = head.time
        val t = if (rewind(c - 1)) c else head.time + 1
        flipAt.merge(t, 1 shl lever) { a, b -> a xor b }
        advanceTo(maxOf(top, t))
        pending += Pending(t, lever, t + 16 + SPAN)
        arrivals += t to lever
    }

    /** A board cell's block packet: [view] a water level, [WaterSim.AIR] or [WaterSim.SOLID]. */
    fun cell(ms: Double, cell: Int, view: Int) {
        seen[cell] = view
        val r = raw(ms)
        val e = (r + drift).roundToInt()
        advanceTo(e + AHEAD)
        observed++
        val t = nearest(cell, view, e, 6)
        val o = Obs(r, cell, view, t ?: Int.MIN_VALUE)
        recent.addLast(o); while (recent.size > 80) recent.removeFirst()
        trace?.invoke("cell ${geo.x(cell)},${geo.y(cell)} v$view raw ${"%.1f".format(r)} e $e -> $t (head ${head.time}, drift ${"%.1f".format(drift)}) sendsAtE ${sends[e]?.entries?.joinToString { "${geo.x(it.key)},${geo.y(it.key)}=${it.value}" }}")
        val tt = t ?: randomTick(cell, view, e)
        if (tt != null) {
            o.tick = tt
            used += key(tt, cell); matched++; streak = 0
            lags.addLast(tt - r); while (lags.size > 15) lags.removeFirst()
            drift = lags.sorted()[lags.size / 2]
            return
        }
        if (++streak >= 6) relock()
        checkDivergence(ms)
    }

    /**
     * A packet nothing simulated explains, tried as a random tick: on that cell or a neighbour, flowing at the time, a few ticks
     * before the packet's tick. The first that makes the server send exactly this is kept (the board is wound back and played
     * again with it). Its signature: a cell updated off its 5-tick step, sooner than scheduled (its own scheduled tick then
     * finds it settled, or is merged into the one it already had). Returns the tick the packet now matches, or null.
     */
    private fun randomTick(cell: Int, view: Int, e: Int): Int? {
        val top = head.time
        val first = snaps.first().time
        for (t in e - RANDOM_SPAN..e + 1) {
            if (t <= first || t > top) continue
            val before = snaps.firstOrNull { it.time == t - 1 } ?: continue
            for (d in intArrayOf(0, geo.w, -geo.w, 1, -1)) {
                val c = cell + d
                if (c !in before.cells.indices) continue
                val v = before.cells[c]
                if (v < 0 || v and WaterSim.DYN == 0) continue
                if (randomAt[t]?.contains(c) == true) continue
                randomAt[t] = (randomAt[t] ?: IntArray(0)) + c
                rewind(t - 1); advanceTo(top)
                val m = nearest(cell, view, e, 2)
                if (m != null) { randomTicks++; trace?.invoke("random tick ${geo.x(c)},${geo.y(c)} at $t"); return m }
                val left = randomAt[t]!!.filter { it != c }.toIntArray()
                if (left.isEmpty()) randomAt.remove(t) else randomAt[t] = left
                rewind(t - 1); advanceTo(top)
            }
        }
        return null
    }

    /** A cell as the client sees it: the water level (flowing and still look the same), air or solid. */
    private fun view(v: Int) = if (v >= 0) v and 15 else v

    private fun key(t: Int, cell: Int) = (t.toLong() shl 32) or (cell.toLong() and 0xffffffffL)

    private fun nearest(cell: Int, view: Int, e: Int, span: Int): Int? {
        var best: Int? = null
        for (t in e - span..e + span) {
            if (sends[t]?.get(cell) != view || key(t, cell) in used) continue
            if (best == null || abs(t - e) < abs(best - e)) best = t
        }
        return best
    }

    /** After a run of packets the simulation can't place: the lag that places most of the latest ones. */
    private fun relock() {
        val hist = HashMap<Int, Double>()
        val lo = snaps.first().time
        for (o in recent.toList().takeLast(30)) for ((t, m) in sends) {
            if (t < lo || m[o.cell] != o.view) continue
            val k = (t - o.raw).roundToInt()
            hist.merge(k, 1.0, Double::plus); hist.merge(k - 1, 0.5, Double::plus); hist.merge(k + 1, 0.5, Double::plus)
        }
        val best = hist.maxByOrNull { it.value } ?: return
        if (best.value >= 8 && abs(best.key - drift) >= 2) { drift = best.key.toDouble(); lags.clear(); lags.addLast(drift); streak = 0; relocks++ }
    }

    /** Most recent packets unexplained even after relocking: rebuild the board from what the client sees. */
    private fun checkDivergence(ms: Double) {
        if (recent.size < 40) return
        val last = recent.toList().takeLast(40)
        val ok = last.count { it.tick != Int.MIN_VALUE }
        if (ok >= 20) return
        val before = relocks
        relock()
        if (relocks != before) { for (o in last) o.tick = 0; return }      // found the lag again: give it 40 packets
        if (ok >= 12) return
        resync(ms)
    }

    fun resync(ms: Double) {
        trace?.invoke("RESYNC at ${now(ms)}")
        val water = IntArray(seen.size) { -1 }
        for (c in seen.indices) if (seen[c] >= 0) water[c] = seen[c]
        // cells never sent since we started keep what the board had
        for (c in head.cells.indices) if (seen[c] == Int.MIN_VALUE && head.cells[c] >= 0) water[c] = head.cells[c] and 15
        var wet = 0
        for (o in geo.outletCell.indices) if (water[geo.outletCell[o]] >= 0) wet = wet or (1 shl o)
        val src = head.sourceOn
        val rebuilt = WaterSim.observed(geo, head.slotIn, head.closed, water, src, wet, now(ms))
        rebuilt.solvedAt = head.solvedAt; rebuilt.hold = head.hold; rebuilt.openSince = head.openSince
        head = rebuilt
        snaps.clear(); carries.clear(); snaps.addLast(head.copy()); carries.addLast(IntArray(0)); sends.clear(); used.clear(); carry.clear(); recent.clear(); pending.clear()
        flipAt.keys.removeIf { it > head.time }
        randomAt.keys.removeIf { it > head.time }
        streak = 0; resyncs++
    }

    /**
     * The gate wool the client sees. The simulation toggles gates itself when water reaches an outlet (and runs ahead of the
     * server), so the wool only overrules it when the simulated server tick of a moment ago has disagreed for a while.
     */
    fun gates(ms: Double, closed: Int) {
        val t = now(ms) - 3
        val s = snaps.lastOrNull { it.time <= t } ?: return
        if (s.closed == closed) { gateOff = 0; return }
        if (++gateOff < 12) return
        val diff = s.closed xor closed
        for (x in snaps) x.closed = x.closed xor diff
        head.closed = head.closed xor diff
        gateOff = 0; gateFixes++
    }
    private var gateOff = 0
    var gateFixes = 0; private set

    /** Moves each flip to the tick (within 3) the packets after it agree with best. */
    private fun refine() {
        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val lastSeen = recent.lastOrNull()?.let { (it.raw + drift).roundToInt() } ?: continue
            if (lastSeen < p.checkAt) continue
            it.remove()
            val from = p.tick - SPAN - 1
            val base = snaps.firstOrNull { s -> s.time == from } ?: continue
            val window = recent.filter { o -> (o.raw + drift).roundToInt() in p.tick - SPAN..p.checkAt + 2 }
            if (window.size < 4) continue
            var best = p.tick; var bestScore = -1
            for (c in p.tick - SPAN..p.tick + SPAN) {
                val bits = HashMap(flipAt)
                bits.merge(p.tick, 1 shl p.lever) { a, b -> a xor b }
                bits.merge(c, 1 shl p.lever) { a, b -> a xor b }
                val s = base.copy()
                val log = HashMap<Int, HashMap<Int, Int>>()
                val dirty = LinkedHashSet<Int>()
                s.onWrite = { cell, _ -> dirty += cell }
                s.onSend = { val m = log.getOrPut(s.time) { HashMap() }; for (d in dirty) m[d] = view(s.cells[d]); dirty.clear() }
                while (s.time < p.checkAt + 4) s.step(bits[s.time + 1] ?: 0, randomAt[s.time + 1])
                var score = 0
                for (o in window) {
                    val e = (o.raw + drift).roundToInt()
                    // the lever's own blocks (pulled at once, landing 3 ticks on) say most about when it was flipped
                    if ((e - 3..e + 3).any { t -> log[t]?.get(o.cell) == o.view }) score += if (geo.slotAt(o.cell) >= 0) 3 else 1
                }
                if (score > bestScore || (score == bestScore && c == p.tick)) { bestScore = score; best = c }
            }
            if (best != p.tick) {
                flipAt.merge(p.tick, 1 shl p.lever) { a, b -> a xor b }
                flipAt.merge(best, 1 shl p.lever) { a, b -> a xor b }
                flipAt.entries.removeIf { e -> e.value == 0 }
                val top = head.time
                if (rewind(minOf(best, p.tick) - 1)) advanceTo(top)
            }
        }
    }

    companion object {
        /** Ticks the simulation runs ahead of the server. */
        const val AHEAD = 6
        /** Ticks of history kept to wind back to. */
        const val KEEP = 120
        /** How far (ticks) a flip may be moved once the packets after it are in. */
        const val SPAN = 8
        /** How many ticks before a packet's tick a random tick that explains it is looked for. */
        const val RANDOM_SPAN = 4
    }
}
