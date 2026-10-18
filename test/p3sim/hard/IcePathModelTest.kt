package com.engineerclient.p3sim.clear.puzzles.hard

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The ice slide on a made-up arena: a 9 x 9 sheet, one column of exit corridor, a few pillars. */
class IcePathModelTest {
    // Arena x 0..8, z 0..8; corridor x 4, z 9..11; the pillars are at (0,4), (5,3), (2,6) and (7,7).
    private val pillars = setOf(0 to 4, 5 to 3, 2 to 6, 7 to 7)
    private fun solid(x: Int, z: Int): Boolean {
        if (z in 9..11) return x != 4
        if (x !in 0..8 || z !in 0..11) return true
        return (x to z) in pillars
    }

    private fun model(traps: Set<Long> = emptySet()) = IcePathModel(::solid, 0, 0, 4..4, 8, traps)

    private fun run(m: IcePathModel, maxTicks: Int = 200): Int { var n = 0; while (m.phase == IcePathModel.Phase.MOVING && n < maxTicks) { m.tick(); n++ }; return n }

    @Test fun aHitSendsTheFishAwayAlongTheNearerAxisAtFortyHundredthsAtick() {
        val m = model()
        assertEquals(IcePathModel.Hit.STARTED, m.hit(-1.0, 0.7))
        m.tick()
        assertEquals(0.5 + 0.4, m.x, 1e-9)
        assertEquals(0.5, m.z, 1e-9)
        run(m)
        assertEquals(IcePathModel.Phase.IDLE, m.phase)
        assertEquals(8, m.cellX, "row 0 is clear, so it runs to the wall")
    }

    @Test fun itStopsInTheMiddleOfTheCellBeforeAnObstacle() {
        // Down column 5 from z 0 meets the pillar at (5,3): it stops on (5,2).
        val m = IcePathModel(::solid, 5, 0, 4..4, 8)
        assertEquals(IcePathModel.Hit.STARTED, m.hit(5.5, -1.0))
        run(m)
        assertEquals(5 to 2, m.cellX to m.cellZ)
        assertEquals(5.5, m.x, 1e-9)
        assertEquals(2.5, m.z, 1e-9)
    }

    @Test fun aHitIntoAWallDoesNothingAndAHitWhileMovingIsRefused() {
        val m = model()
        assertEquals(IcePathModel.Hit.BLOCKED, m.hit(5.0, 0.5), "from the east pushes it west, into the edge")
        assertEquals(IcePathModel.Phase.IDLE, m.phase)
        assertEquals(IcePathModel.Hit.STARTED, m.hit(-1.0, 0.5))
        m.tick()
        assertEquals(IcePathModel.Hit.WHILE_MOVING, m.hit(-1.0, 0.5))
    }

    @Test fun travelTimeIsCellsOverSpeed() {
        val m = IcePathModel(::solid, 0, 0, 4..4, 8)
        m.hit(-1.0, 0.5)
        val ticks = run(m)
        // Column z = 0 has no pillar: 8 cells east at 0.4 blocks a tick.
        assertEquals(Math.ceil(8 / 0.4).toInt(), ticks)
        assertEquals(8, m.cellX)
    }

    @Test fun reachingTheCorridorGoingSouthSolvesIt() {
        val m = IcePathModel(::solid, 4, 0, 4..4, 8)
        assertEquals(IcePathModel.Hit.STARTED, m.hit(4.5, -1.0))
        run(m)
        assertEquals(IcePathModel.Phase.SOLVED, m.phase)
    }

    @Test fun sailingAcrossTheCorridorMouthSidewaysDoesNotCount() {
        val m = IcePathModel(::solid, 0, 8, 4..4, 8)
        assertEquals(IcePathModel.Hit.STARTED, m.hit(-1.0, 8.5))
        run(m)
        assertEquals(IcePathModel.Phase.IDLE, m.phase)
        assertEquals(8, m.cellX)
    }

    @Test fun enteringATrapFailsIt() {
        val m = model(setOf(IcePathModel.key(2, 0)))
        m.hit(-1.0, 0.5)
        run(m)
        assertEquals(IcePathModel.Phase.TRAPPED, m.phase)
        m.reset()
        assertEquals(IcePathModel.Phase.IDLE, m.phase)
        assertEquals(0, m.cellX)
    }

    @Test fun theSearchFindsAShortWayAndPlayingItSolvesIt() {
        val m = model()
        val path = assertNotNull(m.solution())
        assertTrue(path.size in 1..10, "path $path")
        // Play it: each stop is reached by hitting from the opposite side.
        var at = 0 to 0
        for (stop in path) {
            val dx = Integer.signum(stop.first - at.first); val dz = Integer.signum(stop.second - at.second)
            assertEquals(IcePathModel.Hit.STARTED, m.hit(m.x - dx * 1.0, m.z - dz * 1.0))
            run(m)
            at = stop
        }
        assertEquals(IcePathModel.Phase.SOLVED, m.phase)
    }

    @Test fun aTrapOnTheOnlyWayMakesTheSearchRouteAroundOrGiveUp() {
        val trapAll = (0..8).map { IcePathModel.key(it, 8) }.toSet()
        assertEquals(null, model(trapAll).solution())
    }
}
