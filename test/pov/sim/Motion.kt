package com.engineerclient.pov.sim

import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * How a teammate moves (seconds of simulated time): where they are when a tick is due ([pos], read once a tick, as their
 * game moves them) and where they look ([yaw], [pitch], read every frame, as the mouse moves it). [teleports]: the times
 * they jump instantly (an etherwarp, a leap), so a jump there isn't counted as a glitch.
 */
abstract class Motion(val name: String) {
    abstract fun pos(t: Double): Vec3
    abstract fun yaw(t: Double): Float
    abstract fun pitch(t: Double): Float
    open fun sneaking(t: Double) = false
    open fun item(t: Double) = 1
    open val teleports: List<Double> = emptyList()
}

private fun smooth(k: Double) = k.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
private fun tri(t: Double, period: Double): Double { val k = (t / period) % 1.0; return if (k < 0.5) k * 4 - 1 else 3 - k * 4 }
private val ORIGIN = Vec3(-120.0, 70.0, -120.0)

object Motions {
    /** Standing still, looking at one spot. */
    val STILL = object : Motion("still") {
        override fun pos(t: Double) = ORIGIN
        override fun yaw(t: Double) = 30f
        override fun pitch(t: Double) = 5f
    }

    /** Walking (4.3 blocks/s) in a straight line, the view swaying a little. */
    val WALK = object : Motion("walk") {
        override fun pos(t: Double) = ORIGIN.add(4.3 * t, 0.0, 0.0)
        override fun yaw(t: Double) = (-90 + 10 * sin(t * PI)).toFloat()
        override fun pitch(t: Double) = (8 * sin(t * 0.7)).toFloat()
    }

    /** Sprinting (5.6 blocks/s) while strafing hard left and right (a 1.2 s zigzag), the view sweeping ±60°. */
    val STRAFE = object : Motion("strafe") {
        override fun pos(t: Double) = ORIGIN.add(5.6 * t, 0.0, 2.0 * tri(t, 1.2))
        override fun yaw(t: Double) = (-90 + 60 * sin(t * 2 * PI)).toFloat()
        override fun pitch(t: Double) = (20 * sin(t * 1.3)).toFloat()
    }

    /** Standing, flicking 150° in 70 ms every 1.2 s (aiming, leaping): the look's delay shows. */
    val FLICKS = object : Motion("flicks") {
        override fun pos(t: Double) = ORIGIN
        override fun yaw(t: Double): Float { val n = floor(t / 1.2); val k = smooth((t - n * 1.2) / 0.07); return ((n + k) * 150 % 360 - 180).toFloat() }
        override fun pitch(t: Double): Float { val n = floor(t / 1.2); val k = smooth((t - n * 1.2) / 0.07); return (if (n.toInt() % 2 == 0) -20 + 45 * k else 25 - 45 * k).toFloat() }
    }

    /** Running a circle (11 blocks/s, radius 8: speed 500), looking where they go. */
    val CIRCLE = object : Motion("circle") {
        private val w = 11.0 / 8
        override fun pos(t: Double) = ORIGIN.add(8 * cos(w * t), 0.0, 8 * sin(w * t))
        override fun yaw(t: Double) = Math.toDegrees(w * t).toFloat()
        override fun pitch(t: Double) = 0f
    }

    /** Walking with an etherwarp of 12 blocks every 1.5 s, flicking to the next spot just before. */
    val ETHERWARP = object : Motion("etherwarp") {
        override fun pos(t: Double): Vec3 { val n = floor(t / 1.5); return ORIGIN.add(4.0 * t + 12 * n, 0.0, 0.0) }
        override fun yaw(t: Double): Float { val n = floor(t / 1.5); val k = smooth((t - n * 1.5 - 1.3) / 0.08); return (-90 + 40 * (if (n.toInt() % 2 == 0) k else 1 - k)).toFloat() }
        override fun pitch(t: Double) = 10f
        override val teleports = (1..200).map { it * 1.5 }
    }

    /** Sprint-jumping (5.6 blocks/s, a jump every 0.6 s). */
    val JUMP = object : Motion("jump") {
        override fun pos(t: Double): Vec3 { val k = (t % 0.6) / 0.6; return ORIGIN.add(5.6 * t, 1.25 * 4 * k * (1 - k), 0.0) }
        override fun yaw(t: Double) = -90f
        override fun pitch(t: Double) = (5 * sin(t)).toFloat()
    }

    /**
     * A P3 in a loop (20 s): run to a terminal, stand in it with small looks, flick round, leap (30 blocks), run a curve, sneak.
     */
    val P3 = object : Motion("p3 loop") {
        override fun pos(t: Double): Vec3 {
            val c = floor(t / 20); val k = t - c * 20
            val start = ORIGIN.add(0.0, 0.0, 60 * c)
            return when {
                k < 3 -> start.add(5.6 * k, 0.0, 0.0)
                k < 8 -> start.add(16.8, 0.0, 0.0)
                k < 10 -> start.add(16.8, 0.0, 0.0)
                k < 14 -> start.add(16.8, 0.0, 30.0).add(4 * sin((k - 10) * 1.2), 0.0, 5.6 * (k - 10) * 0.5)
                else -> start.add(16.8 + 4 * sin(4 * 1.2), 0.0, 41.2 + 1.3 * (k - 14))
            }
        }
        override fun yaw(t: Double): Float {
            val k = t % 20
            return when {
                k < 3 -> -90f
                k < 8 -> (-90 + 4 * sin(k * 3)).toFloat()
                k < 10 -> (-90 + 170 * smooth((k - 8) / 0.15)).toFloat()
                // Round (from 80) and, over the last second, back to -90 for the next loop: no instant turns.
                k < 19 -> (80 + 30 * sin(k - 10)).toFloat()
                else -> (80 + 30 * sin(9.0) - (170 + 30 * sin(9.0)) * smooth(k - 19)).toFloat()
            }
        }
        override fun pitch(t: Double) = (if (t % 20 in 3.0..8.0) 30 + 3 * sin(t * 5) else 0.0).toFloat()
        override fun sneaking(t: Double) = t % 20 >= 14
        override fun item(t: Double) = if (t % 20 < 8) 1 else 2
        override val teleports = (0..60).map { it * 20 + 10.0 } + (0..60).map { it * 20.0 }
    }

    val ALL = listOf(STILL, WALK, STRAFE, FLICKS, CIRCLE, ETHERWARP, JUMP, P3)

    /** Whether a jump in the truth near [t] (within [within] s before) is a teleport. */
    fun teleportNear(m: Motion, t: Double, within: Double) = m.teleports.any { it <= t + 0.05 && t - it < within }

    fun angle(a: Float, b: Float): Float { var d = (a - b) % 360f; if (d > 180) d -= 360; if (d < -180) d += 360; return abs(d) }
}
