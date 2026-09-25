package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals

/** Doors found from their blocks, at positions taken from the recorded runs. */
class DoorBlocksTest {

    /** A 3x3 footprint (the door is 4 tall, so each column shows up 4 times) centred on (x, z). */
    private fun door(x: Int, z: Int) = (-1..1).flatMap { dx -> (-1..1).flatMap { dz -> List(4) { (x + dx) to (z + dz) } } }

    @Test
    fun `a door is placed between the two tiles it joins`() {
        // Recorded: the door out of Entrance (tile 0,2) into Pipes (tile 1,2).
        assertEquals(listOf(DoorBlocks.Door(0 to 2, 1 to 2)), DoorBlocks.doors(door(-169, -121)))
        // And one running north-south: Duncan (2,2) into Deathmite (2,3).
        assertEquals(listOf(DoorBlocks.Door(2 to 2, 2 to 3)), DoorBlocks.doors(door(-121, -105)))
    }

    @Test
    fun `two doors on one tick are two doors, not one between them`() {
        // Recorded: fairy's door and the one out of Entrance came down together.
        val doors = DoorBlocks.doors(door(-105, -57) + door(-169, -121))
        assertEquals(setOf(DoorBlocks.Door(2 to 4, 3 to 4), DoorBlocks.Door(0 to 2, 1 to 2)), doors.toSet())
    }

    @Test
    fun `a handful of blocks is not a door`() {
        assertEquals(emptyList(), DoorBlocks.doors(door(-169, -121).take(12)))
    }
}
