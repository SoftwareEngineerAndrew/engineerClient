package com.engineerclient.pov

import java.util.UUID

/**
 * The time machine behind [PovPose]'s CUSTOM mode: a per-player ring of packet targets stamped
 * with wall-clock nanos, and the lerp that reads the pose back out at an arbitrary past instant.
 *
 * Deliberately free of Minecraft types so it unit-tests headlessly — [PovPose] is the thin layer
 * that reads the entity's fields into [sample] and writes [poseAt] back out.
 *
 * Thread confinement: the client thread samples and the same thread renders, so no locking.
 */
class PovInterpolator(
    /** Samples kept per player; 40 at 20 Hz is 2 s of history, far past the 6-tick maximum delay. */
    private val capacity: Int = 40,
    /** A player not sampled for this long is dropped entirely (left entity range, died, left party). */
    private val evictAfterNanos: Long = 5_000_000_000L,
) {

    /** One packet target as it stood at [nanos]. Rotations in degrees, [headYaw] is head yaw, not body. */
    data class Sample(
        val nanos: Long,
        val x: Double,
        val y: Double,
        val z: Double,
        val headYaw: Float,
        val pitch: Float,
    )

    /** What to render: a [Sample] with the timestamp dropped. */
    data class Pose(
        val x: Double,
        val y: Double,
        val z: Double,
        val headYaw: Float,
        val pitch: Float,
    )

    /** Oldest first. ArrayDeque so the oldest sample falls off the front in O(1). */
    private val rings = HashMap<UUID, ArrayDeque<Sample>>()

    /** Records where [id] was at [nanos]. Out-of-order or repeated stamps replace the newest sample. */
    fun sample(id: UUID, nanos: Long, x: Double, y: Double, z: Double, headYaw: Float, pitch: Float) {
        val ring = rings.getOrPut(id) { ArrayDeque(capacity) }
        val s = Sample(nanos, x, y, z, headYaw, pitch)
        val newest = ring.lastOrNull()
        if (newest != null && nanos <= newest.nanos) {
            ring[ring.size - 1] = s // clock did not advance: keep one sample per instant, newest wins
            return
        }
        ring.addLast(s)
        while (ring.size > capacity) ring.removeFirst()
    }

    fun sample(id: UUID, nanos: Long, pose: Pose) =
        sample(id, nanos, pose.x, pose.y, pose.z, pose.headYaw, pose.pitch)

    /**
     * The pose [id] held at [nanos], lerped between the two samples bracketing that instant.
     * Clamps to the oldest sample when [nanos] is older than the ring (the delay outran the
     * history) and to the newest when it is in the future (delay 0 lands here every frame).
     * Null only when nothing has ever been sampled for [id].
     */
    fun poseAt(id: UUID, nanos: Long): Pose? {
        val ring = rings[id] ?: return null
        if (ring.isEmpty()) return null
        val oldest = ring.first()
        if (nanos <= oldest.nanos) return pose(oldest)
        val newest = ring.last()
        if (nanos >= newest.nanos) return pose(newest)

        // Ring is small (<= capacity) and newest-biased, so walk back from the end.
        var i = ring.size - 1
        while (i > 0 && ring[i - 1].nanos > nanos) i--
        val a = ring[i - 1]
        val b = ring[i]
        val span = (b.nanos - a.nanos).toDouble()
        val t = if (span <= 0.0) 1.0 else ((nanos - a.nanos).toDouble() / span).coerceIn(0.0, 1.0)
        return Pose(
            x = lerp(t, a.x, b.x),
            y = lerp(t, a.y, b.y),
            z = lerp(t, a.z, b.z),
            headYaw = rotLerp(t.toFloat(), a.headYaw, b.headYaw),
            pitch = lerp(t, a.pitch.toDouble(), b.pitch.toDouble()).toFloat(),
        )
    }

    /** Drops every player whose newest sample is older than the eviction window. */
    fun evict(nowNanos: Long) {
        rings.entries.removeAll { (_, ring) ->
            val newest = ring.lastOrNull() ?: return@removeAll true
            nowNanos - newest.nanos > evictAfterNanos
        }
    }

    fun forget(id: UUID) { rings.remove(id) }

    fun clear() = rings.clear()

    /** Test/diagnostic view: how many samples are held for [id]. */
    fun size(id: UUID): Int = rings[id]?.size ?: 0

    fun tracked(): Int = rings.size

    private fun pose(s: Sample) = Pose(s.x, s.y, s.z, s.headYaw, s.pitch)

    companion object {
        fun lerp(t: Double, a: Double, b: Double) = a + (b - a) * t

        /** Degrees to (-180, 180]. */
        fun wrapDegrees(deg: Float): Float {
            var d = deg % 360.0f
            if (d >= 180.0f) d -= 360.0f
            if (d < -180.0f) d += 360.0f
            return d
        }

        /** Shortest-arc angle lerp: 350 -> 10 crosses 0, never 180. Result is wrapped. */
        fun rotLerp(t: Float, a: Float, b: Float): Float = wrapDegrees(a + wrapDegrees(b - a) * t)
    }
}
