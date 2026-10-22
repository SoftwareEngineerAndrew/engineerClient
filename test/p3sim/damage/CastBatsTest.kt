package com.engineerclient.p3sim.clear.damage

import com.engineerclient.p3sim.run.PlayerRecord
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunConfig
import com.engineerclient.p3sim.run.RunStage
import com.engineerclient.p3sim.run.TestPlans
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** An Implosion that reaches a secret bat counts it as an enemy: the line and the ding fire even when the bat is all it killed. */
class CastBatsTest {
    private val caster = UUID(0, 21)
    private val original = DamageModule.batKiller

    @AfterEach fun restore() { DamageModule.batKiller = original }

    private fun run(): Run {
        val r = Run("r", RunConfig(seed = 1), TestPlans.plan(), TestPlans.emptyPacks(), systems = emptyList())
        r.state.players[caster] = PlayerRecord(caster, "Caster", "MAGE", true)
        r.begin(0)
        r.setT0(0)
        return r
    }

    private val box = ImplosionRules.castBox(0.0, 70.0, 0.0)

    @Test fun aCastThatKillsOnlyABatStillCountsOneEnemy() {
        val r = run()
        var seenBox: net.minecraft.world.phys.AABB? = null
        DamageModule.batKiller = { _, b, by -> seenBox = b; assertEquals(caster, by); 1 }
        val c = DamageModule.cast(r, caster, box, 10_000_000.0)
        assertEquals(1, c.count)
        assertEquals(10_000_000.0, c.total, 1e-6)
        assertEquals(1, c.killed)
        assertEquals(-6.0, seenBox!!.minX, 1e-9)
        assertEquals(77.0, seenBox!!.maxY, 1e-9)
    }

    @Test fun severalBatsEachCountAndAddTheCastsRoll() {
        val r = run()
        DamageModule.batKiller = { _, _, _ -> 3 }
        val c = DamageModule.cast(r, caster, box, 5_000_000.0)
        assertEquals(3, c.count)
        assertEquals(15_000_000.0, c.total, 1e-6)
    }

    @Test fun aCastWithNothingInTheBoxStaysSilent() {
        val r = run()
        DamageModule.batKiller = { _, _, _ -> 0 }
        val c = DamageModule.cast(r, caster, box, null)
        assertEquals(0, c.count)
        assertEquals(0.0, c.total)
        assertEquals(0, c.killed)
    }

    @Test fun theLineSaysOneEnemyForTheBatAlone() {
        assertEquals("§7Your Implosion hit §r§c1 §r§7enemy for §r§c12,345,678.9 §r§7damage.", ImplosionRules.line(1, 12_345_678.9))
    }

    @Test fun theBossStageIsLeftAlone() {
        val r = run()
        var asked = false
        DamageModule.batKiller = { _, _, _ -> asked = true; 1 }
        r.forceStage(RunStage.BOSS)
        assertEquals(0, DamageModule.cast(r, caster, box, 1.0).count)
        assertEquals(false, asked)
    }
}
