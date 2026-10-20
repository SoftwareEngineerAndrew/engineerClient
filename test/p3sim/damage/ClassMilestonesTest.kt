package com.engineerclient.p3sim.clear.damage

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClassMilestonesTest {
    @Test fun tablesFollowTheClass() {
        assertEquals(750_000, ClassMilestones.thresholds("Mage", false)!![0])
        assertEquals(1_450_000, ClassMilestones.thresholds("mage", true)!![0])
        assertEquals(450_000, ClassMilestones.thresholds("Tank", false)!![0])
        assertEquals(750_000, ClassMilestones.thresholds("Archer", true)!![0])
        assertNull(ClassMilestones.thresholds("Healer", false))
        assertNotNull(ClassMilestones.thresholds("Berserk", false))
    }

    @Test fun severalCanFireAtOnce() {
        val t = ClassMilestones.thresholds("Mage", false)!!
        assertEquals(0, ClassMilestones.reached(t, 749_999.0))
        assertEquals(3, ClassMilestones.reached(t, 30_000_000.0))
    }

    @Test fun theClockIsTwoDigitsThenMinutes() {
        assertEquals("02s", ClassMilestones.clock(2))
        assertEquals("02m 05s", ClassMilestones.clock(125))
    }

    @Test fun blessingsShareTheRunClock() {
        // Hypixel prints `(03s)` on a buff line, as on a milestone, and its clock starts a couple of ticks before tick 0.
        assertEquals("00s", com.engineerclient.p3sim.clear.secrets.Blessings.clock(0))
        assertEquals("03s", com.engineerclient.p3sim.clear.secrets.Blessings.clock(58))
        assertEquals("02s", com.engineerclient.p3sim.clear.secrets.Blessings.clock(57))
        assertEquals("01m 07s", com.engineerclient.p3sim.clear.secrets.Blessings.clock(1338))
    }

    @Test fun linesMatchHypixelsFormat() {
        val mage = ClassMilestones.thresholds("Mage", false)!!
        assertEquals("§e§lMage Milestone §r§e❶§r§7: You have dealt §r§c750,000§r§7 Total Damage so far! §r§a02s", ClassMilestones.line("Mage", 0, mage, 2))
        val tank = ClassMilestones.thresholds("Tank", false)!!
        assertEquals("§e§lTank Milestone §r§e❷§r§7: You have tanked and dealt §r§c2,250,000§r§7 Total Damage so far! §r§a01m 00s", ClassMilestones.line("Tank", 1, tank, 60))
        val archer = ClassMilestones.thresholds("Archer", false)!!
        assertEquals("§e§lArcher Milestone §r§e❶§r§7: You have dealt §r§c750,000§r§7 Ranged Damage so far! §r§a09s", ClassMilestones.line("Archer", 0, archer, 9))
    }
}
