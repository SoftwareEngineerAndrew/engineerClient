package com.engineerclient.misc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
 *
 * teleport-law-misses-2.txt: twelve of eighteen Legit Zpew misses (2026-11-12), by their line in that capture (#1-#18):
 * - #1, #2: blinks past cobblestone walls and stairs. The law moves you with the 1.8 collision shapes (TeleportLaw.shapes);
 *   Zpew had whole-cell boxes, which end the walk beside a wall.
 * - #3, #6, #8, #13 (etherwarps) and #7, #9 (blinks): a sneak changed the tick before the click and Zpew cast from the other
 *   pose's eye; the server cast from the click's own sneak's (1.27 etherwarp, 1.62 standing blink), as alldata has it.
 * - #4, #15, #17, #18: the law from where you were at the click is the server's answer; Zpew had cast from where you were at
 *   an earlier click whose etherwarp or blink the server refused (jumping, falling, swimming in between).
 * Not here: #5 (only a cast from your next tick's position, 0.65 lower, refuses as the server did), #12 (no position or look
 * within a block reaches the server's landing), #14 (refused by the server, the law warps; 0.2 higher refuses), #16 (only
 * from 0.42 or more lower). #10, #11 are in the third file.
 *
 * teleport-law-misses-3.txt: eighteen lines of the same capture's 119 (2026-11-12, recorded before 107b586a), by their line
 * there:
 * - #10, #11, #53, #88: blinks through a one-high gap in an iron bar wall (bars in the feet cell and the one over the step,
 *   three walls). The server moved you through: a step you don't fit in is passed over, not the end of the walk. Iron bars
 *   stay solid: #49, #52 (straight up under a column of bars) stop under them, as the server did.
 * - #117, #50: the server cast from your next tick's position (a jump's second tick, a fall's next tick), not the click's.
 * - #25, #110 (blinks) and #79, #80, #119 (etherwarps): the click's own sneak's eye, as in #6-#9 of the second file.
 * - #44, #65, #102: blinks the 1.8 shapes move further than whole-cell boxes, as in #1, #2.
 * - #29, #30: one etherwarp landing taken for the first of two clicks (refused by the law): it is the second click's.
 */
class TeleportLawMissTest {
    class Miss(val kind: String, val ether: Boolean, val sneaking: Boolean, val distance: Double, val from: DoubleArray,
               val yaw: Float, val pitch: Float, val predicted: DoubleArray?, val actual: DoubleArray?, val cells: TeleportLaw.Cells)

    private fun vec(s: String) = if (s == "-") null else s.split(',').map { it.toDouble() }.toDoubleArray()

    /** The captured blocks: id, collision top and the state's properties, by cell. */
    class World {
        val ids = HashMap<Long, String>(); val tops = HashMap<Long, Double>(); val props = HashMap<Long, Map<String, String>>()
        fun key(x: Int, y: Int, z: Int) = (x.toLong() and 0x3FFFFFF shl 38) or (z.toLong() and 0x3FFFFFF shl 12) or (y.toLong() and 0xFFF)

        fun add(blocks: String) {
            if (blocks.isEmpty()) return
            for (b in blocks.split(';')) {
                val p = b.split(' ', limit = 6)
                val k = key(p[0].toInt(), p[1].toInt(), p[2].toInt())
                ids[k] = p[3]; tops[k] = p[4].toDouble()
                props[k] = p.getOrNull(5)?.substringAfter('[', "")?.substringBefore(']')?.split(',')?.filter { '=' in it }
                    ?.associate { it.substringBefore('=') to it.substringAfter('=') } ?: emptyMap()
            }
        }

        /** [shapes]: the 1.8 collision shapes, as Zpew's world gives them; false: the default whole-cell boxes up to the top. */
        fun cells(shapes: Boolean = true) = object : TeleportLaw.Cells {
            override fun id(x: Int, y: Int, z: Int) = ids[key(x, y, z)] ?: "air"
            override fun top(x: Int, y: Int, z: Int) = tops[key(x, y, z)] ?: 0.0
            override fun boxes(x: Int, y: Int, z: Int) =
                if (shapes) TeleportLaw.shapes(id(x, y, z)) { props[key(x, y, z)]?.get(it) } else super.boxes(x, y, z)
        }
    }

    private fun parse(line: String): Miss {
        val f = line.split('\t')
        val world = World().apply { add(f.getOrElse(8) { "" }) }
        val cells = world.cells()
        val (yaw, pitch) = f[5].split(' ').map { it.toFloat() }
        return Miss(f[0], f[1] == "ether", f[2] == "true", f[3].toDouble(), vec(f[4])!!, yaw, pitch, vec(f[6]), vec(f[7]), cells)
    }

    private fun lines(name: String) = javaClass.getResource(name)!!.readText().lines().filter { it.isNotBlank() }
    private val misses = lines("/teleport-law-misses.txt").map(::parse)
    private val lines2 = lines("/teleport-law-misses-2.txt")
    private val misses2 = lines2.map(::parse)
    private val numbers2 = intArrayOf(1, 2, 3, 4, 6, 7, 8, 9, 13, 15, 17, 18)
    /** The second capture's miss by its line there (#1-#18). */
    private fun m2(n: Int) = misses2[numbers2.indexOf(n).also { check(it >= 0) }]
    /** Its blocks, as a world to add more to. */
    private fun world2(vararg n: Int) = World().apply { for (k in n) add(lines2[numbers2.indexOf(k)].split('\t')[8]) }

    /** The law for [m]'s click, from [from] (the server's position when it differs from the captured one). */
    private fun law(m: Miss, from: DoubleArray = m.from, eye: Double? = null, cells: TeleportLaw.Cells = m.cells) =
        if (m.ether) TeleportLaw.etherwarp(from[0], from[1], from[2], m.yaw, m.pitch, m.distance, cells, eye ?: TeleportLaw.SNEAK_EYE)
        else TeleportLaw.blink(from[0], from[1], from[2], m.yaw, m.pitch, m.sneaking, m.distance.toInt(), cells, eye)

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

    @Test
    fun loadsTheSecondCapture() {
        assertEquals(12, misses2.size)
    }

    @Test
    fun blinksPassBesideWallsAndStairs() {
        // #1, #2: refused with whole-cell boxes (Zpew's world before), moved with the 1.8 shapes, as the server did
        for (n in intArrayOf(1, 2)) {
            val m = m2(n)
            assertNull(law(m, cells = world2(n).cells(shapes = false)))
            assertAt(m.actual!!, law(m))
        }
    }

    @Test
    fun aSneakChangedTheTickBeforeStillCastsFromTheClicksOwnEye() {
        // etherwarps: 1.62 (the pose before the sneak) is what Zpew predicted, 1.27 what the server did
        for (n in intArrayOf(6, 8, 13)) {
            val m = m2(n)
            assertAt(m.predicted!!, law(m, eye = TeleportLaw.STAND_EYE))
            assertAt(m.actual!!, law(m))
        }
        // #3: refused by the server, and by the law from 1.27
        m2(3).let { assertAt(it.predicted!!, law(it, eye = TeleportLaw.STAND_EYE)); assertNull(law(it)) }
        // blinks just after letting go of sneak: 1.27 predicted, 1.62 cast
        for (n in intArrayOf(7, 9)) {
            val m = m2(n)
            assertAt(m.predicted!!, law(m, eye = TeleportLaw.SNEAK_EYE))
            assertAt(m.actual!!, law(m))
        }
    }

    @Test
    fun clicksInTheAirCastFromWhereYouWereAtTheClick() {
        for (n in intArrayOf(4, 15, 17, 18)) m2(n).let { assertAt(it.actual!!, law(it)) }
        // #17: Zpew's prediction was the cast from the ground before the jump (y 65); #18: from the tick before (y 70.177)
        m2(17).let { assertAt(it.predicted!!, law(it, doubleArrayOf(it.from[0], 65.0, it.from[2]))) }
        m2(18).let { assertAt(it.predicted!!, law(it, doubleArrayOf(it.from[0], 70.17676, it.from[2]))) }
    }

    @Test
    fun aClickAfterARefusedEtherwarpIsCastFromWhereYouAre() {
        // #3 then #4, jumping: the server refused #3 (silently), so it cast #4 from where you were for #4, not for #3
        val a = m2(3); val b = m2(4)
        val cells = world2(3, 4).cells()
        val q = ZpewQueue<Miss> { from, m -> law(m, from, cells = cells) }
        q.click(a, 0, a.from, true, wall = 0)
        q.expire(20, 20) // #3 stops counting: dropped
        q.click(b, 21, b.from, true, wall = 21)
        assertAt(b.actual!!, q.camera)
        assertTrue(q.teleport(b.actual, 24))
        assertEquals(1, q.lastMatch)
        assertNull(q.camera)
    }

    private val misses3 = lines("/teleport-law-misses-3.txt").map(::parse)
    private val numbers3 = intArrayOf(10, 11, 53, 88, 49, 52, 117, 50, 25, 110, 79, 80, 119, 44, 65, 102, 29, 30)
    /** The third file's miss by its line in the capture (#1-#119). */
    private fun m3(n: Int) = misses3[numbers3.indexOf(n).also { check(it >= 0) }]

    @Test
    fun loadsTheThirdCapture() {
        assertEquals(numbers3.size, misses3.size)
    }

    @Test
    fun blinksGoThroughAOneHighGapInABarWall() {
        // the gap's step has bars under and over it: no room for you, but the walk goes on past it
        for (n in intArrayOf(10, 11, 53, 88)) m3(n).let { assertAt(it.actual!!, law(it)) }
    }

    @Test
    fun ironBarsStillStopABlink() {
        // straight up a shaft under a column of bars: you land under the bars
        for (n in intArrayOf(49, 52)) m3(n).let { assertAt(it.actual!!, law(it)) }
    }

    @Test
    fun someClicksCastFromYourNextTick() {
        // #117: jumping (69.42 at the click: through both bar walls' gaps); from the jump's next tick (69.753) the ray is over the
        // second wall's gap, in its bars, and the walk ends there, as the server's did
        m3(117).let {
            assertAt(doubleArrayOf(-158.5, 70.0, -184.5), law(it))
            assertAt(it.actual!!, law(it, doubleArrayOf(it.from[0], 69.7532, it.from[2])))
        }
        // #50: falling (68.855); straight up from there is under 1.5 and refused, from the next tick (68.407) it moves you
        m3(50).let {
            assertNull(law(it))
            assertAt(it.actual!!, law(it, doubleArrayOf(it.from[0], 68.4074, it.from[2])))
        }
    }

    @Test
    fun moreClicksCastFromTheirOwnSneaksEye() {
        for (n in intArrayOf(79, 80, 119)) m3(n).let {
            assertAt(it.predicted!!, law(it, eye = TeleportLaw.STAND_EYE))
            assertAt(it.actual!!, law(it))
        }
        for (n in intArrayOf(25, 110)) m3(n).let {
            assertAt(it.predicted!!, law(it, eye = TeleportLaw.SNEAK_EYE))
            assertAt(it.actual!!, law(it))
        }
    }

    @Test
    fun moreBlinksPassBesideStairsAndWalls() {
        val worlds = mapOf(44 to 13, 65 to 14, 102 to 15)
        for ((n, k) in worlds) {
            val m = m3(n)
            val whole = World().apply { add(lines("/teleport-law-misses-3.txt")[k].split('\t')[8]) }.cells(shapes = false)
            assertAt(m.predicted!!, law(m, cells = whole))
            assertAt(m.actual!!, law(m))
        }
    }

    @Test
    fun aLandingTakenForTheWrongClick() {
        // #29's own ray finds nothing; the landing is #30's (the next click, from nearly the same spot)
        assertNull(law(m3(29)))
        assertAt(m3(29).actual!!, law(m3(30)))
    }
}
