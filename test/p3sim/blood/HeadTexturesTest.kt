package com.engineerclient.p3sim.blood

import com.engineerclient.p3sim.clear.blood.HeadTextures
import com.engineerclient.p3sim.pack.rooms.SkullTexture
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class HeadTexturesTest {
    private val id = "ab".repeat(32)
    private val table = listOf(
        SkullTexture("cd".repeat(32), "VALUE-FROM-PACK", "11111111-2222-3333-4444-555555555555", "test-role"),
    )

    @Test fun noneAndPlainAndGarbageGiveNoTexture() {
        assertNull(HeadTextures.resolve(HeadTextures.NONE, table))
        assertNull(HeadTextures.resolve(HeadTextures.PLAIN, table))
        assertNull(HeadTextures.resolve("", table))
        assertNull(HeadTextures.resolve("not a texture", table))
    }

    @Test fun anIdBecomesTheTexturesPropertyValue() {
        val r = HeadTextures.resolve(id, emptyList())!!
        val json = String(Base64.getDecoder().decode(r.value), Charsets.UTF_8)
        assertEquals("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/$id\"}}}", json)
    }

    @Test fun thePacksOwnEntryWinsByIdThenByRole() {
        val byId = HeadTextures.resolve("cd".repeat(32), table)!!
        assertEquals("VALUE-FROM-PACK", byId.value)
        assertEquals(UUID.fromString("11111111-2222-3333-4444-555555555555"), byId.profileId)
        assertEquals("VALUE-FROM-PACK", HeadTextures.resolve("test-role", table)!!.value)
    }

    @Test fun theProfileIdIsStablePerKeyAndDiffersBetweenKeys() {
        val a = HeadTextures.resolve(id, emptyList())!!.profileId
        assertEquals(a, HeadTextures.resolve(id, emptyList())!!.profileId)
        assertNotEquals(a, HeadTextures.resolve("ef".repeat(32), emptyList())!!.profileId)
    }
}
