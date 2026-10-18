package com.engineerclient.p3sim.clear.traps

import com.engineerclient.p3sim.clear.puzzles.hard.BlockEdit

/**
 * One crusher: a slab of [material] blocks (the box [x0, y0, z0, x1, y1, z1], canonical frame, absolute y) that grows [sweep] layers along
 * [axis] (`x` or `z`) in direction [sign] and shrinks back. The slab itself is the arm's home and never changes. Every step out puts one more
 * layer of the slab's size next to the last one and every step back takes the tip layer away, so at full reach the crusher is a solid wall
 * [sweep] layers deep (as recorded on Hypixel; nothing slides).
 *
 * [startAt] is the room-clock tick of its first step out: a room wakes and its crushers begin to move 4 ticks later, not a whole rest later.
 */
class Crusher(
    val id: String,
    val slab: IntArray,
    val axis: Char,
    val sign: Int,
    val sweep: Int,
    val material: String,
    val timing: CrusherTiming,
    val startAt: Int = START_LAG,
) {
    /**
     * How many layers out the crusher is [t] ticks into the room's hazard clock. Before [startAt] it is at rest; its first step out lands at
     * [startAt], then one step every `ticksPerBlock` ticks out, back the same way, and `stillTicks` after the last step back the next one out.
     */
    fun extensionAt(t: Int): Int {
        if (t < startAt) return 0
        val period = timing.period(sweep)
        val u = Math.floorMod(t - startAt + timing.stillTicks, period)
        if (u < timing.stillTicks) return 0
        val moves = minOf(2 * sweep, (u - timing.stillTicks) / timing.ticksPerBlock + 1)
        return if (moves <= sweep) moves else 2 * sweep - moves
    }

    /** The cells of layer [j] (1 is next to the slab). */
    fun layer(j: Int): List<IntArray> {
        val dx = if (axis == 'x') sign * j else 0
        val dz = if (axis == 'z') sign * j else 0
        val out = ArrayList<IntArray>()
        for (y in slab[1]..slab[4]) for (z in slab[2]..slab[5]) for (x in slab[0]..slab[3]) out += intArrayOf(x + dx, y, z + dz)
        return out
    }

    /** The cells the arm fills when it is [k] layers out: layers 1..k, never the home slab. */
    fun cells(k: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (j in 1..k) out += layer(j)
        return out
    }

    /**
     * The box (inclusive cell corners x0, y0, z0, x1, y1, z1) the arm fills when it is [k] layers out (k at least 1): the layers are slab-sized
     * and one cell apart, so together they are one solid box.
     */
    fun armBox(k: Int): IntArray {
        val a = sign; val b = sign * k
        val lo = minOf(a, b); val hi = maxOf(a, b)
        val x0 = slab[0] + if (axis == 'x') lo else 0; val x1 = slab[3] + if (axis == 'x') hi else 0
        val z0 = slab[2] + if (axis == 'z') lo else 0; val z1 = slab[5] + if (axis == 'z') hi else 0
        return intArrayOf(x0, slab[1], z0, x1, slab[4], z1)
    }

    /** The block edits that take the arm from [from] layers out to [to]: growing adds the layers `from+1..to`, shrinking clears `to+1..from`. */
    fun edits(from: Int, to: Int): List<BlockEdit> {
        if (from == to) return emptyList()
        val out = ArrayList<BlockEdit>()
        if (to > from) for (j in from + 1..to) for (c in layer(j)) out += BlockEdit(c[0], c[1], c[2], material)
        else for (j in from downTo to + 1) for (c in layer(j)) out += BlockEdit(c[0], c[1], c[2], AIR)
        return out
    }

    /** The middle of the tip layer's face when the arm is [k] layers out, for the slam sound: (x, y, z) in the canonical frame. */
    fun tipFace(k: Int): DoubleArray {
        val tip = layer(k.coerceAtLeast(1))
        val xs = tip.map { it[0] }; val zs = tip.map { it[2] }
        val cx = (xs.min() + xs.max() + 1) / 2.0
        val cz = (zs.min() + zs.max() + 1) / 2.0
        val fx = if (axis == 'x') (if (sign > 0) xs.max() + 1.0 else xs.min().toDouble()) else cx
        val fz = if (axis == 'z') (if (sign > 0) zs.max() + 1.0 else zs.min().toDouble()) else cz
        return doubleArrayOf(fx, slab[1].toDouble(), fz)
    }

    companion object {
        const val AIR = "minecraft:air"

        /** Ticks between a room waking up (the first player inside) and its crushers' first step (recorded 0..7, median 4). */
        const val START_LAG = 4
    }
}
