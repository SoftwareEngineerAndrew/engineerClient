package com.engineerclient.p3sim.run

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Why the pinned view distance is 6 (ChunkWindow): Hypixel's window is 15 chunks wide with 211 to 225 of them. */
class ChunkWindowTest {
    @Test fun sixIsHypixelsFifteenByFifteen() {
        assertEquals(15, ChunkWindow.width(ViewPin.DISTANCE))
        assertTrue(ChunkWindow.count(ViewPin.DISTANCE) in 211..225, "${ChunkWindow.count(ViewPin.DISTANCE)} chunks")
    }

    @Test fun theNeighbouringDistancesMissIt() {
        assertEquals(13, ChunkWindow.width(5)); assertEquals(157, ChunkWindow.count(5))
        assertEquals(17, ChunkWindow.width(7)); assertEquals(265, ChunkWindow.count(7))
    }

    @Test fun theWindowIsSymmetric() {
        for (d in 3..8) for (dx in -9..9) for (dz in -9..9) assertEquals(ChunkWindow.tracked(dx, dz, d), ChunkWindow.tracked(-dx, dz, d))
    }

    @Test fun fiveChunksATickFillsTheWindowInAboutFortyFiveTicks() {
        // Hypixel: the first 225 chunks take 45 ticks at exactly 5 a tick.
        assertEquals(45, 225 / ViewPin.CHUNKS_PER_TICK.toInt())
    }

    @Test fun theLoginsWindowMinusTheNewOneIsWhatGetsForgotten() {
        // A login at render distance 10 holds the whole of its window; a player who moved far away keeps none of it.
        val all = ChunkWindow.leftBehind(10) { _, _ -> false }
        assertEquals(ChunkWindow.count(10), all.size)
        assertTrue(all.all { (x, z) -> ChunkWindow.tracked(x, z, 10) })
        // Standing where the window still covers part of it: the chunks the new window holds are kept, the rest go.
        val kept = { x: Int, z: Int -> ChunkWindow.tracked(x + 8, z, ViewPin.DISTANCE) }
        val gone = ChunkWindow.leftBehind(10, kept)
        assertTrue(gone.none { (x, z) -> kept(x, z) })
        assertEquals(all.count { (x, z) -> !kept(x, z) }, gone.size)
        assertTrue(gone.isNotEmpty() && gone.size < all.size)
        // Nothing to forget when the new window covers it all.
        assertTrue(ChunkWindow.leftBehind(ViewPin.DISTANCE) { _, _ -> true }.isEmpty())
    }
}
