package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.Arena
import com.engineerclient.p3sim.ArenaFixes
import net.minecraft.core.Holder
import net.minecraft.core.IdMap
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.ProtoChunk
import net.minecraft.world.level.chunk.Strategy
import net.minecraft.world.level.chunk.UpgradeData
import net.minecraft.world.ticks.ProtoChunkTicks
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The world is 0 to 255 high now (WorldEnv), not the overworld's -64 to 319: everything the sim writes into a chunk has to fit the 16 sections, and the
 * section lookups have to land on the right one. Real chunks of the new height, the real arena data. Skipped where the game cannot be bootstrapped headless.
 */
class WorldHeightTest {
    companion object {
        var booted = false
        @JvmStatic @BeforeAll fun boot() {
            booted = try { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); true } catch (t: Throwable) { false }
        }

        /** A bare chunk of Hypixel's height (0..255, 16 sections). */
        @Suppress("UNCHECKED_CAST")
        fun chunk(cx: Int, cz: Int): ProtoChunk {
            val height = LevelHeightAccessor.create(WorldEnv.MIN_Y, WorldEnv.HEIGHT)
            val air = Blocks.AIR.defaultBlockState()
            val biomeIds = object : IdMap<Any> {
                override fun getId(value: Any) = 0
                override fun byId(id: Int): Any? = null
                override fun size() = 1
                override fun iterator(): MutableIterator<Any> = mutableListOf<Any>().iterator()
            }
            val blocks = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)
            val biomes = Strategy.createForBiomes(biomeIds as IdMap<Holder<*>>)
            val stand = Holder.direct(Any()) as Holder<*>
            val sections = Array(height.sectionsCount) {
                LevelChunkSection(PalettedContainer(air, blocks), PalettedContainer(stand, biomes) as net.minecraft.world.level.chunk.PalettedContainerRO<Holder<net.minecraft.world.level.biome.Biome>>)
            }
            val ctor = ProtoChunk::class.java.constructors.first { it.parameterCount == 8 }
            return ctor.newInstance(ChunkPos(cx, cz), UpgradeData.EMPTY, sections, ProtoChunkTicks<Block>(), ProtoChunkTicks<net.minecraft.world.level.material.Fluid>(), height, null, null) as ProtoChunk
        }
    }

    @AfterEach fun reset() { WorldRegions.clear(); Arena.unload() }

    @Test fun sixteenSectionsAndTheSectionIndexIsTheHeightOver16() {
        Assumptions.assumeTrue(booted, "Minecraft cannot be bootstrapped here")
        val c = chunk(0, 0)
        assertEquals(16, c.sections.size)
        for (y in listOf(0, 15, 16, 66, 127, 255)) assertEquals(y shr 4, c.getSectionIndex(y), "section of y $y")
        // A block written through its section is the block the chunk reads back at the same position, at both ends of the height.
        val stone = Blocks.STONE.defaultBlockState()
        for (y in listOf(0, 255)) {
            c.getSection(c.getSectionIndex(y)).setBlockState(3, y and 15, 4, stone, false)
            assertEquals(stone, c.getBlockState(net.minecraft.core.BlockPos(3, y, 4)), "block at y $y")
        }
    }

    @Test fun bothArenaFilesFitTheWorldsHeight() {
        Assumptions.assumeTrue(booted, "Minecraft cannot be bootstrapped here")
        for (name in listOf("arena.bin", "arena-ext.bin")) {
            val a = Arena.readBin(name)
            assertTrue(a.y0 >= WorldEnv.MIN_Y && a.y0 + a.h <= WorldEnv.MIN_Y + WorldEnv.HEIGHT, "$name spans y ${a.y0}..${a.y0 + a.h - 1}")
        }
        val patched = ArenaFixes.patch(Arena.readBin("arena.bin"))
        assertTrue(patched.y0 >= WorldEnv.MIN_Y && patched.y0 + patched.h <= WorldEnv.MIN_Y + WorldEnv.HEIGHT, "the patched arena spans y ${patched.y0}..${patched.y0 + patched.h - 1}")
    }
}
