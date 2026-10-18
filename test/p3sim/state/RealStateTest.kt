package com.engineerclient.p3sim.state

import com.engineerclient.p3sim.clear.secrets.SecretPlan
import com.engineerclient.p3sim.clear.state.*
import com.engineerclient.p3sim.pack.PackReport
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.pack.layouts.LayoutPack
import com.engineerclient.p3sim.pack.PackFiles
import com.engineerclient.p3sim.pack.runstate.RunStatePack
import com.engineerclient.p3sim.run.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Optional check of the state logic against the REAL layouts and run-state tables (kept outside this repo). Runs only when
 * ADS_REAL_PACKS names the folder with the pack files; otherwise skipped. Prints counts only.
 */
class RealStateTest {
    private val dir: Path? = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    @Test fun theKeyPlanMatchesTheRecordedKeysOfEveryPlayableLayout() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val report = PackReport()
        val layouts = assertNotNull(LayoutPack.load(dir!!, report = report))
        var checked = 0; var keysSeen = 0; var keysOffPlan = 0; var bloodOk = 0; var bloodSeen = 0
        for (l in layouts.layouts.filter { it.playable == true }) {
            val plan = (DungeonPlan.build(l, 1, null) as? DungeonPlan.Result.Ok)?.plan ?: continue
            val kp = KeyPlans.of(plan)
            checked++
            assertEquals(plan.doors.count { it.kind == DoorKind.WITHER }, kp.witherRooms.size, "${l.id}: one key per paid wither door")
            assertEquals(1, plan.doors.count { it.kind == DoorKind.FAIRY }, "${l.id}: one free door")
            for (k in plan.recordedKeys) {
                val room = k.room ?: continue
                if (k.kind == "wither") { keysSeen++; if (room !in kp.witherRooms) keysOffPlan++ }
                if (k.kind == "blood") { bloodSeen++; if (room == kp.bloodRoom) bloodOk++ }
            }
        }
        println("key plan: $checked playable layouts, wither stands $keysSeen (off plan $keysOffPlan), blood stands $bloodSeen (on plan $bloodOk)")
        assertTrue(checked > 100)
        // 4 of 1,147 recorded wither stands were off the path in the measurements; the plan must explain nearly all of them.
        assertTrue(keysOffPlan * 100 <= keysSeen, "off plan: $keysOffPlan of $keysSeen")
        assertTrue(bloodOk * 100 >= bloodSeen * 99, "blood: $bloodOk of $bloodSeen")
    }

    @Test fun everyTrapRoomHasItsEndChestInThePackAndAtASecretSite() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts); val rooms = assertNotNull(set.rooms)
            val rules = StateRules.fromPack(set.runstate)
            var traps = 0
            for (l in layouts.pool()) {
                val plan = (DungeonPlan.build(l, 3L, rooms, set.runstate) as? DungeonPlan.Result.Ok)?.plan ?: continue
                val secrets = SecretPlan.build(plan, kotlin.random.Random(1))
                for (r in plan.rooms.filter { it.type == RoomType.TRAP }) {
                    val cell = assertNotNull(rules.chestClear(r.name), "${l.id}: the pack has no end chest for ${r.name}").cell!!
                    val w = r.placement!!.toWorld(cell.x, cell.y, cell.z)
                    val site = assertNotNull(secrets.siteAt(r.index, w.x, w.y, w.z), "${l.id}: ${r.name}'s end chest is not one of the secrets module's sites")
                    assertEquals(SecretKind.CHEST, site.kind)
                    assertEquals(rules.chestClear(r.name)!!.secret, site.id)
                    traps++
                }
            }
            println("trap rooms with an end chest rule: $traps")
            assertTrue(traps > 20)
        } finally { set.close() }
    }

    @Test fun aWholeCountdownRunsOnEveryPlayableLayout() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val layouts = assertNotNull(LayoutPack.load(dir!!, report = PackReport()))
        val pack = RunStatePack.load(dir, PackReport())
        val rules = StateRules.fromPack(pack)
        var n = 0
        for (l in layouts.layouts.filter { it.playable == true }.take(60)) {
            val plan = (DungeonPlan.build(l, 1, null, pack) as? DungeonPlan.Result.Ok)?.plan ?: continue
            val run = Run("r", RunConfig(seed = 3), plan, TestPlans.emptyPacks(), systems = emptyList())
            val fx = RecordingEffects(); fx.now = { run.t }
            val c = StateController(run, rules, StateConfig(), fx)
            run.begin(0); c.start()
            run.bus.subscribe { c.onEvent(it) }
            var tick = 0
            while (run.t < 300) { tick++; run.tick(tick); c.tick() }
            assertEquals(RunStage.CLEAR, run.stage, l.id)
            val entranceDoor = plan.doors.single { it.kind == DoorKind.ENTRANCE }
            assertEquals(DoorPhase.OPEN, run.state.doors[entranceDoor.index].phase, l.id)
            assertEquals(DoorPhase.OPEN, run.state.doors[plan.doors.single { it.kind == DoorKind.FAIRY }.index].phase, l.id)
            assertEquals(CheckMark.GREEN, run.state.rooms[plan.fairy!!.index].check, l.id)
            assertTrue(run.state.counters.opened >= 2, l.id)
            n++
        }
        println("countdown: $n layouts")
        assertTrue(n >= 20)
    }
}
