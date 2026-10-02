package com.engineerclient.recorder

import com.google.gson.JsonParser
import net.minecraft.SharedConstants
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.server.Bootstrap
import net.minecraft.util.Mth
import net.minecraft.util.SimpleBitStorage
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.Strategy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Chunk sections and heightmaps decoded into palettes and runs that give back every block. */
class ChunkCaptureTest {

    companion object {
        init {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @Test
    fun rleRoundTrips() {
        val v = intArrayOf(0, 0, 0, 1, 2, 2, 0)
        assertContentEquals(intArrayOf(3, 0, 1, 1, 2, 2, 1, 0), ChunkCapture.rle(v))
        assertContentEquals(v, ChunkCapture.unrle(ChunkCapture.rle(v)))
        assertContentEquals(IntArray(0), ChunkCapture.rle(IntArray(0)))
        assertContentEquals(intArrayOf(4096, 5), ChunkCapture.rle(IntArray(4096) { 5 }))
    }

    private fun section(): PalettedContainer<BlockState> =
        PalettedContainer(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY))

    @Test
    fun singleStateSectionHasNoRuns() {
        val c = section()
        val sb = StringBuilder("{\"pal\":")
        ChunkCapture.container(sb, "rle", c.pack(Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), 4096) { BlockStateParser.serialize(it) }
        sb.append('}')
        val o = JsonParser.parseString(sb.toString()).asJsonObject
        assertEquals(listOf("minecraft:air"), o.getAsJsonArray("pal").map { it.asString })
        assertFalse(o.has("rle"))
    }

    @Test
    fun sectionDecodesBackToEveryBlock() {
        val strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)
        val c = section()
        val expected = Array<BlockState>(4096) { Blocks.AIR.defaultBlockState() }
        val states = listOf(Blocks.STONE.defaultBlockState(), Blocks.SEA_LANTERN.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState())
        for (i in 0 until 4096) if (i % 7 == 0 || i in 1000..1300) {
            val s = states[(i / 7) % states.size]
            val x = i and 15; val z = (i shr 4) and 15; val y = i shr 8
            c.set(x, y, z, s)
            expected[i] = s
        }
        val sb = StringBuilder("{\"pal\":")
        ChunkCapture.container(sb, "rle", c.pack(strategy), 4096) { BlockStateParser.serialize(it) }
        sb.append('}')
        val o = JsonParser.parseString(sb.toString()).asJsonObject
        val pal = o.getAsJsonArray("pal").map { it.asString }
        val idx = ChunkCapture.unrle(o.getAsJsonArray("rle").map { it.asInt }.toIntArray())
        assertEquals(4096, idx.size)
        for (i in 0 until 4096) assertEquals(BlockStateParser.serialize(expected[i]), pal[idx[i]], "index $i (y,z,x order, x fastest)")
        assertTrue(pal.contains("minecraft:sea_lantern"))
    }

    @Test
    fun heightmapUnpacksToAbsoluteHeights() {
        val minY = -64; val height = 384
        val storage = SimpleBitStorage(Mth.ceillog2(height + 1), 256)
        for (i in 0 until 256) storage.set(i, (i * 3) % (height + 1))
        val h = ChunkCapture.heights(storage.raw, minY, height)
        for (i in 0 until 256) assertEquals((i * 3) % (height + 1) + minY, h[i])
    }
}
