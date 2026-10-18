package com.engineerclient.p3sim.layout

import net.minecraft.SharedConstants
import net.minecraft.core.Holder
import net.minecraft.core.IdMap
import net.minecraft.server.Bootstrap
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

/** Real Minecraft chunks for the headless tests: bootstraps the game's registries once and builds bare generation chunks. */
object TestChunks {
    private var booted: Boolean? = null

    /** Bootstraps Minecraft (once); false where that is not possible, so a test can skip. */
    @Synchronized fun boot(): Boolean {
        booted?.let { return it }
        return runCatching { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() }.isSuccess.also { booted = it }
    }

    /** A bare chunk at ([cx], [cz]) of the full world height (-64..319): sections are plain, biomes are a stand-in the fill never reads. */
    @Suppress("UNCHECKED_CAST")
    fun chunk(cx: Int, cz: Int): ProtoChunk {
        val height = LevelHeightAccessor.create(-64, 384)
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
        // Through reflection: the container factory (needed only to make missing sections) and the blending data are null here.
        val ctor = ProtoChunk::class.java.constructors.first { it.parameterCount == 8 }
        return ctor.newInstance(ChunkPos(cx, cz), UpgradeData.EMPTY, sections, ProtoChunkTicks<Block>(), ProtoChunkTicks<net.minecraft.world.level.material.Fluid>(), height, null, null) as ProtoChunk
    }

}
