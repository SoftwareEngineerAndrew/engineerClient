package com.engineerclient.waterboard

import kotlin.math.ln

/**
 * The Water Board's water, exactly as Hypixel's server moves it: Minecraft 1.8's BlockDynamicLiquid / BlockStaticLiquid with the
 * world's scheduled ticks, on the board's vertical plane (room z=26; the glass in front and the wall behind never let water by).
 * Checked against 40 recorded runs packet by packet (95% of every block packet the server sent for the board, tick-exact, on the
 * complete recordings; 100% on ten of them) and against the hand-made one-flow table, which this solves on all 40 boards.
 *
 * Time is server ticks. One [step] is one server tick, in the order the server does it:
 *  1. a water-lever flip, which Hypixel handles after the previous tick's block packets went out (its packet comes with this
 *     tick's), so its source schedules its first flow 4 ticks after that packet, not 5;
 *  2. the due scheduled water ticks, oldest first (all water ticks are 5 apart, so a queue per tick is the server's order);
 *  3. outlets: water newly in an outlet pit toggles that colour's gate (Hypixel opens a closed one and closes an open one);
 *  4. the tick's block packets would go out here;
 *  5. block events: the levers flipped this tick move their pistons (a pulled block leaves its cell at once; a pushed one turns
 *     the cell into a moving piston, destroying water there, and lands [LAND] ticks later).
 *
 * Rechecked 2026-11-12 on two Dungeon Recorder runs (test/waterboard/WaterBoardRecordedTest): every board packet the server
 * sent, on its tick, once the random ticks the packets show are put in ([randomTick]). Where Hypixel isn't vanilla 1.8: a block
 * that dries up leaves nothing below it. Not deterministic: flowing water gets random ticks, so from flips alone the water is
 * exact only until the first one, and then a few ticks early at most (never late); the [Tracker] places them from the packets.
 *
 * The order 1.8 visits east/west/north/south in matters (which update reads which), and in room terms it depends on how the
 * room is turned: [Geometry.of] takes the rotation.
 */
class WaterSim private constructor(
    val geo: Geometry,
    /** Per cell: [AIR], [SOLID], or a water level 0..15 (8+ falling) plus [DYN] when it is flowing (has a tick to come). */
    val cells: IntArray,
    /** Per cell: the tick its scheduled update is due, or [NONE]. One per cell, as the server's (pos, block) entries are. */
    private val due: IntArray,
    private val queue: Array<IntArray>,
    private val queueLen: IntArray,
    /** Per slot: its block is in the board (blocks water). */
    val slotIn: BooleanArray,
    /** Per slot: the tick its pushed block lands, or [NONE]. */
    private val landAt: IntArray,
) {
    var time: Int = 0; private set
    /** The water lever's source is in. */
    var sourceOn: Boolean = false; private set
    /** Gates closed, a bit per [Geometry.OUTLET_X] colour. 0: every gate open. */
    var closed: Int = 0
    /**
     * Ticks every gate has to stay open at once to count as solved (1: the moment they are). The chest needs a while:
     * water reaching another outlet a tick later closes a gate again.
     */
    var hold: Int = 1
    /** The tick the current run of every-gate-open began, or [NONE] while one is closed. */
    var openSince: Int = NONE; internal set
    /** The first tick of the first run of every gate open that lasted [hold] ticks, or [NONE]. */
    var solvedAt: Int = NONE
    /** Outlets with water in them now. */
    var wet: Int = 0; private set
    /** Gate toggles so far: arrivals at outlets whose gate was open (each closes it again). */
    var wrongHits: Int = 0; private set
    /** Every arrival at an outlet: (tick shl 3) or outlet. Off unless asked for (planning doesn't need it). */
    var arrivals: IntArrayList? = null
    /** Called on every block write (cell, phase): the server sends each written block to the client. Validation only. */
    var onWrite: ((Int, Int) -> Unit)? = null
    /** Called where the tick's block packets go out (after the water updates and outlets, before block events). Validation only. */
    var onSend: (() -> Unit)? = null
    /** 0 while the tick's water updates run, 1 after the block packets went out (block events, landings). */
    var phase: Int = 0; private set

    private var adj = 0

    /**
     * Monte Carlo: random ticks drawn from [rng], each flowing cell with probability [randomRate] a tick. Off while null (the
     * deterministic board). Not copied by [copy] / [setFrom]: each trial sets its own.
     */
    var rng: java.util.Random? = null
    var randomRate = 0.0

    fun copy(): WaterSim {
        val s = WaterSim(geo, cells.copyOf(), due.copyOf(), Array(8) { queue[it].copyOf() }, queueLen.copyOf(), slotIn.copyOf(), landAt.copyOf())
        s.time = time; s.sourceOn = sourceOn; s.closed = closed; s.solvedAt = solvedAt; s.wet = wet; s.wrongHits = wrongHits
        s.hold = hold; s.openSince = openSince
        s.arrivals = arrivals?.copy()
        return s
    }

    /** Copies another state of the same board into this one (no allocation): the planner's scratch. */
    fun setFrom(o: WaterSim) {
        require(o.geo === geo)
        System.arraycopy(o.cells, 0, cells, 0, cells.size)
        System.arraycopy(o.due, 0, due, 0, due.size)
        for (b in 0 until 8) { val n = o.queueLen[b]; System.arraycopy(o.queue[b], 0, queue[b], 0, n); queueLen[b] = n }
        System.arraycopy(o.slotIn, 0, slotIn, 0, slotIn.size)
        System.arraycopy(o.landAt, 0, landAt, 0, landAt.size)
        time = o.time; sourceOn = o.sourceOn; closed = o.closed; solvedAt = o.solvedAt; wet = o.wet; wrongHits = o.wrongHits
        hold = o.hold; openSince = o.openSince
        arrivals = o.arrivals?.copy()
    }

    /**
     * One server tick. [flips]: a bit per lever ([Geometry.LEVERS] order, the water lever included) flipped so that its packet
     * comes with this tick's; the water lever acts at the start, the others' pistons at the end.
     */
    fun step(flips: Int = 0, random: IntArray? = null) {
        time++
        if (flips and WATER_BIT != 0) { time--; phase = 1; setSource(!sourceOn); time++ }
        phase = 0
        runDue()
        if (random != null) for (c in random) randomTick(c)
        val r = rng
        if (r != null && randomRate > 0.0) {
            // each flowing cell is random-ticked with probability [randomRate]: geometric gaps over the cells
            val lq = ln(1.0 - randomRate)
            var c = (ln(1.0 - r.nextDouble()) / lq).toInt()
            while (c in cells.indices) { randomTick(c); c += 1 + (ln(1.0 - r.nextDouble()) / lq).toInt() }
        }
        outlets()
        onSend?.invoke()
        phase = 1
        if (flips and BLOCK_LEVERS != 0) for (k in geo.slotCell.indices) if (flips and (1 shl geo.slotLever[k]) != 0) toggle(k)
        for (k in landAt.indices) if (landAt[k] == time) { landAt[k] = NONE; set(geo.slotCell[k], SOLID, true) }
        // solved once every gate has been open [hold] ticks running
        if (closed != 0) openSince = NONE
        else {
            if (openSince == NONE) openSince = time
            if (solvedAt == NONE && time - openSince + 1 >= hold) solvedAt = openSince
        }
    }

    /** Ticks with no flips until [t] (or until solved, when [stopWhenSolved]). */
    fun runTo(t: Int, stopWhenSolved: Boolean = false) {
        while (time < t) { step(); if (stopWhenSolved && solvedAt != NONE) return }
    }

    /** Water may still move: a scheduled update is waiting, a piston is moving, or the source is in. */
    fun moving(): Boolean = sourceOn || queueLen.any { it > 0 } || landAt.any { it != NONE }

    // ---- the server's world -------------------------------------------------------------------------------------------------

    private fun setSource(on: Boolean) {
        sourceOn = on
        set(geo.source, if (on) DYN else SOLID, true)
    }

    private fun toggle(k: Int) {
        val c = geo.slotCell[k]
        if (landAt[k] != NONE) { landAt[k] = NONE; set(c, SOLID, true) }     // flipped again mid-push: it lands first
        if (slotIn[k]) {
            set(c, AIR, true)                                                   // pulled back: open at once
        } else {
            if (cells[c] >= 0) set(c, AIR, true)                               // pushed into water: the water is destroyed
            // the moving piston, placed with flag 4 (BlockPistonBase.doMove): not sent. The client sees it only when the water
            // destroyed there had marked the cell (then as moving_piston); otherwise the cell stays air until the block lands.
            cells[c] = SOLID
            landAt[k] = time + LAND
        }
        slotIn[k] = !slotIn[k]
    }

    private fun outlets() {
        var now = 0
        for (o in geo.outletCell.indices) if (cells[geo.outletCell[o]] >= 0) now = now or (1 shl o)
        val arrived = now and wet.inv()
        wet = now
        if (arrived == 0) return
        for (o in geo.outletCell.indices) {
            if (arrived and (1 shl o) == 0) continue
            if (closed and (1 shl o) == 0) wrongHits++
            closed = closed xor (1 shl o)
            arrivals?.add((time shl 3) or o)
        }
    }

    private fun write(c: Int, v: Int) {
        cells[c] = v
        onWrite?.invoke(c, phase)
    }

    private fun set(c: Int, v: Int, notify: Boolean) {
        val before = cells[c]
        write(c, v)
        // A new flowing block schedules itself (onBlockAdded): not one that was flowing already.
        if (v >= 0 && v and DYN != 0 && !(before >= 0 && before and DYN != 0)) schedule(c)
        if (notify) notify(c)
    }

    private fun schedule(c: Int) {
        if (due[c] != NONE) return
        val t = time + RATE
        due[c] = t
        val b = t and 7
        queue[b][queueLen[b]++] = c
    }

    /** A block changed at [c]: its still water neighbours start flowing (BlockStaticLiquid.updateLiquid). */
    private fun notify(c: Int) {
        for (d in geo.notifyOrder) {
            val q = c + d
            val v = cells[q]
            if (v >= 0 && v and DYN == 0) { write(q, v or DYN); schedule(q) }
        }
    }

    private fun runDue() {
        val b = time and 7
        val n = queueLen[b]
        if (n == 0) return
        val list = queue[b]
        // All due entries leave the queue before any runs (WorldServer.tickUpdates); new ones are 5 ticks out, another bucket.
        var m = 0
        for (i in 0 until n) { val c = list[i]; if (due[c] == time) { due[c] = NONE; list[m++] = c } }
        queueLen[b] = 0
        for (i in 0 until m) {
            val c = list[i]
            val v = cells[c]
            if (v >= 0 && v and DYN != 0) update(c)
        }
    }

    private fun level(c: Int): Int { val v = cells[c]; return if (v >= 0) v and 15 else -1 }
    private fun blocked(c: Int): Boolean = cells[c] == SOLID

    private fun checkAdjacent(q: Int, cur: Int): Int {
        var i = level(q)
        if (i < 0) return cur
        if (i == 0) adj++
        if (i >= 8) i = 0
        return if (cur >= 0 && i >= cur) cur else i
    }

    /** BlockDynamicLiquid.updateTick. */
    private fun update(c: Int) {
        var i = cells[c] and 15
        if (i > 0) {
            var l = -100
            adj = 0
            for (d in geo.horizontal) l = checkAdjacent(c + d, l)
            var i1 = l + 1
            if (i1 >= 8 || l < 0) i1 = -1
            val up = level(c + geo.w)
            if (up >= 0) i1 = if (up >= 8) up else up + 8
            if (adj >= 2) {
                val below = c - geo.w
                if (cells[below] == SOLID || level(below) == 0) i1 = 0
            }
            if (i1 == i) {
                write(c, i)                                   // settles: still water
            } else {
                i = i1
                if (i1 < 0) {
                    set(c, AIR, true)
                } else {
                    set(c, i1 or DYN, false)
                    schedule(c)
                    notify(c)
                }
            }
        } else {
            write(c, i)
        }
        // Vanilla 1.8 carries on with the new level when the block just dried up (i = -1) and drops a level-7 block into air
        // below it. Hypixel's server doesn't: recorded 2026-10-12 (a88e9b, coal pushed over a falling column), the dried block
        // went to air and nothing was ever written below it.
        if (i < 0) return
        val below = c - geo.w
        if (cells[below] == AIR) {
            tryFlow(below, if (i >= 8) i else i + 8)
        } else if (i >= 0 && (i == 0 || blocked(below))) {
            val dirs = flowDirections(c)
            val k1 = if (i >= 8) 1 else i + 1
            if (k1 >= 8) return
            for (d in geo.setOrder) if (dirs and dirBit(d) != 0) tryFlow(c + d, k1)
        }
    }

    /**
     * A random tick (WorldServer.updateBlocks, after the scheduled ticks): flowing water is randomly ticked in 1.8 (BlockLiquid
     * sets tickRandomly; only BlockStaticLiquid of water turns it off), and a random tick is a whole updateTick. It can only
     * move water sooner, never later. Hypixel's random ticks can't be predicted; [Tracker] places them from the packets.
     * Measured on Hypixel: about 1 per 350 flowing-water ticks (3 seen in 1116; vanilla randomTickSpeed 3 would be 1/1365).
     */
    fun randomTick(c: Int) {
        val v = cells[c]
        if (v >= 0 && v and DYN != 0) update(c)
    }

    private fun tryFlow(q: Int, lvl: Int) {
        if (cells[q] == AIR) set(q, lvl or DYN, true)
    }

    private fun dirBit(d: Int): Int = if (d > 0) 1 else 2

    private fun flowDirections(c: Int): Int {
        var best = 1000
        var set = 0
        for (d in geo.horizontal) {
            val q = c + d
            if (blocked(q)) continue
            val v = cells[q]
            if (v >= 0 && v and 15 == 0) continue
            val j = if (blocked(q - geo.w)) flowCost(q, 1, -d) else 0
            if (j < best) set = 0
            if (j <= best) { set = set or dirBit(d); best = j }
        }
        return set
    }

    private fun flowCost(c: Int, dist: Int, cameFrom: Int): Int {
        var best = 1000
        for (d in geo.horizontal) {
            if (d == cameFrom) continue
            val q = c + d
            if (blocked(q)) continue
            val v = cells[q]
            if (v >= 0 && v and 15 == 0) continue
            if (!blocked(q - geo.w)) return dist
            if (dist < 4) {
                val j = flowCost(q, dist + 1, -d)
                if (j < best) best = j
            }
        }
        return best
    }

    companion object {
        const val AIR = -1
        const val SOLID = -2
        const val DYN = 16
        const val NONE = Int.MIN_VALUE
        /** Water's tick rate. */
        const val RATE = 5
        /** A pushed block lands this many ticks after its piston's block event. */
        const val LAND = 2
        const val WATER_LEVER = 6
        const val WATER_BIT = 1 shl WATER_LEVER
        const val BLOCK_LEVERS = (1 shl 6) - 1

        /** A board at rest: [open] cells air, slots per [Geometry], [slotIn] which blocks are in; gates [closed]. */
        fun fresh(geo: Geometry, slotIn: BooleanArray, closed: Int, time: Int = 0): WaterSim {
            val cells = IntArray(geo.w * geo.h) { SOLID }
            for (c in geo.open) cells[c] = AIR
            for (k in geo.slotCell.indices) cells[geo.slotCell[k]] = if (slotIn[k]) SOLID else AIR
            cells[geo.source] = SOLID
            val n = cells.size
            val s = WaterSim(geo, cells, IntArray(n) { NONE }, Array(8) { IntArray(n) }, IntArray(8), slotIn.copyOf(), IntArray(geo.slotCell.size) { NONE })
            s.closed = closed; s.time = time
            return s
        }

        /**
         * A board as seen mid-flow: [water] per cell is a level or -1, all of it taken as still (the server's flowing/still split
         * and its pending ticks can't be seen; still water wakes up as soon as anything near it changes). [sourceOn]: the inlet.
         */
        fun observed(geo: Geometry, slotIn: BooleanArray, closed: Int, water: IntArray, sourceOn: Boolean, wet: Int, time: Int): WaterSim {
            val s = fresh(geo, slotIn, closed, time)
            for (c in water.indices) if (water[c] >= 0 && s.cells[c] == AIR) s.cells[c] = water[c]
            if (sourceOn) { s.cells[geo.source] = 0; s.sourceOn = true }
            s.wet = wet
            // everything that could move next is woken: a still block that is wrong for its neighbours recomputes in 5 ticks
            for (c in s.cells.indices) if (s.cells[c] >= 0) { s.cells[c] = s.cells[c] or DYN; s.schedule(c) }
            return s
        }
    }
}

/** A growable int list without boxing. */
class IntArrayList(cap: Int = 16) {
    var a = IntArray(cap); private set
    var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun copy(): IntArrayList { val o = IntArrayList(maxOf(1, size)); for (i in 0 until size) o.add(a[i]); return o }
    fun clear() { size = 0 }
}
