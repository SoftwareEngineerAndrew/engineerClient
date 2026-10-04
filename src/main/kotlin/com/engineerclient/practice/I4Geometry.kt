package com.engineerclient.practice

import com.engineerclient.p3sim.ShotPlan
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * The i4 board and what one shortbow volley hits on it, from Hypixel's measured arrows ([ShotPlan]). Pure, so it
 * tests offline. The 9 cells are blocks at x 64/66/68, y 126/128/130, z 50, with glass panes between them.
 */
object I4Geometry {
    val CELLS: List<BlockPos> = (0 until 9).map { BlockPos(64 + (it % 3) * 2, 126 + (it / 3) * 2, 50) }

    /** The cell an arrow at [from] moving [v0] reaches at the board's face (z = 50), flying vanilla; null for a pane or off the board. */
    fun cellHit(from: Vec3, v0: Vec3): BlockPos? {
        val (x, y) = landing(from, v0) ?: return null
        return CELLS.firstOrNull { x >= it.x && x < it.x + 1 && y >= it.y && y < it.y + 1 }
    }

    /** Where (x, y) an arrow crosses the board's face, or null if it never gets there. */
    private fun landing(from: Vec3, v0: Vec3): Pair<Double, Double>? {
        var pos = from; var v = v0
        repeat(40) {
            val next = pos.add(v)
            if (pos.z < 50.0 && next.z >= 50.0 && v.z > 0) {
                val f = (50.0 - pos.z) / v.z
                return (pos.x + v.x * f) to (pos.y + v.y * f)
            }
            pos = next; v = Vec3(v.x * 0.99, v.y * 0.99 - 0.05, v.z * 0.99)
        }
        return null
    }

    private fun lookAt(eye: Vec3, at: Vec3): Pair<Float, Float> {
        val d = at.subtract(eye)
        return Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat() to (-Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)))).toFloat()
    }

    /**
     * Aiming a [bow] volley (from [pos], sneaking or not, with [stacks] Hydra stacks) so its main arrow lands on
     * [spot] on the board's face: the crosshair point (the arrows drop ~0.6 on the way, so it's higher) and the
     * cells the whole volley hits.
     */
    fun aim(bow: String, pos: Vec3, crouch: Boolean, stacks: Int, spot: Vec3): Pair<Vec3, Set<BlockPos>> {
        val eye = Vec3(pos.x, pos.y + if (crouch) 1.27 else 1.62, pos.z)
        var at = spot
        repeat(3) {
            val (yaw, pitch) = lookAt(eye, at)
            val main = ShotPlan.plan(bow, pos, yaw, pitch, crouch, stacks, Vec3.ZERO, 4).first()
            val (x, y) = landing(main.at, main.v) ?: return@repeat
            at = at.add(spot.x - x, spot.y - y, 0.0)
        }
        val (yaw, pitch) = lookAt(eye, at)
        val cells = ShotPlan.plan(bow, pos, yaw, pitch, crouch, stacks, Vec3.ZERO, 4).mapNotNull { cellHit(it.at, it.v) }.toSet()
        return at to cells
    }

    /** The crosshair spots worth trying: each cell's centre, and the middle between two side-by-side cells. */
    val SPOTS: List<Vec3> = CELLS.map { Vec3(it.x + 0.5, it.y + 0.5, 50.0) } + CELLS.filter { it.x < 68 }.map { Vec3(it.x + 1.5, it.y + 0.5, 50.0) }
}
