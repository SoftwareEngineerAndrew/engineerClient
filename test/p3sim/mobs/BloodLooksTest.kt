package com.engineerclient.p3sim.clear.mobs

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Tags copied from ADR recordings of real camps. */
class BloodLooksTest {
    private fun arch(name: String) = assertNotNull(BloodLooks.forName(name))

    @Test
    fun tagsAreHypixels() {
        assertEquals("§2 §r§bSpeedy Psycho§r §a256k§r§c❤", BloodLooks.tag(arch("Psycho"), "Speedy", 256_000.0, 256_000.0))
        assertEquals("§2 §r§bSpeedy Psycho§r §c0❤", BloodLooks.tag(arch("Psycho"), "Speedy", 0.0, 256_000.0))
        assertEquals("§2 §r§7Stealth Ooze§r §e100k§r§c❤", BloodLooks.tag(arch("Ooze"), "Stealth", 100_000.0, 310_000.0))
        assertEquals("§2§r§7 §r§bSpeedy Giant§r §a25.4M§r§c❤", BloodLooks.tag(arch("Giant"), "Speedy", 25_400_000.0, 25_400_000.0))
        assertEquals("§2§r§7 Stealth Giant§r §a15M§r§c❤", BloodLooks.tag(arch("Giant"), "Stealth", 15_000_000.0, 15_000_000.0))
        assertEquals("§e§r§5 §r§b§lSpeedy Bonzo§r §a3M§r§c❤", BloodLooks.tag(arch("Bonzo"), "Speedy", 3_000_000.0, 3_000_000.0))
        assertEquals("§a§r§5 §r§c§lBoomer Spirit Bear§r §a10M§r§c❤", BloodLooks.tag(arch("Spirit Bear"), "Boomer", 10_000_000.0, 10_000_000.0))
        assertEquals("§2 §r§b§lSpeedy Livid§r §a10M§r§c❤", BloodLooks.tag(arch("Livid"), "Speedy", 10_000_000.0, 10_000_000.0))
    }

    @Test
    fun profilesAreNamedLikeHypixels() {
        assertEquals("Revoker ", BloodLooks.profileName(arch("Revoker")))
        assertEquals("Mr. Dead", BloodLooks.profileName(arch("Mr. Dead")))
        assertEquals("Revoker ", Disguise.profileName(arch("Revoker")))
        assertEquals("minecraft:giant", arch("Giant").entity)
    }
}
