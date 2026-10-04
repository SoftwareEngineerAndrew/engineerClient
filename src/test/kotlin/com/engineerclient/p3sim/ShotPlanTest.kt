package com.engineerclient.p3sim

import net.minecraft.world.phys.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real shots from Dungeon Recorder recordings (2026-10-03, main server; tools/p3sim/research/terror-mosquito.md):
 * where you stood and looked (the server's view), Hydra stacks, and the main arrow's aim noise (u/3 - look, from
 * its spawn packet). Every arrow ShotPlan makes must, after one vanilla tick (move, x0.99, -0.05), be where
 * Hypixel's exact position packet put it, moving as its velocity packet said.
 */
class ShotPlanTest {

    private class Seen(val v: Vec3, val at: Vec3)

    /** One vanilla arrow tick from [a]'s planned state matches [seen] (positions to 0.001, velocities to 0.0003). */
    private fun check(what: String, a: ShotPlan.Planned, seen: Seen) {
        val after = a.at.add(a.v)
        val v = Vec3(a.v.x * 0.99, a.v.y * 0.99 - 0.05, a.v.z * 0.99)
        assertTrue(after.distanceTo(seen.at) < 0.001, "$what position: $after vs ${seen.at}")
        assertTrue(v.distanceTo(seen.v) < 0.0003, "$what velocity: $v vs ${seen.v}")
    }

    /** The arrow of [arrows] whose next tick lands nearest [seen]. */
    private fun nearest(arrows: List<ShotPlan.Planned>, seen: Seen) = arrows.minBy { it.at.add(it.v).distanceTo(seen.at) }

    /** 13-35-59 n14945: Mosquito at 10 stacks from the i4 plate: main, two Hydra arrows, Duplex. */
    @Test
    fun mosquitoTenStacksOnTheI4Plate() {
        val arrows = ShotPlan.plan(Bows.MOSQUITO, Vec3(63.65173081679392, 127.0, 35.691138242412784), -75.4064f, 20.219725f, false, 10,
            Vec3(-0.010396480349954551, -0.0008791983211820509, -0.011699856409059989), 4)
        assertEquals(5, arrows.size)
        val main = Seen(Vec3(2.903314411279985, -1.2250503570774582, 0.7269730818531404), Vec3(69.27319116258133, 126.29337180650322, 37.116753614308685))
        val hydraA = Seen(Vec3(2.9761948361106025, -1.2250503570774582, 0.31569309650247224), Vec3(69.34685438479745, 126.29337180650322, 36.70144396408973))
        val hydraB = Seen(Vec3(2.7740340596960262, -1.2250503570774582, 1.1239699688701705), Vec3(69.14244489390046, 126.29337180650322, 37.51776956049822))
        check("main", arrows[0], main)
        assertTrue(arrows[0].owned)
        check("hydra +", nearest(arrows.subList(1, 3), hydraA), hydraA)
        check("hydra -", nearest(arrows.subList(1, 3), hydraB), hydraB)
        // Duplex: the main arrow's tick-2 move again (its packets are the main's, bit for bit), 4 ticks later.
        assertEquals(4, arrows[3].delay)
        check("duplex", arrows[3], main)
        assertEquals(4, arrows[4].delay)
        check("duplex twin", arrows[4], main)
        // The spawn packet shows pos1 floored to 1/32.
        val spawn = Vec3(66.3125, 127.46875, 36.375)
        val p1 = arrows[0].at
        assertTrue(p1.x - spawn.x in 0.0..1.0 / 32 && p1.y - spawn.y in 0.0..1.0 / 32 && p1.z - spawn.z in 0.0..1.0 / 32, "pos1 $p1 vs spawn packet $spawn")
    }

    /** 21-53-28 n25133: Mosquito crouched (eye 1.27) at 9 stacks: no Hydra, Duplex. */
    @Test
    fun mosquitoCrouched() {
        val arrows = ShotPlan.plan(Bows.MOSQUITO, Vec3(63.253326872297144, 127.0, 35.739562757892536), 1723.5178f, 18.861046f, true, 9,
            Vec3(0.005727832140594802, -0.010956913408099345, 0.006990315358352345), 4)
        assertEquals(3, arrows.size)
        val main = Seen(Vec3(2.96703900384545, -1.1752426295550265, 0.7313678813404141), Vec3(69.02376379059714, 126.03055920265436, 37.18009483932455))
        check("main", arrows[0], main)
        check("duplex", arrows[1], main)
        check("duplex twin", arrows[2], main)
    }

    /** 13-42-11 n19211: Terminator, no stacks: main and the two side arrows (sent twice each by Hypixel). */
    @Test
    fun terminator() {
        val arrows = ShotPlan.plan(Bows.TERMINATOR, Vec3(-151.41424680750924, 69.0, -173.79105320317788), 186.7256f, -27.972887f, false, 0,
            Vec3(-0.00336518038153813, -0.007929600037575424, -0.001004669561217475), 4)
        assertEquals(5, arrows.size)
        check("main", arrows[0], Seen(Vec3(0.294268449002014, 1.2565464200695844, -2.5819446987731185), Vec3(-150.79928598152497, 73.22324796248513, -179.0313962522984)))
        val left = Seen(Vec3(0.5438564365500826, 1.363669657571873, -2.510529207104926), Vec3(-150.5471310159631, 72.83161786344108, -178.95927445127882))
        val right = Seen(Vec3(0.05493499359091758, 1.363669657571873, -2.568210950375389), Vec3(-151.04109889585487, 72.83161786344108, -179.01752611564206))
        check("side 1", nearest(arrows.subList(1, 5), left), left)
        check("side 2", nearest(arrows.subList(1, 5), right), right)
        assertEquals(2, arrows.drop(1).count { it.at.add(it.v).distanceTo(left.at) < 0.001 }, "each side arrow twice")
    }
    /** The cells one volley hits from the middle of the i4 plate, aimed so its main arrow lands on [spot]. */
    private fun volley(bow: String, stacks: Int, spot: Vec3): Set<Pair<Int, Int>> =
        com.engineerclient.practice.I4Geometry.aim(bow, Vec3(63.5, 127.0, 35.5), false, stacks, spot).second.map { it.x to it.y }.toSet()

    /** i4 from the plate: Terror's Hydra arrows make a middle-column aim cover its row; the Terminator covers two between cells. */
    @Test
    fun i4Coverage() {
        for (y in listOf(126, 128, 130)) {
            assertEquals(setOf(64 to y, 66 to y, 68 to y), volley(Bows.MOSQUITO, 10, Vec3(66.5, y + 0.5, 50.0)), "Mosquito at 10 stacks, row $y")
            assertEquals(setOf(66 to y), volley(Bows.MOSQUITO, 9, Vec3(66.5, y + 0.5, 50.0)), "Mosquito at 9 stacks, row $y")
            assertEquals(setOf(64 to y, 66 to y), volley(Bows.TERMINATOR, 0, Vec3(65.5, y + 0.5, 50.0)), "Terminator between cells, row $y")
        }
    }
}
