package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.BotHealth
import com.engineerclient.p3sim.clear.bots.HitClock
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotHealthTest {
    @Test fun aFightingTeammateIsHitAboutTwoPointTwoTimesAMinute() {
        val c = HitClock(Random(4))
        val hits = (0 until 1200 * 200).count { c.roll() != null } / 200.0
        assertTrue(hits in 1.9..2.5, "hits a minute: $hits")
    }

    @Test fun hitsAreMidSizedAndNeverMoreThanAQuarter() {
        val r = Random(2)
        val sizes = (0 until 5000).map { BotHealth.hitPercent(r.nextDouble()) }.sorted()
        assertTrue(sizes.first() >= 4.0 && sizes.last() <= 24.0)
        assertEquals(9.0, sizes[2500], 1.0)
    }

    @Test fun maxHealthFollowsTheClassTableAtTheTenthFiftiethAndNinetiethPercent() {
        assertEquals(26_700.0, BotHealth.maxHp("ARCHER", 0.5).toDouble(), 5.0)
        assertEquals(21_000.0, BotHealth.maxHp("ARCHER", 0.1).toDouble(), 5.0)
        assertEquals(48_300.0, BotHealth.maxHp("MAGE", 0.9).toDouble(), 5.0)
        assertEquals(24_200.0, BotHealth.maxHp("healer", 0.5).toDouble(), 5.0)
        // Every class stays in a believable range at the ends of the draw.
        for (c in listOf("ARCHER", "MAGE", "HEALER", "TANK", "BERSERK")) for (u in listOf(0.0, 0.3, 0.7, 1.0)) assertTrue(BotHealth.maxHp(c, u) in 14_000..54_000)
        // An unknown class does not crash.
        assertTrue(BotHealth.maxHp("PALADIN", 0.5) > 0)
    }
}
