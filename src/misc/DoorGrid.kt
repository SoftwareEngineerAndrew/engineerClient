package com.engineerclient.misc

import kotlin.math.abs
import kotlin.math.floor

/**
 * Where dungeon doors are, pure maths so it tests without the game.
 *
 * Rooms sit on a 6x6 grid of 32-block tiles whose first centre is at -185. Every wither or blood door
 * is 36 blocks (3x3 across, y 69-72) centred halfway between two neighbouring tiles, so the door
 * between tiles (i, j) and (i+1, j) is centred at x = -169 + 32i, z = -185 + 32j, and the one between
 * (i, j) and (i, j+1) at x = -185 + 32i, z = -169 + 32j.
 *
 * Hypixel opens a door for a left click (start mining) or a right click (use on block) on any block
 * within one block of it: 5x5 around the centre, y 68-73, so the frame, floor and ceiling around the
 * door count too (measured from 578 recorded door opens).
 */
object DoorGrid {

    const val DOOR_Y0 = 69
    const val DOOR_Y1 = 72

    /** A door's centre column and the two tiles it joins. */
    data class Door(val x: Int, val z: Int, val a: Pair<Int, Int>, val b: Pair<Int, Int>)

    fun tileOf(x: Double, z: Double): Pair<Int, Int> = floor((x + 200) / 32).toInt() to floor((z + 200) / 32).toInt()

    fun tileCentre(t: Pair<Int, Int>): Pair<Int, Int> = -185 + 32 * t.first to -185 + 32 * t.second

    /** Every place a door can be: 60 of them. */
    val ALL: List<Door> = buildList {
        for (i in 0..5) for (j in 0..5) {
            if (i < 5) add(Door(-169 + 32 * i, -185 + 32 * j, i to j, i + 1 to j))
            if (j < 5) add(Door(-185 + 32 * i, -169 + 32 * j, i to j, i to j + 1))
        }
    }

    /** The door whose click zone holds this block, if any. */
    fun doorAt(x: Int, y: Int, z: Int): Door? {
        if (y < DOOR_Y0 - 1 || y > DOOR_Y1 + 1) return null
        return ALL.firstOrNull { abs(it.x - x) <= 2 && abs(it.z - z) <= 2 }
    }

    /** Which of the door's two tiles is further from (x, z): the side you're about to open. */
    fun farSide(d: Door, x: Double, z: Double): Pair<Int, Int> {
        fun dist(t: Pair<Int, Int>): Double { val c = tileCentre(t); return (c.first - x) * (c.first - x) + (c.second - z) * (c.second - z) }
        return if (dist(d.a) >= dist(d.b)) d.a else d.b
    }
}
