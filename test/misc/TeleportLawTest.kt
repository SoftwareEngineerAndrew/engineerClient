package com.engineerclient.misc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeleportLawTest {
    /** A stone floor (y <= 0) plus the given blocks. */
    private class World(val blocks: Map<Triple<Int, Int, Int>, String> = emptyMap()) : TeleportLaw.Cells {
        override fun id(x: Int, y: Int, z: Int) = blocks[Triple(x, y, z)] ?: if (y <= 0) "stone" else "air"
        override fun top(x: Int, y: Int, z: Int) = when (val id = id(x, y, z)) {
            "air", "torch", "player_head" -> if (id == "player_head") 0.5 else 0.0
            "oak_fence", "cobblestone_wall" -> 1.5
            "white_carpet" -> 1.0 / 16
            else -> 1.0
        }
    }

    private fun stone(vararg cells: Triple<Int, Int, Int>) = cells.associateWith { "stone" }

    private fun blink(w: World, yaw: Float, pitch: Float, d: Int = 12, sneak: Boolean = false, x: Double = 0.5, y: Double = 1.0, z: Double = 0.5) =
        TeleportLaw.blink(x, y, z, yaw, pitch, sneak, d, w)

    @Test fun blinkOpenFloorGoesTheFullDistance() {
        assertContentEquals(doubleArrayOf(0.5, 1.0, 12.5), blink(World(), 0f, 0f))
        assertContentEquals(doubleArrayOf(0.5, 1.0, 10.5), blink(World(), 0f, 0f, d = 10))
    }

    @Test fun blinkWallStopsOneCellShort() {
        assertContentEquals(doubleArrayOf(0.5, 1.0, 4.5), blink(World(stone(*(1..3).map { Triple(0, it, 5) }.toTypedArray())), 0f, 0f))
    }

    @Test fun blinkShortMoveUpIsRefused() {
        assertNull(blink(World(stone(Triple(0, 4, 0))), 0f, -90f))
    }

    @Test fun blinkOwnSpotIsRefused() {
        assertNull(blink(World(stone(*(1..3).map { Triple(0, it, 1) }.toTypedArray())), 0f, 0f))
    }

    @Test fun blinkFenceUnderTheStepEndsTheWalk() {
        assertContentEquals(doubleArrayOf(0.5, 1.0, 2.5), blink(World(mapOf(Triple(0, 1, 3) to "oak_fence")), 0f, 0f))
    }

    @Test fun blinkStartsFromAnywhere() {
        // The same click worked out again from another start (Zpew's re-prediction).
        assertContentEquals(doubleArrayOf(0.5, 1.0, 22.5), blink(World(), 0f, 0f, z = 10.5))
    }

    // Looking down at 45° from the sneak eye (feet y 1, eye 2.27) toward +z: the ray enters the floor (y 0) in cell z 1.
    private fun ether(w: World, yaw: Float = 0f, pitch: Float = 45f, x: Double = 0.5, y: Double = 1.0, z: Double = 0.5) =
        TeleportLaw.etherwarp(x, y, z, yaw, pitch, 61.0, w)

    @Test fun etherwarpLandsOnTheFloorItHits() {
        assertContentEquals(doubleArrayOf(0.5, 1.05, 1.5), ether(World()))
    }

    @Test fun etherwarpOntoAFenceIsACellHigher() {
        assertContentEquals(doubleArrayOf(0.5, 3.05, 1.5), ether(World(mapOf(Triple(0, 1, 1) to "oak_fence")), pitch = 60f))
    }

    @Test fun etherwarpPassesHeadsAndTorchesButNotCarpet() {
        val r = assertNotNull(ether(World(mapOf(Triple(0, 1, 1) to "torch")), pitch = 60f))
        assertEquals(1.05, r[1], 1e-9)
        assertContentEquals(doubleArrayOf(0.5, 2.05, 1.5), ether(World(mapOf(Triple(0, 1, 1) to "white_carpet")), pitch = 60f))
    }

    @Test fun etherwarpNeedsRoomAbove() {
        assertNull(ether(World(stone(Triple(0, 2, 1)))))
    }

    @Test fun etherwarpRefusedOnASign() {
        assertNull(ether(World(mapOf(Triple(0, 0, 1) to "oak_wall_sign"))))
    }

    @Test fun etherwarpOutOfRange() {
        assertNull(ether(World(), pitch = 0f))
    }

    @Test fun solidMaterials() {
        assertTrue(TeleportLaw.isSolid("stone"))
        assertTrue(TeleportLaw.isSolid("oak_fence"))
        assertFalse(TeleportLaw.isSolid("white_carpet"))
        assertFalse(TeleportLaw.isSolid("player_head"))
        assertFalse(TeleportLaw.isSolid("wall_torch"))
        assertFalse(TeleportLaw.isSolid("water"))
    }
}
