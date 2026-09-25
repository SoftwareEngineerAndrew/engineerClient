package com.engineerclient.waypoints

import kotlin.math.abs

/** The faces of a box that can be moved. The bottom is not one of them. */
enum class Face { EAST, WEST, SOUTH, NORTH, UP }

/**
 * Which face of a box the view selects — pure maths, so it tests without the game.
 *
 * The selected face is the one the view passes out through, not the one it comes in by: a box
 * seen head on selects its far face. The top is the exception — look at it from any angle, coming
 * down onto it or leaving up through it, and it is the top. The bottom cannot be edited, so a side
 * is chosen by where the view leaves the box *horizontally*: looking down at a box through one of
 * its sides still picks the side behind it rather than the floor.
 */
object BoxFaces {

    /** Where the view first meets the box (0 if it starts inside), or null if it misses. */
    fun distance(eye: DoubleArray, dir: DoubleArray, min: DoubleArray, max: DoubleArray): Double? =
        cross(eye, dir, min, max)?.let { maxOf(it.enter, 0.0) }

    fun select(eye: DoubleArray, dir: DoubleArray, min: DoubleArray, max: DoubleArray): Face? {
        val hit = cross(eye, dir, min, max) ?: return null
        if ((hit.enter >= 0 && hit.enterAxis == Y && hit.enterHigh) || (hit.exitAxis == Y && hit.exitHigh)) return Face.UP

        var exit = Double.POSITIVE_INFINITY
        var face: Face? = null
        for (axis in intArrayOf(X, Z)) {
            val d = dir[axis]
            if (abs(d) < 1e-9) continue
            val far = maxOf((min[axis] - eye[axis]) / d, (max[axis] - eye[axis]) / d)
            if (far < exit) {
                exit = far
                face = if (axis == X) (if (d > 0) Face.EAST else Face.WEST) else (if (d > 0) Face.SOUTH else Face.NORTH)
            }
        }
        return face
    }

    /** A box as its corners, in blocks: minX, minY, minZ, maxX, maxY, maxZ (the max side exclusive). */
    const val MIN_X = 0; const val MIN_Y = 1; const val MIN_Z = 2; const val MAX_X = 3; const val MAX_Y = 4; const val MAX_Z = 5

    /**
     * Moves [face] of the box [c] by [by] blocks, outward if positive. The one rule: the box stays
     * at least a block across in every direction. Returns false, changing nothing, if it would not.
     */
    fun move(c: IntArray, face: Face, by: Int): Boolean {
        val (corner, sign, other) = when (face) {
            Face.EAST -> Triple(MAX_X, 1, MIN_X)
            Face.WEST -> Triple(MIN_X, -1, MAX_X)
            Face.SOUTH -> Triple(MAX_Z, 1, MIN_Z)
            Face.NORTH -> Triple(MIN_Z, -1, MAX_Z)
            Face.UP -> Triple(MAX_Y, 1, MIN_Y)
        }
        val next = c[corner] + sign * by
        val size = if (sign > 0) next - c[other] else c[other] - next
        if (size < 1) return false
        c[corner] = next
        return true
    }

    private const val X = 0
    private const val Y = 1
    private const val Z = 2

    /** Where the view enters and leaves the box, and through which side of which axis. */
    private class Crossing(val enter: Double, val enterAxis: Int, val enterHigh: Boolean, val exitAxis: Int, val exitHigh: Boolean)

    private fun cross(eye: DoubleArray, dir: DoubleArray, min: DoubleArray, max: DoubleArray): Crossing? {
        var enter = Double.NEGATIVE_INFINITY; var exit = Double.POSITIVE_INFINITY
        var enterAxis = -1; var enterHigh = false; var exitAxis = -1; var exitHigh = false
        for (axis in 0..2) {
            val o = eye[axis]; val d = dir[axis]
            if (d == 0.0) { if (o < min[axis] || o > max[axis]) return null; continue }
            val near = if (d > 0) (min[axis] - o) / d else (max[axis] - o) / d
            val far = if (d > 0) (max[axis] - o) / d else (min[axis] - o) / d
            // Moving up, the view comes in by the low side and leaves by the high one.
            if (near > enter) { enter = near; enterAxis = axis; enterHigh = d < 0 }
            if (far < exit) { exit = far; exitAxis = axis; exitHigh = d > 0 }
        }
        if (exit < maxOf(enter, 0.0)) return null
        return Crossing(enter, enterAxis, enterHigh, exitAxis, exitHigh)
    }
}
