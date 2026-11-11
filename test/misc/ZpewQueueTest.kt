package com.engineerclient.misc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZpewQueueTest {
    /** A click that moves you [dz] blocks along z (null: refused). */
    private fun queue() = ZpewQueue<Double?> { from, dz -> dz?.let { doubleArrayOf(from[0], from[1], from[2] + it) } }

    private val start = doubleArrayOf(0.5, 1.0, 0.5)
    private fun at(z: Double) = doubleArrayOf(0.5, 1.0, z)

    @Test fun chainOfClicksPutsTheCameraAtTheEnd() {
        val q = queue()
        q.click(10.0, 0, start); q.click(10.0, 1, at(0.5)); q.click(10.0, 2, at(0.5))
        assertContentEquals(at(30.5), q.camera)
        q.teleport(at(10.5), 6); assertContentEquals(at(30.5), q.camera)
        q.teleport(at(20.5), 7); assertContentEquals(at(30.5), q.camera)
        q.teleport(at(30.5), 8); assertNull(q.camera)
        assertEquals(0, q.size)
    }

    @Test fun aWrongGuessIsWorkedOutAgainFromTheRealLanding() {
        val q = queue()
        q.click(10.0, 0, start); q.click(10.0, 1, start); q.click(10.0, 2, start)
        // The first blink was cut short at 7.5: the camera follows on from there, not back to the player at 0.5.
        q.teleport(at(7.5), 6)
        assertContentEquals(at(27.5), q.camera)
        q.teleport(at(17.5), 7); q.teleport(at(27.5), 8)
        assertNull(q.camera)
    }

    @Test fun aCastTheServerDroppedIsSkipped() {
        val q = queue()
        q.click(10.0, 0, start); q.click(5.0, 1, start); q.click(3.0, 2, start)
        assertContentEquals(at(18.5), q.camera)
        // The server dropped the first cast: the teleport to 5.5 is the second click's, worked out from 0.5.
        q.teleport(at(5.5), 7)
        assertEquals(1, q.size)
        assertContentEquals(at(8.5), q.camera)
        q.teleport(at(8.5), 8)
        assertEquals(0, q.size); assertNull(q.camera)
    }

    @Test fun aClickNotExpectedIsLeftOutOfTheCameraButStillMatched() {
        val q = queue()
        q.click(10.0, 0, start)
        q.click(10.0, 1, start, expected = false)
        assertContentEquals(at(10.5), q.camera)
        q.teleport(at(10.5), 6); assertNull(q.camera); assertEquals(1, q.size)
        // The server cast it after all.
        q.teleport(at(20.5), 7); assertEquals(0, q.size)
    }

    @Test fun refusedClicksLeaveTheCameraAtThePlayerAndExpire() {
        val q = queue()
        q.click(null, 0, start)
        assertNull(q.camera)
        q.click(10.0, 5, start)
        assertContentEquals(at(10.5), q.camera)
        q.expire(20, 20)
        assertEquals(1, q.size)
        assertContentEquals(at(10.5), q.camera)
        q.expire(25, 20)
        assertEquals(0, q.size); assertNull(q.camera)
    }

    @Test fun aDroppedCastAmongIdenticalClicksExpiresSoonAfterTheRoundTrip() {
        val q = queue()
        // Spam the same look, 6 ticks of round trip; the server drops the middle cast.
        q.click(10.0, 0, start); q.click(10.0, 2, start); q.click(10.0, 4, start)
        q.teleport(at(10.5), 6)
        q.teleport(at(20.5), 10)  // the third click's, matched to the second: one phantom left, camera one blink ahead
        assertEquals(1, q.size)
        assertContentEquals(at(30.5), q.camera)
        // Well before the 20-tick timeout, once it is GRACE past the round trip, it lets go.
        q.expire(12, 20)
        assertEquals(1, q.size)
        q.expire(4 + kotlin.math.ceil(q.limit(20)).toLong(), 20)
        assertEquals(0, q.size); assertNull(q.camera)
    }

    @Test fun aLateTeleportStillMatchesItsExpiredClick() {
        val q = queue()
        q.click(1.0, 0, start); q.teleport(at(1.5), 2)  // a 2-tick round trip: clicks expire 6 ticks in
        q.click(10.0, 10, at(1.5)); q.click(5.0, 11, at(1.5))
        assertEquals(2, q.expire(17, 20))
        assertEquals(0, q.size); assertNull(q.camera)
        // ...but the first one's teleport turns up: it is that click's, not the one waiting now.
        q.click(3.0, 17, at(1.5))
        assertEquals(ZpewQueue.LATE, q.teleport(at(11.5), 18))
        assertEquals(1, q.size)
        assertContentEquals(at(14.5), q.camera)
    }

    @Test fun jitterWidensTheLimit() {
        val q = queue()
        var t = 0L
        for (wait in listOf(6L, 12L, 5L, 13L, 6L, 12L)) { q.click(1.0, t, at(0.5)); q.teleport(at(1.5), t + wait); q.teleport(at(0.5), t + wait); t += 20 }
        // A steady 6 would give 6 + 4; this connection swings 5-13, so the limit sits well above its mean.
        assert(q.limit(40) > q.roundTrip + ZpewQueue.GRACE) { "limit ${q.limit(40)} round trip ${q.roundTrip}" }
    }

    @Test fun aTeleportWithNothingWaitingIsIgnored() {
        val q = queue()
        q.teleport(at(5.5), 0)
        assertNull(q.camera); assertEquals(0, q.size)
    }
}
