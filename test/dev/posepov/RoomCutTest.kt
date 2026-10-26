package com.devgineerclient.posepov

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A one-tile room: its outside faces and a sealed pocket left out, the door slots and the open air kept. */
class RoomCutTest {
    private val y0 = 66
    private val h = 20

    /** Solid walls one block thick all round, a floor, and a solid 3x3x3 cube with one air cell in it at (10, 70, 10). */
    private fun opaque(x: Int, y: Int, z: Int): Boolean {
        if (y == y0) return true
        if (x == 0 || z == 0 || x == 30 || z == 30) return !(y in 69..72 && (x == 15 || z == 15))
        if (x in 9..11 && y in 69..71 && z in 9..11) return !(x == 10 && y == 70 && z == 10)
        return false
    }

    private fun quad(vararg c: Float) = c

    @Test fun pocketsAndOutsideAreCut() {
        val hidden = assertNotNull(RoomCut.pockets(31, 31, y0, h, ::opaque))
        val cut = RoomCut(31, 31, y0, h, hidden)
        assertTrue(cut.hiddenAt(10, 70, 10))
        assertFalse(cut.hiddenAt(5, 70, 5))
        assertEquals(1, hidden.cardinality())
        // The floor's underside (group 0 at y0).
        assertTrue(cut.drops(0, quad(1f, 66f, 1f, 2f, 66f, 1f, 2f, 66f, 2f, 1f, 66f, 2f), true))
        // The outer side of the north wall (group 2 at z = 0), away from a door.
        assertTrue(cut.drops(2, quad(3f, 80f, 0f, 4f, 80f, 0f, 4f, 81f, 0f, 3f, 81f, 0f), true))
        // ...but not at door height by its door slot.
        assertFalse(cut.drops(2, quad(15f, 70f, 0f, 16f, 70f, 0f, 16f, 71f, 0f, 15f, 71f, 0f), true))
        // A face of the cube looking into the pocket: the east face of (9, 70, 10) is at x = 10 looking east (group 5).
        assertTrue(cut.drops(5, quad(10f, 70f, 10f, 10f, 70f, 11f, 10f, 71f, 11f, 10f, 71f, 10f), true))
        assertFalse(cut.drops(5, quad(10f, 70f, 10f, 10f, 70f, 11f, 10f, 71f, 11f, 10f, 71f, 10f), false))
        // The cube's outer face, looking into the open room: kept.
        assertFalse(cut.drops(4, quad(9f, 70f, 10f, 9f, 70f, 11f, 9f, 71f, 11f, 9f, 71f, 10f), true))
    }
}
