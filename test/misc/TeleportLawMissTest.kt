package com.engineerclient.misc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Captured teleport misses (Legit Zpew's misses format, which ascentclient's Capture Misses also writes; these came from
 * ascentclient) replayed through the law, from the blocks as they were at the click (missing cells are air).
 * teleport-law-misses.txt holds the seven lines captured at click time, in order (debug log times 151083, 156083, 159689,
 * 167175, 184409, 184619, 207492).
 *
 * The law is right in every case it can be judged on; the client's input was not the server's:
 * - 167175, 184619, 207492: the server cast from its own position, which it sent as a sync packet (velocity kept) between the
 *   click and the answer. From there the law gives the server's answer.
 * - 159689: the law lands where the server did; the client's refusal came from outside the law.
 * Not asserted: 151083 (a blink 0.75 straight up out of water moved, against #items.blink-refusal), 156083 (the answer was a
 * sync packet, not a landing), 184409 (no position or look near the click's reaches the server's target cell).
 */
class TeleportLawMissTest {
    class Miss(val kind: String, val ether: Boolean, val sneaking: Boolean, val distance: Double, val from: DoubleArray,
               val yaw: Float, val pitch: Float, val predicted: DoubleArray?, val actual: DoubleArray?, val cells: TeleportLaw.Cells)

    private fun vec(s: String) = if (s == "-") null else s.split(',').map { it.toDouble() }.toDoubleArray()

    private fun parse(line: String): Miss {
        val f = line.split('\t')
        val ids = HashMap<Long, String>(); val tops = HashMap<Long, Double>()
        fun key(x: Int, y: Int, z: Int) = (x.toLong() and 0x3FFFFFF shl 38) or (z.toLong() and 0x3FFFFFF shl 12) or (y.toLong() and 0xFFF)
        if (f.size > 8 && f[8].isNotEmpty()) for (b in f[8].split(';')) {
            val p = b.split(' ', limit = 6)
            val k = key(p[0].toInt(), p[1].toInt(), p[2].toInt())
            ids[k] = p[3]; tops[k] = p[4].toDouble()
        }
        val cells = object : TeleportLaw.Cells {
            override fun id(x: Int, y: Int, z: Int) = ids[key(x, y, z)] ?: "air"
            override fun top(x: Int, y: Int, z: Int) = tops[key(x, y, z)] ?: 0.0
        }
        val (yaw, pitch) = f[5].split(' ').map { it.toFloat() }
        return Miss(f[0], f[1] == "ether", f[2] == "true", f[3].toDouble(), vec(f[4])!!, yaw, pitch, vec(f[6]), vec(f[7]), cells)
    }

    private val misses = javaClass.getResource("/teleport-law-misses.txt")!!.readText().lines().filter { it.isNotBlank() }.map(::parse)

    /** The law for [m]'s click, from [from] (the server's position when it differs from the captured one). */
    private fun law(m: Miss, from: DoubleArray = m.from) =
        if (m.ether) TeleportLaw.etherwarp(from[0], from[1], from[2], m.yaw, m.pitch, m.distance, m.cells)
        else TeleportLaw.blink(from[0], from[1], from[2], m.yaw, m.pitch, m.sneaking, m.distance.toInt(), m.cells)

    private fun assertAt(expected: DoubleArray, actual: DoubleArray?) {
        val a = assertNotNull(actual)
        for (k in 0..2) assertEquals(expected[k], a[k], 1e-9)
    }

    @Test
    fun loadsTheCapturedClicks() {
        assertEquals(7, misses.size)
        // the harness reproduces what the client predicted for each wrong landing
        for (m in misses.filter { it.kind == "wrong-landing" }) assertAt(m.predicted!!, law(m))
    }

    @Test
    fun etherwarpOverIronBarsLandsWhereTheServerDid() {
        // 159689: the ray passes under the bars at x -92 and hits the floor at (-91, 68, -57)
        val m = misses[2]
        assertAt(m.actual!!, law(m))
    }

    @Test
    fun hyperionRefusedFromTheServersPosition() {
        // 167175: the server had you at -19.787, -37.559 (its sync packet); from there the blink lands in your own spot
        val m = misses[3]
        assertAt(m.predicted!!, law(m))
        assertNull(law(m, doubleArrayOf(-19.787, 77.0, -37.559)))
    }

    @Test
    fun blinkFromTheServersPositionAfterAMissedEtherwarp() {
        // 184619: the server's sync put you at -60.670, -53.894 before the click was handled
        val m = misses[5]
        assertAt(m.actual!!, law(m, doubleArrayOf(-60.670, 70.0, -53.894)))
    }

    @Test
    fun blinkFromTheServersPositionOnACellEdge() {
        // 207492: z -184.053 by the client, -183.974 by the server's sync: the other side of z = -184
        val m = misses[6]
        assertAt(m.actual!!, law(m, doubleArrayOf(-145.503, 70.0, -183.974)))
    }
}
