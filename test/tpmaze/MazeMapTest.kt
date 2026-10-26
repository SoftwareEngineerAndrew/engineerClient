package com.engineerclient.tpmaze

import com.engineerclient.tpmaze.MazePads.END
import com.engineerclient.tpmaze.MazePads.LAND
import com.engineerclient.tpmaze.MazePads.START
import com.engineerclient.tpmaze.MazePads.START_AREA
import com.engineerclient.tpmaze.MazePads.cell
import com.engineerclient.tpmaze.MazePads.padsOf
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Teleport Maze's shared map and the team plan, on mazes drawn to the rules the ADR recordings show (tools/tpmaze). */
class MazeMapTest {

    @Test fun landingCellsAreWhereTheRecordingsLandPlayers() {
        // the start pad's landing (14.5, 11.5), the end pad's (16.5, 15.5), and a cell pad's: the far corner of its patch
        assertEquals(listOf(14, 11), LAND[START].toList())
        assertEquals(listOf(16, 15), LAND[END].toList())
        assertEquals(listOf(9, 11), LAND[2].toList())
        assertEquals(2, MazePads.landingAt(9.5, 11.5))
        assertEquals(-1, MazePads.landingAt(9.2, 11.5))
        assertEquals("1SW", MazePads.label(0))
        assertEquals("7SE", MazePads.label(24))
    }

    @Test fun regionsAndThePadUnderAPlayer() {
        assertEquals(0, MazePads.regionAt(7.0, 9.0))
        assertEquals(START_AREA, MazePads.regionAt(15.5, 11.5))
        assertEquals(MazePads.END_AREA, MazePads.regionAt(16.5, 15.5))
        assertEquals(2, MazePads.padUnder(10.6, 12.3))       // on the frame at (10, 12)
        assertEquals(START, MazePads.padUnder(15.4, 12.6))
        // between cells 6 and 7 the frames are two blocks apart through a wall: only the player's own cell counts
        assertEquals(26, MazePads.padUnder(20.5, 12.9))
        assertEquals(23, MazePads.padUnder(20.5, 14.1))
        assertEquals(-1, MazePads.padUnder(7.0, 9.0))
    }

    @Test fun everyLandingsLineOfSightHoldsTheFinalPad() {
        val rnd = Random(3)
        repeat(2000) {
            val f = rnd.nextInt(28)
            val land = (0 until 28).filter { it != f }.random(rnd)
            val yaw = MazePads.yawTo(LAND[land][0] + 0.5, LAND[land][1] + 0.5, f)
            assertTrue(f in MazePads.aim(land, yaw), "landing $land, final $f")
        }
        // a setback keeps your own yaw: it looks at no landing cell
        assertTrue(MazePads.aim(2, 7.0).isEmpty())
    }

    @Test fun theRoomFrameTurnsPositionsAndYawsTogether() {
        // relative (0, 0) at world block (-185, -153); the room turned a quarter: its +x runs along world +z, its +z along world -x
        val f = RoomFrame(-185, -153, 0, 1, -1, 0)
        val room = doubleArrayOf(9.5, 11.5)
        val w = f.toWorld(room[0], room[1])
        val back = f.toRoom(w[0], w[1])
        assertTrue(abs(back[0] - room[0]) < 1e-9 && abs(back[1] - room[1]) < 1e-9)
        // the world yaw from one landing to another becomes the room yaw between them
        val a = LAND[2].map { it + 0.5 }; val b = LAND[25].map { it + 0.5 }
        val wa = f.toWorld(a[0], a[1]); val wb = f.toWorld(b[0], b[1])
        val worldYaw = Math.toDegrees(kotlin.math.atan2(-(wb[0] - wa[0]), wb[1] - wa[1]))
        assertTrue(abs(f.yawToRoom(worldYaw) - MazePads.yawTo(a[0], a[1], 25)) < 1e-6)
    }

    @Test fun theMapKeepsPairsTwoWayAndOneEach() {
        val m = MazeMap()
        assertTrue(m.link(START, 2))
        assertEquals(START, m.partnerOf(2))
        assertTrue(m.link(3, 17))
        assertEquals(3, m.partnerOf(17))
        // a pad has one partner: a second, different one is a misread
        assertTrue(!m.link(3, 9))
        assertEquals(17, m.partnerOf(3))
        assertNull(m.partnerOf(9))
        assertTrue(m.link(25, END))
        assertEquals(25, m.final)
        assertTrue(!m.link(25, END))
    }

    @Test fun anExactReadReplacesAGuessButNotTheOtherWayRound() {
        val m = MazeMap()
        assertTrue(m.link(3, 17, exact = false))
        assertTrue(m.links(sureOnly = true).isEmpty())
        // the teammate's own client says 3 goes to 21: the guess goes, 17 is unknown again
        assertTrue(m.link(3, 21))
        assertEquals(21, m.partnerOf(3))
        assertNull(m.partnerOf(17))
        // a guess never overrides an exact read
        assertTrue(!m.link(21, 9, exact = false))
        assertEquals(listOf(3, 21), m.links(sureOnly = true).single().toList())
    }

    @Test fun looksPinTheFinalPad() {
        val m = MazeMap()
        val f = 25
        // from the start's landing (pad 2's) and a second landing elsewhere
        assertTrue(m.look(2, MazePads.yawTo(9.5, 11.5, f)))
        assertTrue(f in m.candidates())
        assertTrue(m.look(8, MazePads.yawTo(LAND[8][0] + 0.5, LAND[8][1] + 0.5, f)))
        assertEquals(f, m.final)
        // a look that agrees with nothing left is not a pad's: ignored
        assertTrue(!m.look(13, 7.0))
        assertEquals(f, m.final)
    }

    @Test fun playersInOneCellSplitItsUnusedPads() {
        val m = MazeMap()
        m.link(START, 2)
        m.look(2, MazePads.yawTo(9.5, 11.5, 25))
        val three = MazePlan.plan(m, mapOf("a" to 0, "b" to 0, "c" to 0))
        assertEquals(setOf(0, 1, 3), three.values.map { it.pad }.toSet())
        assertTrue(three.values.all { it.kind == MazePlan.Kind.EXPLORE })
        // the same answer on every client, whatever order it hears the names in
        assertEquals(three.mapValues { it.value.pad }, MazePlan.plan(m, linkedMapOf("c" to 0, "a" to 0, "b" to 0)).mapValues { it.value.pad })
        assertEquals(MazePlan.Kind.START, MazePlan.plan(m, mapOf("d" to START_AREA))["d"]!!.kind)
    }

    @Test fun aStackedTeamSpillsOverToTheNextCellWithUnusedPads() {
        // five leap onto whoever is in cell 1, where two pads are used already: 2 (back to the start) and 1 (to cell 3)
        val m = MazeMap()
        m.link(START, 2)
        m.link(1, 9)
        val plan = MazePlan.plan(m, mapOf("a" to 0, "b" to 0, "c" to 0, "d" to 0, "e" to 0))
        assertEquals(setOf(0, 3), plan.filterValues { it.kind == MazePlan.Kind.EXPLORE }.values.map { it.pad }.toSet())
        val over = plan.filterValues { it.kind == MazePlan.Kind.DETOUR }
        assertEquals(3, over.size)
        assertTrue(over.values.all { it.pad == 1 && it.steps == 1 }, "through pad 1 to cell 3's three unused pads")
    }

    @Test fun aKnownWayToTheFinalCellBeatsExploring() {
        val m = MazeMap()
        m.link(START, 2)                         // the start leads into cell 1
        m.link(3, 17)                            // cell 1 -> cell 5
        m.link(16, 21)                           // cell 5 -> cell 6
        m.link(25, END)                          // the final pad is in cell 7... not linked from 6 yet
        val explore = MazePlan.plan(m, mapOf("a" to 0))["a"]!!
        assertEquals(MazePlan.Kind.EXPLORE, explore.kind)
        m.link(20, 26)                           // cell 6 -> cell 7, the final pad's
        val a = MazePlan.plan(m, mapOf("a" to 0))["a"]!!
        assertEquals(MazePlan.Kind.ROUTE, a.kind)
        assertEquals(3, a.pad)
        assertEquals(4, a.steps)                 // 3 -> cell 5, 16 -> cell 6, 20 -> cell 7, then the final pad
        assertEquals(MazePlan.Kind.FINAL, MazePlan.plan(m, mapOf("b" to 6))["b"]!!.kind)
    }

    // ---- whole mazes ----------------------------------------------------------------------------------------------------

    /** A maze as the recordings show them: [twin] for every pad but the final one (the start pad's too). */
    private class Maze(val twin: IntArray, val final: Int)

    private fun maze(rnd: Random): Maze {
        while (true) {
            val f = rnd.nextInt(28)
            val rest = ((0 until 28).filter { it != f } + START).shuffled(rnd).toMutableList()
            val twin = IntArray(30) { -1 }
            var ok = true
            while (rest.isNotEmpty()) {
                val a = rest.removeAt(rest.size - 1)
                val opts = rest.filter { cell(it) != cell(a) }
                if (opts.isEmpty()) { ok = false; break }
                val b = opts[rnd.nextInt(opts.size)]
                rest.remove(b); twin[a] = b; twin[b] = a
            }
            val x = twin[START]
            if (!ok || cell(x) == cell(f) || padsOf(cell(x)).any { it != x && twin[it] in 0 until 28 && cell(twin[it]) == cell(f) }) continue
            return Maze(twin, f)
        }
    }

    /** Teleports until somebody steps on the final pad: every player follows the plan, sharing one map, one teleport each a round. */
    private fun solve(mz: Maze, players: Int): Int {
        val map = MazeMap()
        val where = HashMap<String, Int>()
        for (i in 0 until players) where["p$i"] = START_AREA
        for (round in 1..60) {
            val plan = MazePlan.plan(map, where)
            for (name in where.keys.sorted()) {
                val pad = assertNotNull(plan[name], "no advice for $name").pad
                if (pad == mz.final) return round
                val to = mz.twin[pad]
                map.link(pad, to)
                map.look(to, MazePads.yawTo(LAND[to][0] + 0.5, LAND[to][1] + 0.5, mz.final))
                where[name] = if (to == START) START_AREA else cell(to)
            }
        }
        error("not solved in 60 rounds")
    }

    @Test fun aTeamSharingTheMapIsFasterAndNeverStuck() {
        val rnd = Random(11)
        val mazes = List(3000) { maze(rnd) }
        val mean = (1..5).associateWith { k -> mazes.map { solve(it, k) }.average() }
        // one player plays as well as going diagonal does (7.7 teleports on these mazes); more players, fewer rounds
        assertTrue(mean[1]!! in 7.2..8.2, "one player: ${mean[1]}")
        assertTrue(mean[3]!! < mean[1]!! - 1.5, "three: ${mean[3]} against one: ${mean[1]}")
        assertTrue(mean[5]!! <= mean[3]!! + 0.05, "five: ${mean[5]} against three: ${mean[3]}")
        println("rounds to the end by team size: " + mean.entries.joinToString { "${it.key}: ${"%.2f".format(it.value)}" })
    }
}
