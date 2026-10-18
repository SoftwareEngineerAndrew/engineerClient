package com.engineerclient.p3sim.state

import com.engineerclient.p3sim.clear.state.BodyBlocks
import com.engineerclient.p3sim.clear.state.DoorsModule
import com.engineerclient.p3sim.run.*
import com.engineerclient.p3sim.run.api.DoorOpenResult
import com.engineerclient.p3sim.run.api.Services
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/** The module object as the run drives it: lifecycle hooks, the service answers, savepoint entries. */
class DoorsModuleTest {
    private val fx = RecordingEffects()

    @AfterEach fun reset() { DoorsModule.effects = { com.engineerclient.p3sim.clear.state.McStateEffects } }

    private fun run(): Run {
        DoorsModule.effects = { fx }
        val r = Run("r", RunConfig(seed = 4), StatePlans.plan(), TestPlans.emptyPacks(), systems = listOf(DoorsModule))
        fx.now = { r.t }
        return r
    }

    @Test fun theRunDrivesTheModule() {
        val r = run()
        r.begin(0)
        assertNotNull(DoorsModule.controller)
        assertNotNull(r.t0Tick, "the module planned the countdown and set T0")
        StatePlans.addPlayer(r, "Ann")
        var tick = 0
        while (r.t < 30) { tick++; r.tick(tick) }
        assertEquals(RunStage.CLEAR, r.stage)
        assertEquals(DoorPhase.OPEN, r.state.doors[0].phase)
        assertEquals(BodyBlocks.BARRIER, fx.bodies.first { it.second == 0 }.third)
        assertEquals(CheckMark.GREEN, r.state.rooms[3].check)
        r.end(EndReason.STOPPED)
        assertNull(DoorsModule.controller, "the controller goes with the run")
    }

    @Test fun theServiceAnswersMatchTheRun() {
        val r = run(); r.begin(0)
        val ann = StatePlans.addPlayer(r, "Ann")
        assertEquals(KeyKind.WITHER, Services.doors.keyForRoom(r, 1))
        assertNull(Services.doors.keyForRoom(r, 3))
        assertEquals(KeyKind.BLOOD, Services.doors.keyForRoom(r, 5))
        val kp = Services.doors.keyPlan(r.plan)
        assertEquals(listOf(1, 2, 4), kp.witherRooms)
        val d = r.plan.doors[1]
        val (cx, cz) = d.spot
        assertEquals(1, Services.doors.doorAtBlock(r, cx, 70, cz))
        assertNull(Services.doors.doorAtBlock(r, 0, 70, 0))
        assertEquals(DoorOpenResult.NO_KEY, Services.doors.tryOpen(r, 1, ann.uuid))
        Services.doors.dropKey(r, 1, -150.0, -185.0)
        assertEquals(1, DoorsModule.controller!!.standList().size)
        assertNull(Services.doors.keyForRoom(r, 1))
        r.end(EndReason.STOPPED)
        assertEquals(DoorOpenResult.NOT_A_DOOR, Services.doors.tryOpen(r, 1, ann.uuid), "with no run on, a door is not a door")
        assertNull(Services.doors.keyForRoom(r, 1))
    }

    @Test fun savepointEntryRoundTrips() {
        val r = run(); r.begin(0)
        var tick = 0
        while (r.t < 20) { tick++; r.tick(tick) }
        val snap = assertNotNull(DoorsModule.snapshot(r))
        DoorsModule.restore(r, snap)
        assertEquals(snap.toString(), DoorsModule.snapshot(r).toString())
    }

    @Test fun anotherModulesEventsReachTheStateLogic() {
        val r = run(); r.begin(0)
        var tick = 0
        while (r.t < 5) { tick++; r.tick(tick) }
        DoorsModule.completeRoom(r, 2)
        assertEquals(CheckMark.GREEN, r.state.rooms[2].check, "Bravo has no secrets")
        r.bus.post(PuzzleFailed(r.t, 0, 4, null, "x"))
        assertEquals(CheckMark.RED, r.state.rooms[4].check)
    }
}
