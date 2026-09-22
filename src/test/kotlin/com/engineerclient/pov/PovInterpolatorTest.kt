package com.engineerclient.pov

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The POV previews' time machine, tested headlessly: given a ring of packet targets stamped with
 * nanos, does reading it back at an arbitrary past instant land on the right pose? The failures
 * this guards against are invisible in game (a head that lags a tick, or spins the long way round
 * through 180 when the player crosses north), so they have to be caught here.
 */
class PovInterpolatorTest {

    private val id: UUID = UUID.nameUUIDFromBytes("p3wr".toByteArray())
    private val other: UUID = UUID.nameUUIDFromBytes("teammate".toByteArray())

    private val tick = 50_000_000L // one client tick in nanos

    /** Samples at t = 0, 1, 2, ... ticks, walking +1 x per tick and +10 degrees of head yaw. */
    private fun ramp(n: Int, start: Long = 0L, yaw0: Float = 0f): PovInterpolator {
        val interp = PovInterpolator()
        repeat(n) { i ->
            interp.sample(id, start + i * tick, i.toDouble(), 70.0, -i.toDouble(), yaw0 + i * 10f, i.toFloat())
        }
        return interp
    }

    @Test
    fun `no samples means no pose`() {
        assertNull(PovInterpolator().poseAt(id, 0L))
    }

    @Test
    fun `exact sample times return that sample untouched`() {
        val interp = ramp(5)
        for (i in 0 until 5) {
            val pose = interp.poseAt(id, i * tick)!!
            assertEquals(i.toDouble(), pose.x, 1e-9, "x at sample $i")
            assertEquals(-i.toDouble(), pose.z, 1e-9, "z at sample $i")
            assertEquals(i * 10f, pose.headYaw, 1e-4f, "yaw at sample $i")
            assertEquals(i.toFloat(), pose.pitch, 1e-4f, "pitch at sample $i")
        }
    }

    @Test
    fun `midpoint between two samples is half way`() {
        val interp = ramp(3)
        val pose = interp.poseAt(id, tick + tick / 2)!!
        assertEquals(1.5, pose.x, 1e-9)
        assertEquals(70.0, pose.y, 1e-9)
        assertEquals(-1.5, pose.z, 1e-9)
        assertEquals(15f, pose.headYaw, 1e-3f)
        assertEquals(1.5f, pose.pitch, 1e-4f)
    }

    @Test
    fun `quarter point between two samples`() {
        val interp = ramp(3)
        val pose = interp.poseAt(id, tick / 4)!!
        assertEquals(0.25, pose.x, 1e-9)
        assertEquals(2.5f, pose.headYaw, 1e-3f)
    }

    @Test
    fun `a delay past the oldest sample clamps to the oldest`() {
        val interp = ramp(4, start = 1_000 * tick)
        val pose = interp.poseAt(id, 1_000 * tick - 10 * tick)!!
        assertEquals(0.0, pose.x, 1e-9)
        assertEquals(0f, pose.headYaw, 1e-4f)
    }

    @Test
    fun `zero delay gives the newest sample`() {
        val now = 500 * tick
        val interp = PovInterpolator()
        repeat(6) { i -> interp.sample(id, now - (5 - i) * tick, i.toDouble(), 70.0, 0.0, i * 10f, 0f) }
        val pose = interp.poseAt(id, now)!!
        assertEquals(5.0, pose.x, 1e-9)
        assertEquals(50f, pose.headYaw, 1e-4f)
        // A stamp in the future (render clock slightly ahead of the sample) also clamps to newest.
        assertEquals(5.0, interp.poseAt(id, now + 3 * tick)!!.x, 1e-9)
    }

    @Test
    fun `yaw wrap crosses zero, not 180`() {
        val interp = PovInterpolator()
        interp.sample(id, 0L, 0.0, 70.0, 0.0, 350f, 0f)
        interp.sample(id, tick, 0.0, 70.0, 0.0, 10f, 0f)
        val mid = interp.poseAt(id, tick / 2)!!.headYaw
        // 350 -> 10 the short way passes through 0/360, and never near 180.
        assertEquals(0f, PovInterpolator.wrapDegrees(mid), 1e-3f, "midpoint was $mid")
        val quarter = PovInterpolator.wrapDegrees(interp.poseAt(id, tick / 4)!!.headYaw)
        assertEquals(-5f, quarter, 1e-3f, "quarter was $quarter")
    }

    @Test
    fun `yaw wrap the other way round`() {
        val interp = PovInterpolator()
        interp.sample(id, 0L, 0.0, 70.0, 0.0, 10f, 0f)
        interp.sample(id, tick, 0.0, 70.0, 0.0, 350f, 0f)
        assertEquals(0f, PovInterpolator.wrapDegrees(interp.poseAt(id, tick / 2)!!.headYaw), 1e-3f)
        assertEquals(5f, PovInterpolator.wrapDegrees(interp.poseAt(id, tick / 4)!!.headYaw), 1e-3f)
    }

    @Test
    fun `rotLerp never takes the long way`() {
        for (a in 0 until 360 step 7) {
            for (b in 0 until 360 step 11) {
                val mid = PovInterpolator.rotLerp(0.5f, a.toFloat(), b.toFloat())
                val short = Math.abs(PovInterpolator.wrapDegrees(b.toFloat() - a.toFloat()))
                val stepA = Math.abs(PovInterpolator.wrapDegrees(mid - a))
                assertTrue(stepA <= short / 2f + 1e-3f, "a=$a b=$b mid=$mid")
            }
        }
    }

    @Test
    fun `the ring drops the oldest samples past its capacity`() {
        val interp = PovInterpolator(capacity = 8)
        repeat(30) { i -> interp.sample(id, i * tick, i.toDouble(), 70.0, 0.0, 0f, 0f) }
        assertEquals(8, interp.size(id))
        // Oldest kept is sample 22; anything older clamps to it.
        assertEquals(22.0, interp.poseAt(id, 0L)!!.x, 1e-9)
        assertEquals(29.0, interp.poseAt(id, 29 * tick)!!.x, 1e-9)
    }

    @Test
    fun `a repeated timestamp replaces the newest sample`() {
        val interp = PovInterpolator()
        interp.sample(id, tick, 1.0, 70.0, 0.0, 0f, 0f)
        interp.sample(id, tick, 2.0, 70.0, 0.0, 0f, 0f)
        assertEquals(1, interp.size(id))
        assertEquals(2.0, interp.poseAt(id, tick)!!.x, 1e-9)
    }

    @Test
    fun `players not seen for five seconds are evicted`() {
        val interp = PovInterpolator()
        val now = 10_000L * tick
        interp.sample(id, now, 1.0, 70.0, 0.0, 0f, 0f)
        interp.sample(other, now - 6_000_000_000L, 2.0, 70.0, 0.0, 0f, 0f)
        assertEquals(2, interp.tracked())

        interp.evict(now)
        assertEquals(1, interp.tracked())
        assertNull(interp.poseAt(other, now))
        assertEquals(1.0, interp.poseAt(id, now)!!.x, 1e-9)

        // Still tracked just inside the window, gone just outside it.
        interp.evict(now + 4_900_000_000L)
        assertEquals(1, interp.tracked())
        interp.evict(now + 5_100_000_000L)
        assertEquals(0, interp.tracked())
        assertNull(interp.poseAt(id, now))
    }

    @Test
    fun `forget and clear drop history`() {
        val interp = ramp(3)
        interp.sample(other, 0L, 0.0, 0.0, 0.0, 0f, 0f)
        interp.forget(other)
        assertNull(interp.poseAt(other, 0L))
        assertEquals(1, interp.tracked())
        interp.clear()
        assertEquals(0, interp.tracked())
    }
}
