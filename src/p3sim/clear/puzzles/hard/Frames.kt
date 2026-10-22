package com.engineerclient.p3sim.clear.puzzles.hard

import com.engineerclient.p3sim.pack.rooms.Placement

/**
 * Positions between the room's canonical frame and the world, for things that are not on block cells (a sliding silverfish, a
 * player). The cells follow [Placement.toWorld]; a position inside a cell is turned by the placement's quarter turns about the cell's
 * middle. Pure: no Minecraft.
 */
object Frames {
    /** The world x, z of canonical position ([cx], [cz]) (block coordinates, a cell's middle at +0.5). */
    fun toWorld(p: Placement, cx: Double, cz: Double): Pair<Double, Double> {
        val bx = Math.floor(cx).toInt(); val bz = Math.floor(cz).toInt()
        val w = p.toWorld(bx, 0, bz)
        val ox = cx - bx - 0.5; val oz = cz - bz - 0.5
        val (rx, rz) = when (((p.quarterTurns % 4) + 4) % 4) { 1 -> -oz to ox; 2 -> -ox to -oz; 3 -> oz to -ox; else -> ox to oz }
        return w.x + 0.5 + rx to w.z + 0.5 + rz
    }

    /** The canonical x, z of world position ([wx], [wz]), or null when it lies outside the placed room. */
    fun toCanonical(p: Placement, wx: Double, wz: Double): Pair<Double, Double>? {
        val bx = Math.floor(wx).toInt(); val bz = Math.floor(wz).toInt()
        val (cx, cz) = p.toCanonical(bx, bz) ?: return null
        val rx = wx - bx - 0.5; val rz = wz - bz - 0.5
        val (ox, oz) = when (((p.quarterTurns % 4) + 4) % 4) { 1 -> rz to -rx; 2 -> -rx to -rz; 3 -> -rz to rx; else -> rx to rz }
        return cx + 0.5 + ox to cz + 0.5 + oz
    }

    /** The world yaw of a canonical yaw (a clockwise quarter turn of the room turns a south-facing look to the west: +90). */
    fun yawToWorld(p: Placement, yaw: Float): Float = yaw + 90f * (((p.quarterTurns % 4) + 4) % 4)
}
