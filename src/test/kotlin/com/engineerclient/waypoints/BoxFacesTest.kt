package com.engineerclient.waypoints

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `looking at the top goes through to a side, or nothing straight on`() {
        assertEquals(Face.EAST, pick(v(-0.5, 3.0, 0.5), v(1.0, -2.0, 0.0)))   // down onto the top, heading east
        assertNull(pick(v(0.5, 5.0, 0.5), v(0.0, -1.0, 0.0)))                // straight down
        assertNull(pick(v(0.5, -2.0, 0.5), v(0.0, 1.0, 0.0)))                // straight up from below
    }

    @Test
    fun `the top is selected by standing inside and looking up`() {
        assertTrue(BoxFaces.editsTop(v(0.5, 0.0, 0.5), -30f, min, max))
        assertFalse(BoxFaces.editsTop(v(0.5, 0.0, 0.5), -5f, min, max))      // not looking up enough
        assertFalse(BoxFaces.editsTop(v(0.5, 0.0, 0.5), 40f, min, max))      // looking down
        assertFalse(BoxFaces.editsTop(v(1.5, 0.0, 0.5), -30f, min, max))     // standing outside
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
        assertEquals(Face.NORTH, pick(v(0.5, 0.5, 0.5), v(0.05, 1.0, -0.2)))  // up through the top, leaving north
    }

    @Test
    fun `a box you are not looking at is not selected`() {
        assertNull(pick(v(-3.0, 5.0, 5.0), v(1.0, 0.0, 0.0)))    // passes beside it
        assertNull(pick(v(3.0, 0.5, 0.5), v(1.0, 0.0, 0.0)))     // it is behind you
    }

    @Test
    fun `a face moves freely, in past the start block too, but never below a block across`() {
        val c = intArrayOf(0, 64, 0, 1, 65, 1)                      // the 1x1x1 box on block (0, 64, 0)
        assertEquals(false, BoxFaces.move(c, Face.EAST, -1))       // 1 wide already
        BoxFaces.move(c, Face.EAST, +3)                             // 4 wide, x 0..4
        assertEquals(true, BoxFaces.move(c, Face.WEST, -3))         // west pulled in 3, past the start block
        assertEquals(3, c[BoxFaces.MIN_X])
        assertEquals(false, BoxFaces.move(c, Face.WEST, -1))        // would be 0 wide
        assertEquals(true, BoxFaces.move(c, Face.WEST, +5))         // west pushed out past where it began
        assertEquals(-2, c[BoxFaces.MIN_X])
        assertEquals(false, BoxFaces.move(c, Face.UP, -1))          // the top cannot sink below a block tall
        BoxFaces.move(c, Face.UP, +2)
        assertEquals(true, BoxFaces.move(c, Face.UP, -2))
    }
}
