package com.engineerclient.p3sim

import java.math.BigInteger

/**
 * A custom practice as a short code to share (the Practice tab's Export / Import): its start, the jobs picked,
 * checkpoints, leap waypoints and start timer, packed into as few bits as each needs, written in 79 characters
 * that paste cleanly into chat and Discord (no markdown, emoji or mention characters). About 16 characters for
 * a start and a few jobs.
 *
 * Positions are to 1/32 of a block in the arena's box, angles to 1/4 degree. Checkpoints keep no facing (only
 * where they are counts). Jobs are indices into [P3Plan.allJobs] (a fixed list), as a list or a mask, whichever
 * is shorter.
 */
object PracticeCode {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!$%&()+-./=?[]^{}"
    private val BASE = BigInteger.valueOf(ALPHABET.length.toLong())
    private const val VERSION = 0

    // The arena's box (arena.bin), in 1/32 blocks.
    private const val X0 = -8.0; private const val Y0 = 0.0; private const val Z0 = -8.0
    private const val POS_BITS = 13
    private const val YAW_BITS = 11
    private const val PITCH_BITS = 10

    class Custom(val start: Spots.Spot?, val jobs: Set<String>, val checkpoints: List<Spots.Spot>, val leaps: List<Spots.Spot>, val delay: Double)

    fun encode(c: Custom): String {
        val w = Bits()
        w.put(VERSION, 2)
        w.put(if (c.start != null) 1 else 0, 1)
        c.start?.let { w.spot(it, true) }
        w.put(Math.round(c.delay * 10).toInt().coerceIn(0, 63), 6)
        val all = P3Plan.allJobs()
        val idx = all.indices.filter { all[it] in c.jobs }
        val width = bitsFor(all.size)
        val asList = Bits().apply { gamma(idx.size + 1); idx.forEach { put(it, width) } }
        if (asList.size < all.size) { w.put(1, 1); w.append(asList) }
        else { w.put(0, 1); all.forEach { w.put(if (it in c.jobs) 1 else 0, 1) } }
        w.gamma(c.checkpoints.size + 1)
        c.checkpoints.forEach { w.spot(it, false) }
        w.put(c.leaps.size.coerceAtMost(4), 3)
        c.leaps.take(4).forEach { w.spot(it, true) }
        // A leading 1 keeps the leading zeros.
        var n = BigInteger.ONE
        for (b in w.bits) n = n.shiftLeft(1).or(if (b) BigInteger.ONE else BigInteger.ZERO)
        val out = StringBuilder()
        while (n.signum() > 0) { val (q, r) = n.divideAndRemainder(BASE); out.append(ALPHABET[r.toInt()]); n = q }
        return out.reverse().toString()
    }

    /** The practice in [code], null: not a code (or a newer version's). */
    fun decode(code: String): Custom? = runCatching {
        var n = BigInteger.ZERO
        for (ch in code.filterNot { it.isWhitespace() }) {
            val d = ALPHABET.indexOf(ch); require(d >= 0)
            n = n.multiply(BASE).add(BigInteger.valueOf(d.toLong()))
        }
        require(n.bitLength() >= 2)
        val r = Reader((n.bitLength() - 2 downTo 0).map { n.testBit(it) })
        require(r.get(2) == VERSION)
        val start = if (r.get(1) == 1) r.spot("Start", true) else null
        val delay = r.get(6) / 10.0
        val all = P3Plan.allJobs()
        val jobs = if (r.get(1) == 1) List(r.count()) { all[r.get(bitsFor(all.size))] }.toSet()
            else all.filter { r.get(1) == 1 }.toSet()
        val cps = List(r.count()) { r.spot("Checkpoint ${it + 1}", false) }
        val leaps = List(r.get(3).coerceAtMost(4)) { r.spot("Leap ${it + 1}", true) }
        Custom(start, jobs, cps, leaps, delay.coerceIn(0.0, 5.0))
    }.getOrNull()

    private fun bitsFor(n: Int) = 32 - Integer.numberOfLeadingZeros((n - 1).coerceAtLeast(1))

    private class Bits {
        val bits = ArrayList<Boolean>()
        val size get() = bits.size
        fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bits += (v shr i) and 1 == 1 }
        fun append(o: Bits) { bits += o.bits }
        /** Elias gamma, [v] >= 1: as many zeros as it has bits after the first, then it. */
        fun gamma(v: Int) { val n = 32 - Integer.numberOfLeadingZeros(v); put(0, n - 1); put(v, n) }
        fun pos(v: Double, o: Double) = put(Math.round((v - o) * 32).toInt().coerceIn(0, (1 shl POS_BITS) - 1), POS_BITS)
        fun spot(s: Spots.Spot, facing: Boolean) {
            pos(s.x, X0); pos(s.y, Y0); pos(s.z, Z0)
            if (!facing) return
            put(Math.floorMod(Math.round(s.yaw * 4), 1440), YAW_BITS)
            put(Math.round((s.pitch.coerceIn(-90f, 90f) + 90f) * 4), PITCH_BITS)
        }
    }

    private class Reader(val bits: List<Boolean>) {
        var i = 0
        fun get(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or (if (bits[i++]) 1 else 0) }; return v }
        fun gamma(): Int { var z = 0; while (!bits[i]) { z++; i++ }; require(z < 31); return get(z + 1) }
        /** A list's length ([gamma] - 1): each entry takes a bit or more, so a bad code can't ask for a huge list. */
        fun count(): Int = (gamma() - 1).also { require(it <= bits.size - i) }
        fun pos(o: Double) = get(POS_BITS) / 32.0 + o
        fun spot(name: String, facing: Boolean): Spots.Spot {
            val x = pos(X0); val y = pos(Y0); val z = pos(Z0)
            if (!facing) return Spots.Spot(name, x, y, z, 0f)
            val yaw = get(YAW_BITS) / 4f
            val pitch = get(PITCH_BITS) / 4f - 90f
            return Spots.Spot(name, x, y, z, if (yaw > 180f) yaw - 360f else yaw, pitch)
        }
    }
}
