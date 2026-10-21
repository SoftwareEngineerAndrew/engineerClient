package com.engineerclient.p3sim.clear.damage

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The numbers and lists behind the pearl, the Spirit Leap floor item and the secret items whose ability is missing. */
class ClearItemsTest {
    @Test fun thePearlThrowSoundPitchStaysInTheRecordedBand() {
        // measured 0.333 to 0.492 for a random draw in [0,1): 0.4 / (r * 0.4 + 0.8)
        assertEquals(0.5f, ClearItems.pearlPitch(0f), 1e-6f)
        assertEquals(1f / 3f, ClearItems.pearlPitch(1f), 1e-6f)
        for (i in 0..100) {
            val p = ClearItems.pearlPitch(i / 100f)
            assertTrue(p in 0.3333f..0.5001f, "pitch $p out of the recorded band")
        }
    }

    @Test fun thePearlThrowIsQuietAndFast() {
        assertEquals(0.5f, ClearItems.PEARL_VOLUME)
        assertEquals(1.5f, ClearItems.PEARL_SPEED)
        assertEquals(1.0f, ClearItems.PEARL_SPREAD)
        assertEquals(32, ClearItems.PEARL_PUFFS)
    }

    @Test fun theFloorLeapCoolsFiveSecondsAndTheInfiniLeapTwo() {
        assertEquals(100, ClearItems.leapCooldownTicks(ClearItems.SPIRIT_LEAP))
        assertEquals(40, ClearItems.leapCooldownTicks(ClearItems.INFINILEAP))
        // A Mage's Cooldown Reduction: 49% with another Mage, 74% as the party's only one.
        assertEquals(20, ClearItems.leapCooldownTicks(ClearItems.INFINILEAP, mage = true))
        assertEquals(10, ClearItems.leapCooldownTicks(ClearItems.INFINILEAP, mage = true, doubled = true))
        assertEquals(100, ClearItems.leapCooldownTicks(ClearItems.SPIRIT_LEAP, mage = true, doubled = true))
    }

    @Test fun onlyTheFloorLeapIsUsedUp() {
        assertTrue(ClearItems.leapConsumes(ClearItems.SPIRIT_LEAP))
        assertFalse(ClearItems.leapConsumes(ClearItems.INFINILEAP))
    }

    @Test fun everySecretItemWithoutAnAbilityIsEatenByName() {
        val ids = listOf("DUNGEON_DECOY", "INFLATABLE_JERRY", "DUNGEON_TRAP", "DEFUSE_KIT", "TRAINING_WEIGHTS", "HEALING_VIII_SPLASH_POTION", "POTION")
        for (id in ids) assertTrue(id in ClearItems.INERT, "$id must be eaten")
        // the items that do something are not in the list
        for (id in listOf("SPIRIT_LEAP", "INFINITE_SPIRIT_LEAP", "ENDER_PEARL", "REVIVE_STONE", "SUPERBOOM_TNT", "HYPERION")) assertFalse(id in ClearItems.INERT, "$id has a use")
        assertEquals("Decoy", ClearItems.INERT["DUNGEON_DECOY"])
        assertEquals("The Decoy ability is not in ADS yet.", ClearItems.inertNote("Decoy"))
    }
}
