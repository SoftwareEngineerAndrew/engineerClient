package com.engineerclient.p3sim.clear.damage

import com.engineerclient.p3sim.run.PlayerRecord
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The percent-of-max-HP ledger: hits, the 10-tick window, "No deaths", death, ghost and revive. */
class PlayerLedgerTest {
    private fun rec() = PlayerRecord(UUID(0, 1), "Tester", "MAGE", false)

    @Test fun aHitLowersThePercentAndOpensTheWindow() {
        val r = rec()
        val h = PlayerLedger.hit(r, 15.0, 100, noDeaths = false)
        assertEquals(PlayerLedger.Outcome.HURT, h.outcome)
        assertEquals(15.0, h.applied)
        assertEquals(85.0, r.hpPercent, 1e-9)
        assertEquals(100, r.lastHitT)
    }

    @Test fun aSecondHitInsideTenTicksIsIgnoredAndOneAtTenLands() {
        val r = rec()
        PlayerLedger.hit(r, 5.0, 100, false)
        assertEquals(PlayerLedger.Outcome.IGNORED, PlayerLedger.hit(r, 5.0, 109, false).outcome)
        assertEquals(95.0, r.hpPercent, 1e-9)
        assertEquals(PlayerLedger.Outcome.HURT, PlayerLedger.hit(r, 5.0, 110, false).outcome)
        assertEquals(90.0, r.hpPercent, 1e-9)
    }

    @Test fun twoTrapsAreExactlyThirtyPercentWhenTenTicksApart() {
        val r = rec()
        PlayerLedger.hit(r, 15.0, 0, false)
        PlayerLedger.hit(r, 15.0, 10, false)
        assertEquals(70.0, r.hpPercent, 1e-9)
    }

    @Test fun theVanillaBarIsFortyTimesThePercent() {
        assertEquals(40.0, PlayerLedger.vanilla(100.0))
        assertEquals(20.0, PlayerLedger.vanilla(50.0), 1e-9)
        assertEquals(34.0, PlayerLedger.vanilla(85.0), 1e-9)
        assertEquals(0.5, PlayerLedger.vanilla(0.0), "a living player never shows an empty bar")
    }

    @Test fun aHitThatWouldKillIsLethalAndLeavesTheLedgerForTheCallerToDecide() {
        val r = rec()
        r.hpPercent = 10.0
        val h = PlayerLedger.hit(r, 15.0, 50, false)
        assertEquals(PlayerLedger.Outcome.LETHAL, h.outcome)
        assertEquals(10.0, r.hpPercent, "masks get their say before anything is taken")
        PlayerLedger.saved(r)
        assertEquals(100.0, r.hpPercent)
    }

    @Test fun noDeathsHoldsTheLedgerAtOneVanillaPoint() {
        val r = rec()
        r.hpPercent = 10.0
        val h = PlayerLedger.hit(r, 50.0, 50, noDeaths = true)
        assertEquals(PlayerLedger.Outcome.HELD, h.outcome)
        assertEquals(2.5, r.hpPercent, 1e-9)
        assertEquals(7.5, h.applied, 1e-9)
        assertEquals(1.0, PlayerLedger.vanilla(r.hpPercent), 1e-9)
        assertFalse(r.ghost)
        // already at the floor: the next hit takes nothing and still does not kill
        val again = PlayerLedger.hit(r, 50.0, 70, noDeaths = true)
        assertEquals(PlayerLedger.Outcome.HELD, again.outcome)
        assertEquals(0.0, again.applied, 1e-9)
        assertEquals(2.5, r.hpPercent, 1e-9)
    }

    @Test fun dyingMakesAGhostThatIsRevivedAfterSixSeconds() {
        val r = rec()
        PlayerLedger.die(r, 1000)
        assertTrue(r.ghost)
        assertEquals(1, r.deaths)
        assertEquals(0.0, r.hpPercent)
        assertEquals(1120, r.reviveAtT)
        assertFalse(PlayerLedger.reviveDue(r, 1119))
        assertTrue(PlayerLedger.reviveDue(r, 1120))
        PlayerLedger.revive(r)
        assertFalse(r.ghost)
        assertEquals(100.0, r.hpPercent)
        assertEquals(null, r.reviveAtT)
        assertFalse(PlayerLedger.reviveDue(r, 5000), "a living player is never due")
    }

    @Test fun aGhostTakesNoDamage() {
        val r = rec()
        PlayerLedger.die(r, 0)
        assertEquals(PlayerLedger.Outcome.IGNORED, PlayerLedger.hit(r, 15.0, 500, false).outcome)
        assertEquals(0.0, r.hpPercent)
    }

    @Test fun zeroAndNegativeHitsDoNothing() {
        val r = rec()
        assertEquals(PlayerLedger.Outcome.IGNORED, PlayerLedger.hit(r, 0.0, 10, false).outcome)
        assertEquals(PlayerLedger.Outcome.IGNORED, PlayerLedger.hit(r, -3.0, 10, false).outcome)
        assertEquals(PlayerLedger.Outcome.IGNORED, PlayerLedger.hit(r, Double.NaN, 10, false).outcome)
        assertEquals(Int.MIN_VALUE, r.lastHitT)
    }

    @Test fun aHitAtTheVeryStartOfARunIsNotInsideAWindowThatNeverStarted() {
        val r = rec()
        assertFalse(PlayerLedger.immune(r, 0))
        assertEquals(PlayerLedger.Outcome.HURT, PlayerLedger.hit(r, 2.0, -200, false).outcome)
        assertTrue(PlayerLedger.immune(r, -195))
    }
}
