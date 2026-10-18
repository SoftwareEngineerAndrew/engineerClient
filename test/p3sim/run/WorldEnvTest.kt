package com.engineerclient.p3sim.run

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The ADS world's sky: the dimension type edit and the data pack it travels in (WorldEnv). Synthetic input shaped like the game's own file. */
class WorldEnvTest {
    /** A dimension type shaped like the game's overworld one, with made-up values where the test does not care. */
    private val vanilla = """
        {"ambient_light":0.0,"attributes":{"minecraft:audio/ambient_sounds":{"mood":{"sound":"x:y","tick_delay":6000}},
        "minecraft:audio/background_music":{"default":{"min_delay":12000,"max_delay":24000,"sound":"x:music"}},
        "minecraft:visual/sky_color":"#78a7ff"},"coordinate_scale":1.0,"default_clock":"minecraft:overworld","has_ceiling":false,
        "has_ender_dragon_fight":false,"has_skylight":true,"height":384,"infiniburn":"#minecraft:infiniburn_overworld","logical_height":384,
        "min_y":-64,"monster_spawn_block_light_limit":0,"timelines":"#minecraft:in_overworld"}
    """.trimIndent()

    @Test fun theDimensionIsZeroTo255WithoutSkyLight() {
        val o = JsonParser.parseString(WorldEnv.dimensionTypeJson(vanilla)).asJsonObject
        assertFalse(o.get("has_skylight").asBoolean)
        assertFalse(o.get("has_ceiling").asBoolean)
        assertEquals(0, o.get("min_y").asInt)
        assertEquals(256, o.get("height").asInt)
        assertEquals(256, o.get("logical_height").asInt)
        assertEquals(0.0, o.get("ambient_light").asDouble)
    }

    @Test fun theCaveMoodSoundIsGoneAndTheRestStays() {
        val o = JsonParser.parseString(WorldEnv.dimensionTypeJson(vanilla)).asJsonObject
        val attributes = o.getAsJsonObject("attributes")
        assertFalse(attributes.has("minecraft:audio/ambient_sounds"))
        assertTrue(attributes.has("minecraft:audio/background_music"))
        assertEquals("#78a7ff", attributes.get("minecraft:visual/sky_color").asString)
        assertEquals("minecraft:overworld", o.get("default_clock").asString)
        assertEquals("#minecraft:infiniburn_overworld", o.get("infiniburn").asString)
        assertEquals("#minecraft:in_overworld", o.get("timelines").asString)
        assertEquals(1.0, o.get("coordinate_scale").asDouble)
    }

    @Test fun aFileWithoutAttributesStillWorks() {
        val o = JsonParser.parseString(WorldEnv.dimensionTypeJson("""{"has_skylight":true,"min_y":-64,"height":384,"logical_height":384}""")).asJsonObject
        assertEquals(0, o.get("min_y").asInt); assertFalse(o.get("has_skylight").asBoolean)
    }

    @Test fun theHeightFitsTheArenaAndTheDungeon() {
        // The vanilla constructor wants multiples of 16; the boss arena and the dungeon were captured at y 0..255.
        assertEquals(0, WorldEnv.MIN_Y % 16); assertEquals(0, WorldEnv.HEIGHT % 16)
        assertEquals(256, WorldEnv.MIN_Y + WorldEnv.HEIGHT)
    }

    @Test fun packMetadataNamesTheRunningGameFormat() {
        val pack = JsonParser.parseString(WorldEnv.packMetaJson(121, 0)).asJsonObject.getAsJsonObject("pack")
        assertEquals(listOf(121, 0), pack.getAsJsonArray("min_format").map { it.asInt })
        assertEquals(121, pack.get("max_format").asInt)
        assertTrue(pack.get("description").asString.isNotBlank())
    }

    @Test fun thePackIsWrittenWhereTheGameLooksForAWorldPack(@TempDir dir: Path) {
        val world = dir.resolve("p3sim")   // does not exist yet: the world folder is made by the game afterwards
        val root = WorldEnv.installPack(world, vanilla, 121, 0)
        assertEquals(world.resolve("datapacks").resolve("ads-env"), root)
        assertTrue(Files.isRegularFile(root.resolve("pack.mcmeta")))
        val file = root.resolve("data/minecraft/dimension_type/overworld.json")
        assertEquals(WorldEnv.dimensionTypeJson(vanilla), Files.readString(file))
        assertEquals("file/ads-env", WorldEnv.PACK_ID)
    }

    @Test fun aBadFileWritesNothing(@TempDir dir: Path) {
        val world = dir.resolve("p3sim")
        runCatching { WorldEnv.installPack(world, "not json", 121, 0) }
        assertFalse(Files.exists(world.resolve("datapacks")))
    }

    @Test fun theArenaBoxCoversTenByElevenChunks() {
        // The arena box: x -8..143, z -8..159 (152 x 168 blocks from -8): chunks -1..8 by -1..9.
        val chunks = WorldEnv.chunksOf(-8, -8, 152, 168)
        assertEquals(110, chunks.size)
        assertEquals((-1 to -1), chunks.first()); assertEquals((8 to 9), chunks.last())
        assertTrue((0 to 0) in chunks && (8 to -1) in chunks && (-1 to 9) in chunks)
        assertEquals(listOf(0 to 0), WorldEnv.chunksOf(0, 0, 16, 16))
        assertEquals(listOf(-1 to -1, -1 to 0, 0 to -1, 0 to 0), WorldEnv.chunksOf(-1, -1, 2, 2))
        assertTrue(WorldEnv.chunksOf(0, 0, 0, 5).isEmpty())
    }
}
