package com.engineerclient.p3sim.secrets

import com.engineerclient.p3sim.clear.secrets.SecretWorld
import com.engineerclient.p3sim.pack.rooms.SkullTexture
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The parts of [SecretWorld] that are plain data (no world needed): the essence skull's texture and the bat's health. */
class SecretWorldPureTest {
    // Synthetic ids: the repository holds no recorded texture id, the real ones come from the rooms pack at runtime.
    private val skinId = "ab".repeat(32)

    @Test fun theEssenceSkullCarriesThePacksSkinAndKeepsThePacksProfileId() {
        val packEntry = SkullTexture("cd".repeat(32), "stale", "6e5ae0e4-0000-4000-8000-000000000001", "essence")
        val skin = SkullTexture(skinId, null, null, SecretWorld.ESSENCE_SKIN_ROLE)
        val tex = SecretWorld.essenceTexture(packEntry, skin)
        assertEquals(skinId, tex.id)
        assertEquals("6e5ae0e4-0000-4000-8000-000000000001", tex.profileId, "Odin matches the profile id, which stays the pack's")
        assertEquals("essence", tex.role)
        val json = String(Base64.getDecoder().decode(tex.value!!))
        assertTrue(json.contains("http://textures.minecraft.net/texture/$skinId"), json)
        assertTrue(!json.contains("cdcdcdcd"), "the pack's synthesised essence entry never supplies the skin")
        assertEquals(null, SecretWorld.essenceTexture(null, null).profileId)
    }

    @Test fun anEssenceSkullWithoutASkinEntryIsAPlainHeadThatOdinStillMatches() {
        val packEntry = SkullTexture("cd".repeat(32), "stale", "6e5ae0e4-0000-4000-8000-000000000001", "essence")
        val tex = SecretWorld.essenceTexture(packEntry, null)
        assertEquals(null, tex.value, "no skin, so the head is plain")
        assertEquals("6e5ae0e4-0000-4000-8000-000000000001", tex.profileId)
    }

    @Test fun aSecretBatHasHundredHealth() {
        assertEquals(100f, SecretWorld.BAT_HEALTH)
    }
}
