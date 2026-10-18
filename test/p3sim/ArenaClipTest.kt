package com.engineerclient.p3sim

import com.engineerclient.p3sim.run.ArenaRegion
import com.engineerclient.p3sim.run.WorldRegions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seam bug against the real arena data in the jar: the extension writes blocks at x, z -16..-10, which is dungeon tile (5,5)'s
 * corner. Needs Minecraft's block registry; skipped when the game's resources cannot be opened headless.
 */
class ArenaClipTest {
    companion object {
        var booted = false
        @JvmStatic @BeforeAll fun boot() {
            booted = try { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); true } catch (t: Throwable) { false }
        }
    }

    @AfterEach fun reset() { WorldRegions.clear(); Arena.unload() }

    @Test fun theExtensionReallyWritesIntoTheDungeonCorner() {
        Assumptions.assumeTrue(booted, "Minecraft cannot be bootstrapped here")
        val ext = Arena.readBin("arena-ext.bin")
        var inCorner = 0
        for (y in 0 until ext.h) for (z in 0 until ext.d) for (x in 0 until ext.w) {
            val wx = ext.x0 + x; val wz = ext.z0 + z
            if (wx in -16..-10 && wz in -16..-10 && !ext.states[ext.cells[(y * ext.d + z) * ext.w + x].toInt()].`is`(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID)) inCorner++
        }
        assertTrue(inCorner > 0, "the old data has blocks in the corner of tile (5,5)")
        println("[ArenaClipTest] extension cells at x, z -16..-10: $inCorner")
    }

    @Test fun withTheArenaRegionTheArenaStartsAtMinus8() {
        Assumptions.assumeTrue(booted, "Minecraft cannot be bootstrapped here")
        val base = Arena.readBin("arena.bin")
        val plain = ArenaFixes.patch(base)
        assertTrue(plain.x0 < -8 || plain.z0 < -8, "unclipped, the boss practice's box reaches into the dungeon's corner (x0 ${plain.x0}, z0 ${plain.z0})")
        ArenaRegion.register()
        val clipped = ArenaFixes.patch(base)
        assertEquals(-8, clipped.x0); assertEquals(-8, clipped.z0)
        val air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
        for (x in -16..-9) for (z in -16..-9) for (y in 0..255) assertTrue(clipped.get(x, y, z) === air, "no arena block at $x,$y,$z")
        // Everything the arena owns is the same with or without the clip.
        for (x in -8..40 step 3) for (z in -8..40 step 3) for (y in 40..140 step 5) assertEquals(plain.get(x, y, z), clipped.get(x, y, z), "arena block at $x,$y,$z")
    }
}
