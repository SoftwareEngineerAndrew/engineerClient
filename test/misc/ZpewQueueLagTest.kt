package com.engineerclient.misc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ZpewQueueLagTest {
    /** Each click moves 10 blocks east. */
    private fun queue() = ZpewQueue<Unit> { from, _ -> doubleArrayOf(from[0] + 10, from[1], from[2]) }

    @Test
    fun cameraHoldsWhileNoServerTicksArrive() {
        val q = queue()
        q.click(Unit, 0, doubleArrayOf(0.0, 70.0, 0.0), true, wall = 0)
        // the server freezes: 100 client ticks pass, no server ticks
        q.expire(0, 100)
        assertContentEquals(doubleArrayOf(10.0, 70.0, 0.0), assertNotNull(q.camera))
        q.teleport(doubleArrayOf(10.0, 70.0, 0.0), 2)
        assertNull(q.camera)
    }

    @Test
    fun droppedClickStillExpiresOnServerTicks() {
        val q = queue()
        q.click(Unit, 0, doubleArrayOf(0.0, 70.0, 0.0), true, wall = 0)
        q.expire(25, 25)
        assertNull(q.camera)
    }

    @Test
    fun hardCapWhenTheServerNeverAnswers() {
        val q = queue()
        q.click(Unit, 0, doubleArrayOf(0.0, 70.0, 0.0), true, wall = 0)
        q.expire(0, 200)
        assertNull(q.camera)
    }

    @Test
    fun matchesOnlyAWaitingLanding() {
        val q = queue()
        q.click(Unit, 0, doubleArrayOf(0.0, 70.0, 0.0), true, wall = 0)
        // a server sync packet near where you stand is no click's landing
        assertFalse(q.matches(doubleArrayOf(0.4, 70.0, 0.0)))
        assertTrue(q.matches(doubleArrayOf(10.0, 70.0, 0.0)))
    }
}
