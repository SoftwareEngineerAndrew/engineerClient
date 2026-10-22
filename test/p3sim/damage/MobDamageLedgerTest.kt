package com.engineerclient.p3sim.clear.damage

import com.engineerclient.p3sim.run.DamageSource
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MobDamageLedgerTest {
    @Test fun itAddsUpHitsBySource() {
        val l = MobDamageLedger()
        val mob = UUID(0, 5); val player = UUID(0, 6)
        l.record(mob, 100.0, DamageSource.BOW, player)
        l.record(mob, 50.0, DamageSource.BOW, null)
        l.record(mob, 25.0, DamageSource.IMPLOSION, null)
        val e = l.of(mob)!!
        assertEquals(3, e.hits)
        assertEquals(175.0, e.total)
        assertEquals(150.0, e.bySource[DamageSource.BOW])
        assertEquals(player, e.lastBy, "an anonymous hit does not wipe who was hitting")
    }

    @Test fun aKilledMobIsForgotten() {
        val l = MobDamageLedger()
        l.record(UUID(0, 1), 1.0, DamageSource.MELEE, null)
        l.forget(UUID(0, 1))
        assertNull(l.of(UUID(0, 1)))
        assertEquals(0, l.size)
    }
}
