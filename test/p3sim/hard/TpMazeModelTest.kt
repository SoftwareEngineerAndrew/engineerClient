package com.engineerclient.p3sim.clear.puzzles.hard

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Teleport Maze draw and its walk, against a port of Odin's solver (Odin is BSD-3; the maths below follows `TPMazeSolver` and `VecUtils`). */
class TpMazeModelTest {
    private val pads = TpMazeModel.standardPads()

    private fun maze(seed: Long, groups: Int = 4) = TpMazeModel.generate(pads, 69, seed, groups)

    @Test fun landingsAreOnHalfBlocksAtTheSlabTopAndNeverOnAFrame() {
        for (p in pads) {
            assertEquals(0.5, (p.landX + 0.5) % 1.0)
            assertTrue(pads.none { it.frameX == p.landX && it.frameZ == p.landZ }, "landing of pad ${p.index} is not a frame")
        }
        val m = maze(1)
        val l = m.landing(5)
        assertEquals(69.5, l.y)
        assertEquals(0.0, l.x % 0.5)
        assertEquals(0.0, l.z % 0.5)
    }

    @Test fun everyMazeEndsOnPadTwentyEightAndTheSolutionIsShort() {
        for (seed in 1L..200L) {
            val m = maze(seed, 1 + (seed % 7).toInt())
            var cur = TpMazeModel.START
            var steps = 0
            val sol = m.solution()
            for (p in sol) {
                val land = assertNotNull(m.step(p), "seed $seed pad $p")
                steps++
                cur = land.pad
            }
            assertEquals(TpMazeModel.EXIT, cur, "seed $seed")
            assertTrue(m.solved)
            assertEquals(m.route.size + 1, sol.size, "start plus one way on per route group (seed $seed)")
        }
    }

    @Test fun theSameSeedGivesTheSameMazeAndDifferentSeedsDiffer() {
        val a = maze(7); val b = maze(7); val c = maze(8)
        assertEquals((0 until 30).map { a.destinationOf(it) }, (0 until 30).map { b.destinationOf(it) })
        assertTrue((0 until 30).any { a.destinationOf(it) != c.destinationOf(it) })
    }

    @Test fun aLandingNeverStandsOnItsGroupsWayOn() {
        for (seed in 1L..100L) {
            val m = maze(seed)
            for (p in 0 until 30) {
                val to = m.destinationOf(p) ?: continue
                if (to < 28) assertTrue(to != m.wayOnFrom(to), "seed $seed pad $p lands on the way on of its group")
            }
        }
    }

    @Test fun padTwentyEightDoesNothing() {
        val m = maze(3)
        assertNull(m.step(TpMazeModel.EXIT))
    }

    // ---- Odin's solver, ported -----------------------------------------------------------------------------------------

    private class Box(val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double) {
        fun inflate(dx: Double, dy: Double, dz: Double) = Box(x0 - dx, y0 - dy, z0 - dz, x1 + dx, y1 + dy, z1 + dz)
        fun intersects(o: Box) = x0 < o.x1 && x1 > o.x0 && y0 < o.y1 && y1 > o.y0 && z0 < o.z1 && z1 > o.z0
    }

    private class OdinSolver(val pads: List<Pad>, val y: Int) {
        val visited = LinkedHashSet<Int>()
        var correct: List<Int> = emptyList()
        var best: Int? = null
        private fun padBox(i: Int) = Box(pads[i].frameX.toDouble(), y.toDouble(), pads[i].frameZ.toDouble(), pads[i].frameX + 1.0, y + 1.0, pads[i].frameZ + 1.0)

        private fun interceptable(box: Box, range: Double, px: Double, py: Double, pz: Double, yaw: Float, pitch: Float): Boolean {
            val f2 = -cos(-pitch * 0.017453292f).toDouble()
            val lx = sin(-yaw * 0.017453292f - 3.1415927f) * f2
            val ly = sin(-pitch * 0.017453292f).toDouble()
            val lz = cos(-yaw * 0.017453292f - 3.1415927f) * f2
            val sx = px; val sy = py + 71.0; val sz = pz
            val gx = sx + lx * range; val gy = sy + ly * range; val gz = sz + lz * range
            fun atX(x: Double): DoubleArray? { val d = gx - sx; if (d * d < 1e-7) return null; val t = (x - sx) / d; return if (t in 0.0..1.0) doubleArrayOf(x, sy + (gy - sy) * t, sz + (gz - sz) * t) else null }
            fun atZ(z: Double): DoubleArray? { val d = gz - sz; if (d * d < 1e-7) return null; val t = (z - sz) / d; return if (t in 0.0..1.0) doubleArrayOf(sx + (gx - sx) * t, sy + (gy - sy) * t, z) else null }
            fun inZ(v: DoubleArray?) = v != null && v[2] >= box.z0 && v[2] <= box.z1
            fun inX(v: DoubleArray?) = v != null && v[0] >= box.x0 && v[0] <= box.x1
            return inZ(atX(box.x0)) || inZ(atX(box.x1)) || inX(atZ(box.z0)) || inX(atZ(box.z1))
        }

        fun onTeleport(l: Landing) {
            if (l.y != 69.5 || l.x % 0.5 != 0.0 || l.z % 0.5 != 0.0) return
            val pos = Box(l.x, l.y, l.z, l.x + 1, l.y + 1, l.z + 1).inflate(1.0, 0.0, 1.0)
            val player = Box(l.x - 0.3, l.y, l.z - 0.3, l.x + 0.3, l.y + 1.8, l.z + 0.3).inflate(1.0, 0.0, 1.0)
            for (i in pads.indices) if (pos.intersects(padBox(i)) || player.intersects(padBox(i))) visited += i
            if (correct.isEmpty()) correct = pads.indices.toList()
            val playerNow = Box(l.x - 0.3, l.y, l.z - 0.3, l.x + 0.3, l.y + 1.8, l.z + 0.3)
            correct = correct.filter {
                it !in visited && interceptable(padBox(it).inflate(0.75, 0.0, 0.75).let { b -> Box(b.x0, b.y0, b.z0, b.x1, b.y0 + 4.0, b.z1) }, 32.0, l.x, l.y, l.z, l.yaw, l.pitch) &&
                    !padBox(it).inflate(0.5, 0.0, 0.5).intersects(playerNow)
            }
            val cur = pads.indices.firstOrNull { pos.intersects(padBox(it)) } ?: return
            if (cur in 28..29) { best = null; return }
            val g = cur / 4 * 4
            val candidates = (g until g + 4).filter { it != cur && it !in visited }
            best = candidates.firstOrNull { it in correct } ?: candidates.minByOrNull {
                val yaw = (atan2(pads[it].centreZ - l.z, pads[it].centreX - l.x) * 180.0 / Math.PI).toFloat() - 90f
                abs(wrap(yaw) - wrap(l.yaw))
            }
        }

        private fun wrap(a: Float): Float { var r = a % 360f; if (r >= 180f) r -= 360f; if (r < -180f) r += 360f; return r }
    }

    @Test fun odinsSolverNamesTheWayOnAtEveryLandingOfTheCorrectWalk() {
        for (seed in 1L..300L) {
            val m = maze(seed, 2 + (seed % 6).toInt())
            val odin = OdinSolver(pads, 69)
            var pad = TpMazeModel.START
            var guard = 0
            while (guard++ < 20) {
                val l = m.step(pad) ?: break
                odin.onTeleport(l)
                if (l.pad == TpMazeModel.EXIT) break
                assertEquals(m.wayOnFrom(l.pad), odin.best, "seed $seed after landing on pad ${l.pad}")
                pad = odin.best!!
            }
            assertTrue(m.solved, "seed $seed: following Odin's pointer reaches the exit")
        }
    }

    @Test fun odinsSolverStaysRightAfterWrongSteps() {
        var checked = 0
        for (seed in 1L..300L) {
            val m = maze(seed)
            val odin = OdinSolver(pads, 69)
            val rnd = kotlin.random.Random(seed)
            var pad = TpMazeModel.START
            for (turn in 0 until 25) {
                val l = m.step(pad) ?: break
                odin.onTeleport(l)
                if (l.pad == TpMazeModel.EXIT) break
                val want = m.wayOnFrom(l.pad)!!
                if (want !in odin.visited) { assertEquals(want, odin.best, "seed $seed turn $turn landing ${l.pad}"); checked++ }
                // Half the time step a wrong pad of the group.
                val mates = (l.pad / 4 * 4 until l.pad / 4 * 4 + 4).filter { it != l.pad && it != want }
                pad = if (rnd.nextInt(2) == 0 && mates.isNotEmpty()) mates.random(rnd) else want
            }
        }
        assertTrue(checked > 500, "checked $checked landings")
    }
}
