package com.engineerclient.waypoints

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The box editor's face selection, on a 1x1x1 box at the origin. */
class BoxFacesTest {

    private val min = doubleArrayOf(0.0, 0.0, 0.0)
    private val max = doubleArrayOf(1.0, 1.0, 1.0)
    private fun pick(eye: DoubleArray, dir: DoubleArray) = BoxFaces.select(eye, dir, min, max)
    private fun v(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)

    @Test
    fun `head on selects the far face, not the near one`() {
        assertEquals(Face.EAST, pick(v(-3.0, 0.5, 0.5), v(1.0, 0.0, 0.0)))   // standing west, looking east
        assertEquals(Face.WEST, pick(v(4.0, 0.5, 0.5), v(-1.0, 0.0, 0.0)))
        assertEquals(Face.SOUTH, pick(v(0.5, 0.5, -3.0), v(0.0, 0.0, 1.0)))
        assertEquals(Face.NORTH, pick(v(0.5, 0.5, 4.0), v(0.0, 0.0, -1.0)))
    }

    @Test
    fun `the top is selected from any angle`() {
        assertEquals(Face.UP, pick(v(0.5, 5.0, 0.5), v(0.0, -1.0, 0.0)))    // straight down onto it
        assertEquals(Face.UP, pick(v(-0.5, 3.0, 0.5), v(1.0, -2.0, 0.0)))   // down onto it at an angle
        assertEquals(Face.UP, pick(v(0.5, -2.0, 0.5), v(0.0, 1.0, 0.0)))    // up through it from below
        assertEquals(Face.UP, pick(v(0.5, 0.5, 0.5), v(0.3, 1.0, 0.0)))     // up through it from inside
    }

    @Test
    fun `looking down through a side picks the side behind, never the bottom`() {
        // Comes in by the west side and would leave through the floor: the east side is selected.
        assertEquals(Face.EAST, pick(v(-0.2, 1.1, 0.5), v(1.0, -1.0, 0.0)))
    }

    @Test
    fun `from inside, the face you look at is the one you would leave through`() {
        assertEquals(Face.NORTH, pick(v(0.5, 0.5, 0.5), v(0.0, 0.0, -1.0)))
        assertEquals(Face.EAST, pick(v(0.5, 0.5, 0.5), v(1.0, -0.2, 0.1)))
    }

    @Test
    fun `a box you are not looking at is not selected`() {
        assertNull(pick(v(-3.0, 5.0, 5.0), v(1.0, 0.0, 0.0)))    // passes beside it
        assertNull(pick(v(3.0, 0.5, 0.5), v(1.0, 0.0, 0.0)))     // it is behind you
    }
}
