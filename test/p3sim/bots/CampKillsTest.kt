package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.CampKills
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** A camper's kill times follow the recorded spread. */
class CampKillsTest {
    @Test fun livesFollowTheRecordedSpread() {
        assertEquals(21, CampKills.life(0.1))
        assertEquals(24, CampKills.life(0.5))
        assertEquals(118, CampKills.life(0.9))
        val lives = (0 until 1000).map { CampKills.life(it / 1000.0) }.sorted()
        assertEquals(24, lives[500])
    }
}
