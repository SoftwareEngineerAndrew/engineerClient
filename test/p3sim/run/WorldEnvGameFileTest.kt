package com.engineerclient.p3sim.run

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The world's dimension type is edited as text (WorldEnv), so a game update that renames one of its keys would otherwise show up as a world that is
 * quietly vanilla again. Checked against the game's own file on the test classpath.
 */
class WorldEnvGameFileTest {
    @Test fun theGamesOverworldDimensionTypeHasTheKeysWorldEnvEdits() {
        val text = javaClass.getResourceAsStream("/data/minecraft/dimension_type/overworld.json")?.use { it.readAllBytes().toString(Charsets.UTF_8) }
        assertNotNull(text, "the game's overworld dimension type")
        val vanilla = JsonParser.parseString(text).asJsonObject
        for (key in listOf("has_skylight", "has_ceiling", "min_y", "height", "logical_height", "ambient_light", "attributes")) assertTrue(vanilla.has(key), key)
        assertTrue(vanilla.get("has_skylight").asBoolean, "the game's overworld has sky light, which is what the pack takes away")
        // What the edit leaves is still the game's file: nothing else lost, and the edited keys changed.
        val edited = JsonParser.parseString(WorldEnv.dimensionTypeJson(text)).asJsonObject
        assertEquals(vanilla.keySet(), edited.keySet())
        assertFalse(edited.get("has_skylight").asBoolean)
        assertEquals(0, edited.get("min_y").asInt)
        assertEquals(256, edited.get("height").asInt)
        assertTrue(vanilla.getAsJsonObject("attributes").has("minecraft:audio/ambient_sounds"), "the cave mood sound is where WorldEnv looks for it")
        assertFalse(edited.getAsJsonObject("attributes").has("minecraft:audio/ambient_sounds"))
    }
}
