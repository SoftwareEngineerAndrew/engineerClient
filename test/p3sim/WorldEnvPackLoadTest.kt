package com.engineerclient.p3sim

import com.google.gson.JsonParser
import net.minecraft.SharedConstants
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.PackCompatibility
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.server.packs.resources.MultiPackResourceManager
import net.minecraft.world.level.validation.DirectoryValidator
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The game itself reads the world pack the way `WorldOpenFlows.createFreshLevel` does: a pack repository over the world folder's `datapacks`, the pack
 * found under the id [WorldEnv.PACK_ID] and compatible with the running game, and its `overworld` dimension type the one the resource stack serves
 * above the game's own. Skipped where the game cannot be bootstrapped headless.
 */
class WorldEnvPackLoadTest {
    companion object {
        private var booted = false
        @JvmStatic @BeforeAll fun boot() { booted = runCatching { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() }.isSuccess }
    }

    @Test fun theGameFindsThePackInAWorldFolderAndServesItsDimensionTypeOverTheGamesOwn(@TempDir dir: Path) {
        assumeTrue(booted, "Minecraft cannot be bootstrapped here")
        val vanilla = javaClass.getResourceAsStream("/data/minecraft/dimension_type/overworld.json")!!.use { it.readAllBytes().toString(Charsets.UTF_8) }
        val format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA)
        val world = dir.resolve("p3sim")
        WorldEnv.installPack(world, vanilla, format.major(), format.minor())

        val repository = ServerPacksSource.createPackRepository(world.resolve("datapacks"), DirectoryValidator { true })
        repository.reload()
        val pack = repository.getPack(WorldEnv.PACK_ID)
        assertNotNull(pack, "the pack is found under ${WorldEnv.PACK_ID} (found: ${repository.availableIds})")
        assertEquals(PackCompatibility.COMPATIBLE, pack.compatibility, "the pack fits the running game's data format")

        repository.setSelected(listOf("vanilla", WorldEnv.PACK_ID))
        assertTrue(WorldEnv.PACK_ID in repository.selectedIds, "selected: ${repository.selectedIds}")
        MultiPackResourceManager(PackType.SERVER_DATA, repository.openAllSelected()).use { manager ->
            val served = manager.getResource(Identifier.parse("minecraft:dimension_type/overworld.json")).orElseThrow()
            val o = served.openAsReader().use { JsonParser.parseReader(it).asJsonObject }
            assertFalse(o.get("has_skylight").asBoolean, "the pack's file is the one served")
            assertEquals(0, o.get("min_y").asInt); assertEquals(256, o.get("height").asInt)
            // The game's own copy is still underneath it.
            assertTrue(manager.getResourceStack(Identifier.parse("minecraft:dimension_type/overworld.json")).size >= 2)
        }
    }
}
