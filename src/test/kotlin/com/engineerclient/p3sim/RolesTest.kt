package com.engineerclient.p3sim

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RolesTest {
    private val stations = listOf(
        "S1 T1", "S1 T2", "S1 T3", "S1 T4", "S1 east lever", "S1 west lever", "S1 SS",
        "S2 T1", "S2 T2", "S2 T3", "S2 T4", "S2 T5", "S2 low lever", "S2 high lever", "S2 Lights",
        "S3 T1", "S3 T2", "S3 T3", "S3 T4", "S3 west lever", "S3 east lever", "S3 Arrows",
        "S4 T1", "S4 T2", "S4 T3", "S4 T4", "S4 low lever", "S4 high lever", "S4 Target",
    )
    private val all = stations + listOf("gate 1", "gate 2", "gate 3")

    @Test
    fun `every preset - each job has a role and a time`() {
        for (p in Roles.PRESETS) {
            val plan = Roles.plan(p)
            for (j in all) {
                assertTrue(plan.owners[j].orEmpty().isNotEmpty(), "${p.name}: nobody does $j")
                assertTrue(j in plan.times, "${p.name}: no time for $j")
            }
            assertEquals(all.toSet(), plan.owners.keys, "${p.name}: unknown jobs")
            assertEquals(5, plan.jobsOf.size)
        }
    }

    @Test
    fun `pf - the roles as written`() {
        val plan = Roles.plan(Roles.PF)
        assertEquals(listOf("S1 SS", "S2 high lever", "S3 T2", "S3 Arrows", "S4 T4", "S4 high lever", "S4 low lever"),
            plan.jobsOf.getValue(DungeonClass.HEALER).filter { !it.startsWith("gate") })
        assertEquals(listOf("S1 T4", "S1 T3", "S2 T2"), plan.jobsOf.getValue(DungeonClass.MAGE))
        // Stacks.
        assertEquals(listOf(DungeonClass.BERSERK, DungeonClass.TANK), plan.owners["S2 T3"])
        assertEquals(listOf(DungeonClass.HEALER, DungeonClass.BERSERK), plan.owners["S3 Arrows"])
        // Early enters: ee2 archer, ee3 healer, core mage.
        assertEquals(DungeonClass.ARCHER, plan.ee[2])
        assertEquals(DungeonClass.HEALER, plan.ee[3])
        assertEquals(DungeonClass.MAGE, plan.ee[5])
        // Pre-devs in S1's time line.
        assertEquals(1 to 7.3, plan.times["S4 Target"])
        assertEquals(1 to 10.0, plan.times["S2 Lights"])
        assertEquals(2 to 6.5, plan.times["S2 low lever"])
        // The gates: whoever has the section's ll/bl.
        assertEquals(listOf(DungeonClass.ARCHER), plan.owners["gate 1"])
        assertEquals(listOf(DungeonClass.HEALER), plan.owners["gate 2"])
        assertTrue(plan.moves.any { it.kind == "hold" && it.who == "2" })
        assertTrue(plan.moves.any { it.kind == "preleap" && it.who == "ee2" && it.at == 12.1 })
    }

    @Test
    fun `quality pf - ee2 by the mage, ee3 by the tank`() {
        val plan = Roles.plan(Roles.QUALITY_PF)
        assertEquals(DungeonClass.MAGE, plan.ee[2])
        assertEquals(DungeonClass.TANK, plan.ee[3])
        assertEquals(listOf("S2 T1"), plan.jobsOf.getValue(DungeonClass.HEALER).filter { it.startsWith("S2") })
        assertEquals(listOf(DungeonClass.MAGE, DungeonClass.BERSERK), plan.owners["S2 T3"])
    }
}
