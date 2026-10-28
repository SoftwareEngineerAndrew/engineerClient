package com.engineerclient.p3sim

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.SharedConstants
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.resources.RegistryOps
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.dimension.DimensionType
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The edited dimension type goes through the game's own codec (its range and height checks included) and comes out as the sky-less 0 to 255 type the world
 * wants, with the game's other values kept. Skipped where the game cannot be bootstrapped headless.
 */
class WorldEnvDecodeTest {
    companion object {
        private var booted = false
        @JvmStatic @BeforeAll fun boot() { booted = runCatching { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() }.isSuccess }
    }

    @Test fun theEditedDimensionTypeDecodesWithTheGamesOwnCodec() {
        assumeTrue(booted, "Minecraft cannot be bootstrapped here")
        val vanilla = javaClass.getResourceAsStream("/data/minecraft/dimension_type/overworld.json")!!.use { it.readAllBytes().toString(Charsets.UTF_8) }
        val ops = RegistryOps.create(JsonOps.INSTANCE, VanillaRegistries.createWorldLookup())
        fun decode(text: String): DimensionType = DimensionType.DIRECT_CODEC.parse(ops, JsonParser.parseString(text)).getOrThrow { IllegalStateException(it) }
        val original = decode(vanilla)
        assertTrue(original.hasSkyLight()); assertEquals(-64, original.minY())
        val edited = decode(WorldEnv.dimensionTypeJson(vanilla))
        assertFalse(edited.hasSkyLight()); assertFalse(edited.hasCeiling())
        assertEquals(0, edited.minY()); assertEquals(256, edited.height()); assertEquals(256, edited.logicalHeight())
        assertEquals(original.coordinateScale(), edited.coordinateScale())
        assertEquals(original.infiniburn().toString(), edited.infiniburn().toString())
        assertEquals(original.hasEnderDragonFight(), edited.hasEnderDragonFight())
    }
}
