package com.devgineerclient.recorder

import io.netty.buffer.Unpooled
import net.minecraft.SharedConstants
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.Strategy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ChunkCaptureTest {
    companion object {
        private var booted = false
        @JvmStatic @BeforeAll fun boot() { booted = runCatching { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() }.isSuccess }
    }

    /** A block-state section read off the wire keeps every cell's palette index (y, z, x fastest). */
    @Test fun sectionKeepsEveryCell() {
        assumeTrue(booted)
        val strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)
        val src = PalettedContainer(Blocks.AIR.defaultBlockState(), strategy)
        src.set(1, 0, 0, Blocks.STONE.defaultBlockState())
        src.set(0, 0, 1, Blocks.BEDROCK.defaultBlockState())
        val buf = FriendlyByteBuf(Unpooled.buffer())
        src.write(buf)
        val got = PalettedContainer(Blocks.AIR.defaultBlockState(), strategy).also { it.read(buf) }
        val sb = StringBuilder()
        ChunkCapture.container(sb, "rle", got.pack(strategy), 4096) { it.toString() }
        println(sb)
        assertEquals("1,0,1,1,14,0,1,2,4079,0", Regex("\"rle\":\\[([^]]*)]").find(sb)!!.groupValues[1])
    }
}
