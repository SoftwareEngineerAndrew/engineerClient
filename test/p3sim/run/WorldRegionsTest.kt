package com.engineerclient.p3sim.run

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.ChunkAccess
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldRegionsTest {
    private class R(override val name: String, override val minX: Int, override val maxX: Int, override val minZ: Int, override val maxZ: Int) : WorldRegion {
        override fun fill(chunk: ChunkAccess) {}
        override fun resync(level: ServerLevel) = 0
    }

    @AfterEach fun reset() = WorldRegions.clear()

    @Test fun aRegionTouchesTheChunksOfItsRectangleOnly() {
        val r = R("dungeon", -200, -10, -200, -10)
        assertTrue(r.intersects(-200, -200)); assertTrue(r.intersects(-16, -16)); assertTrue(r.intersects(-10, -10))
        assertFalse(r.intersects(0, 0)); assertFalse(r.intersects(-224, -200))
        // The shared chunk (-1,-1) (x,z -16..-1): the dungeon box ends at -10, the arena starts at -8.
        val arena = R("arena", -8, 300, -8, 300)
        assertTrue(r.intersects(-16, -16) && arena.intersects(-16, -16), "both regions fill the shared chunk")
    }

    @Test fun theStoppedWorldTakesItsRegionsAndTheNextWorldKeepsItsOwn() {
        WorldRegions.register(R("old", 0, 10, 0, 10))
        WorldRegions.noteUsed("old")                       // the running world generated a chunk with it
        WorldRegions.register(R("next", 100, 110, 100, 110))   // registered for the world about to open, before the old server stopped
        WorldRegions.worldStopped()
        assertEquals(listOf("next"), WorldRegions.regions.map { it.name })
        WorldRegions.noteUsed("next"); WorldRegions.worldStopped()
        assertTrue(WorldRegions.regions.isEmpty())
    }

    @Test fun registeringAgainReplacesByNameAndForgetsThatItWasUsed() {
        WorldRegions.register(R("dungeon", 0, 1, 0, 1)); WorldRegions.noteUsed("dungeon")
        WorldRegions.register(R("dungeon", 5, 6, 5, 6))       // a new dungeon for the next world
        WorldRegions.worldStopped()
        assertEquals(1, WorldRegions.regions.size); assertEquals(5, WorldRegions.regions[0].minX)
    }
}
