package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.Gson
import net.minecraft.world.phys.Vec3

/**
 * The bots' fixed moves: each one the same way at the same speed every run (`docs/p3-roles.md`). Built in from your
 * best sim runs (`p3sim/legs.json`: every tick of the move as you made it, the fastest per pair of spots, the ones that
 * matched the usual way); your own recordings ([Moves]) come first. A move with neither falls back to [Routes].
 */
object Legs {
    class Leg(
        val from: String, val to: String, val fromPos: List<Double>, val toPos: List<Double>, val ticks: Int, val src: String,
        val frames: List<List<Double>>, val held: List<String?>,
    ) {
        val fromV get() = Vec3(fromPos[0], fromPos[1], fromPos[2])
        val toV get() = Vec3(toPos[0], toPos[1], toPos[2])
    }

    private class Saved(val legs: List<Leg>? = null)

    /** A leg's start this near the bot (it gets on in a straight line), its end this near where it's going. */
    const val FROM_NEAR = 3.5
    const val TO_NEAR = 2.0
    /** Getting on and off a leg, blocks a tick. */
    private const val JOIN_SPEED = 0.9

    val all: List<Leg> by lazy {
        runCatching {
            Legs::class.java.getResourceAsStream("/assets/engineerclient/p3sim/legs.json")!!.reader().use { Gson().fromJson(it, Saved::class.java).legs.orEmpty() }
        }.onFailure { EngineerClient.logger.warn("[p3sim] legs.json: {}", it.toString()) }.getOrDefault(emptyList())
    }

    /** The built-in leg from near [from] to near [to] (null: none). */
    fun find(from: Vec3, to: Vec3): Leg? = all.filter { it.toV.distanceTo(to) <= TO_NEAR && it.fromV.distanceTo(from) <= FROM_NEAR }
        .minByOrNull { it.fromV.distanceTo(from) + it.toV.distanceTo(to) }

    fun route(from: Vec3, to: Vec3): Routes.Route? = find(from, to)?.let { play(from, to, it.frames, it.held) }

    /**
     * [frames] (x, y, z, yaw, pitch a tick) as a route from [from] to [to]: on in a straight line from where the bot is,
     * along them a frame a tick, off in a straight line to the spot.
     */
    fun play(from: Vec3, to: Vec3, frames: List<List<Double>>, held: List<String?>?): Routes.Route {
        val k = frames.size + 2
        val x = DoubleArray(k); val y = DoubleArray(k); val z = DoubleArray(k); val t = DoubleArray(k)
        val yaw = FloatArray(k) { Float.NaN }; val pitch = FloatArray(k) { Float.NaN }; val h = arrayOfNulls<String>(k)
        x[0] = from.x; y[0] = from.y; z[0] = from.z
        val f0 = frames[0]
        val on = Math.sqrt((f0[0] - from.x).let { it * it } + (f0[1] - from.y).let { it * it } + (f0[2] - from.z).let { it * it }) / JOIN_SPEED
        for ((i, fr) in frames.withIndex()) {
            val q = i + 1
            x[q] = fr[0]; y[q] = fr[1]; z[q] = fr[2]; yaw[q] = fr[3].toFloat(); pitch[q] = fr[4].toFloat()
            h[q] = held?.getOrNull(i)
            t[q] = on + i
        }
        x[k - 1] = to.x; y[k - 1] = to.y; z[k - 1] = to.z
        t[k - 1] = t[k - 2] + Math.sqrt((to.x - x[k - 2]).let { it * it } + (to.y - y[k - 2]).let { it * it } + (to.z - z[k - 2]).let { it * it }) / JOIN_SPEED
        return Routes.Route(x, y, z, t, yaw, pitch, h)
    }
}
