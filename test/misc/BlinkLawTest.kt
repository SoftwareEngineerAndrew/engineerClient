package com.engineerclient.misc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlinkLawTest {
    /** A floor (y <= 0) plus the given solid cells and tall (fence) cells. */
    private class World(val solids: Set<Triple<Int, Int, Int>> = emptySet(), val fences: Set<Triple<Int, Int, Int>> = emptySet()) {
        fun solid(x: Int, y: Int, z: Int) = y <= 0 || Triple(x, y, z) in solids || Triple(x, y, z) in fences
        fun top(x: Int, y: Int, z: Int) = if (Triple(x, y, z) in fences) 1.5 else if (solid(x, y, z)) 1.0 else 0.0
    }

    private fun blink(w: World, yaw: Float, pitch: Float, d: Int = 12, sneak: Boolean = false, x: Double = 0.5, y: Double = 1.0, z: Double = 0.5) =
        BlinkLaw.predict(x, y, z, yaw, pitch, sneak, d, w::solid, w::top)

    @Test fun openFloorGoesTheFullDistance() {
        assertContentEquals(doubleArrayOf(0.5, 1.0, 12.5), blink(World(), 0f, 0f))
        assertContentEquals(doubleArrayOf(0.5, 1.0, 10.5), blink(World(), 0f, 0f, d = 10))
    }

    @Test fun wallStopsOneCellShort() {
        val wall = (1..3).map { Triple(0, it, 5) }.toSet()
        assertContentEquals(doubleArrayOf(0.5, 1.0, 4.5), blink(World(wall), 0f, 0f))
    }

    @Test fun shortMoveUpIsRefused() {
        val ceiling = setOf(Triple(0, 4, 0))
        assertNull(blink(World(ceiling), 0f, -90f))
    }

    @Test fun ownSpotIsRefused() {
        val wall = (1..3).map { Triple(0, it, 1) }.toSet()
        assertNull(blink(World(wall), 0f, 0f))
    }

    @Test fun fenceUnderTheStepEndsTheWalk() {
        // A fence at z = 3: its cell is solid, and the step over it sits on a block taller than a cell.
        val fence = setOf(Triple(0, 1, 3))
        assertContentEquals(doubleArrayOf(0.5, 1.0, 2.5), blink(World(fences = fence), 0f, 0f))
    }

    @Test fun solidMaterials() {
        assertTrue(BlinkLaw.isSolid("stone"))
        assertTrue(BlinkLaw.isSolid("oak_fence"))
        assertFalse(BlinkLaw.isSolid("white_carpet"))
        assertFalse(BlinkLaw.isSolid("player_head"))
        assertFalse(BlinkLaw.isSolid("wall_torch"))
        assertFalse(BlinkLaw.isSolid("water"))
    }
}
