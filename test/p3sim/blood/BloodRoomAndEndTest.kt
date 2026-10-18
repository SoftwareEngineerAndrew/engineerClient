package com.engineerclient.p3sim.blood

import com.engineerclient.p3sim.clear.blood.BloodRoom
import com.engineerclient.p3sim.clear.blood.BossEndNumbers
import com.engineerclient.p3sim.clear.blood.BossEntry
import com.engineerclient.p3sim.clear.blood.WatcherLines
import com.engineerclient.p3sim.clear.blood.WatcherStats
import com.engineerclient.p3sim.run.TestPlans
import com.engineerclient.p3sim.run.api.EndScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BloodRoomAndEndTest {
    @Test fun theRoomFallsBackToTheTileMiddleWithoutBlocks() {
        val plan = TestPlans.plan()
        val r = assertNotNull(BloodRoom.of(plan, null))
        assertEquals(3, r.roomIndex)
        // The blood room is tile (4, 3); the blood door joins it to tile (4, 2).
        assertEquals(-57 + 0.5, r.watcherX, 1e-9)
        assertEquals(-89 + 0.5, r.watcherZ, 1e-9)
        assertEquals(BloodRoom.DEFAULT_STAND_Y, r.watcherY)
        assertEquals(-57 + 0.5, r.doorX, 1e-9)
        assertEquals(-200 + 31 + 64 + 0.5, r.doorZ, 1e-9)
        assertEquals(BloodRoom.DOOR_CENTRE_Y, r.doorY)
        assertTrue(r.heads.isEmpty())
    }

    @Test fun theLightningStrikesTheCentreOfTheBlockThePlayerStoodIn() {
        val v = BossEntry.blockCentre(-184.31, 69.0, -106.9)
        assertEquals(-184.5, v.x, 1e-9); assertEquals(69.5, v.y, 1e-9); assertEquals(-106.5, v.z, 1e-9)
        val n = BossEntry.blockCentre(-0.2, 70.99, 0.0)
        assertEquals(-0.5, n.x, 1e-9); assertEquals(70.5, n.y, 1e-9); assertEquals(0.5, n.z, 1e-9)
    }

    @Test fun theArrivalIsTheP1SpotHalfABlockUp() {
        assertEquals(73.5, BossEntry.ARRIVAL.x, 1e-9)
        assertEquals(221.5, BossEntry.ARRIVAL.y, 1e-9)
        assertEquals(14.5, BossEntry.ARRIVAL.z, 1e-9)
    }

    @Test fun theWatchersPassLineIsABossLine() {
        assertEquals("§c[BOSS] The Watcher§r§f: You have proven yourself. You may pass.", WatcherLines.chat(WatcherLines.PASS))
    }

    @Test fun theEndLinesUseTheRealScoreAndTime() {
        val n = BossEndNumbers.from(EndScreen(score = 287, letter = "S", clearedPct = 100, secretsFound = 44, secretsTotal = 60, crypts = 5, deaths = 2, ticks = 4_000))
        assertEquals("Team Score: §r§a287 §r§f(§r§bS§r§f)", n.scoreLine())
        assertEquals("§r§c☠ §r§eDefeated §r§cMaxor, Storm, Goldor, and Necron §r§ein §r§a03m 20s", n.defeatedLine())
        assertEquals(2, n.deaths); assertEquals(44, n.secretsFound)
    }

    @Test fun splusIsTheRecordedBoldAqua() {
        val n = BossEndNumbers.from(EndScreen(305, "S+", 100, 11, 60, 4, 0, 6_000))
        assertEquals("Team Score: §r§a305 §r§f(§r§b§lS+§r§f)", n.scoreLine())
    }

    @Test fun noTimeMeansNoDefeatedLine() {
        assertNull(BossEndNumbers(0, "D", 0, 0, 0, 0, 0).defeatedLine())
    }

    @Test fun timeTextPadsMinutesAndSeconds() {
        assertEquals("05m 12s", BossEndNumbers.timeText(5 * 1200 + 12 * 20))
        assertEquals("00m 00s", BossEndNumbers.timeText(0))
        assertEquals("12m 59s", BossEndNumbers.timeText(12 * 1200 + 59 * 20 + 19))
    }

    @Test fun noRunMeansNoNumbers() {
        assertNull(BossEndNumbers.of(null))
        assertNull(BossEndNumbers.ofCurrentRun())
    }

    @Test fun theJumpSoundsStayInTheirRecordedRanges() {
        val r = kotlin.random.Random(7)
        repeat(500) {
            assertTrue(BossEntry.thunderPitch(r) in 0.79f..0.98f)
            assertTrue(BossEntry.explodePitch(r) in 0.5f..0.7f)
        }
        assertEquals(2f, BossEntry.EXPLODE_VOLUME)
    }

    @Test fun theWatcherHangsFourBlocksOverTheFloorFacingTheDoor() {
        val r = assertNotNull(BloodRoom.of(TestPlans.plan(), null))
        assertEquals(r.watcherY + WatcherStats.RISE, r.watcherHomeY, 1e-9)
        assertEquals(73.0, r.watcherHomeY, 1e-9)
        assertEquals(WatcherStats.yawTowardDoor(r.quarterTurns), r.watcherYaw)
        assertNull(r.exit, "no blocks, no alcove")
    }

    @Test fun theWatcherFacesTheBloodDoorInEveryRotation() {
        // Recorded: a SOUTH room 180, WEST 270, NORTH 0, EAST 90.
        assertEquals(180f, WatcherStats.yawTowardDoor(0))
        assertEquals(270f, WatcherStats.yawTowardDoor(1))
        assertEquals(0f, WatcherStats.yawTowardDoor(2))
        assertEquals(90f, WatcherStats.yawTowardDoor(3))
        assertEquals(180f, WatcherStats.yawTowardDoor(4))
    }

    @Test fun theWatchersHealthAndBarFollowTheCountedKills() {
        assertEquals(3000f, WatcherStats.health(0))
        assertEquals(2800f, WatcherStats.health(1))
        assertEquals(200f, WatcherStats.health(14))
        assertEquals(0.1f, WatcherStats.health(15))
        assertEquals(0.1f, WatcherStats.health(40), "more kills than the camp has change nothing")
        assertEquals(3000f, WatcherStats.health(-3))
        assertEquals(1.0f, WatcherStats.barProgress(0))
        assertEquals(0.93f, WatcherStats.barProgress(1))
        assertEquals(0.07f, WatcherStats.barProgress(14))
        assertEquals(0.0f, WatcherStats.barProgress(15))
    }

    @Test fun watchfulEyesHoverACoupleOfBlocksFromHimOnTheRecordedSides() {
        val r = kotlin.random.Random(5)
        repeat(300) {
            val o = WatcherStats.eyeOffset(r)
            assertEquals(2.5, o[1], 1e-9)
            for (v in listOf(o[0], o[2])) assertTrue(v in -1.5..-0.5 || v in 1.5..2.5, "offset $v")
        }
    }
}
