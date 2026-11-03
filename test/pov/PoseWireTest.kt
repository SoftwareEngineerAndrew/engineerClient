package com.engineerclient.pov

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** POV Previews' frames byte for byte, the rules for when they go, the route choice, and the receiving track's basics. */
class PoseWireTest {
    private fun near(a: Double, b: Double, tol: Double) = assertTrue(abs(a - b) <= tol, "$a vs $b")
    private fun angle(a: Float, b: Float): Float { var d = (a - b) % 360f; if (d > 180) d -= 360; if (d < -180) d += 360; return abs(d) }

    @Test fun tickRoundTrip() {
        for (item in listOf(null, 0, 1234, 65535)) {
            val body = PoseWire.tickBody(54321, -123.456, 71.5, 12345.25, 179.9f, -45.5f, 2, item)
            assertEquals(if (item == null) 16 else 18, body.size)
            val h = assertNotNull(PoseWire.readTick(body))
            assertEquals(54321L, h.raw)
            near(h.pos!!.x, -123.456, 1 / 256.0); near(h.pos.y, 71.5, 1 / 256.0); near(h.pos.z, 12345.25, 1 / 256.0)
            assertTrue(angle(h.yaw, 179.9f) < 0.01f); near(h.pitch.toDouble(), -45.5, 0.01)
            assertEquals(2, h.flags)
            assertEquals(item ?: -1, h.item)
        }
        // Not a tick: wrong sizes, or 18 bytes without the item flag.
        assertNull(PoseWire.readTick(ByteArray(15)))
        assertNull(PoseWire.readTick(ByteArray(17)))
        assertNull(PoseWire.readTick(PoseWire.tickBody(1, 0.0, 0.0, 0.0, 0f, 0f, 0, null) + byteArrayOf(0, 0)))
        // Flags past swung, sneaking and the item: not a tick (an older game's rooms sent as kind 9).
        assertNull(PoseWire.readTick(PoseWire.tickBody(1, 0.0, 64.0, 0.0, 0f, 0f, 4, null)))
        // Rooms go over TURN as their own kind, never as a tick's.
        assertEquals(PoseWire.D_ROOMS, PoseWire.turnKind(9)); assertEquals(3, PoseWire.turnKind(3))
        assertTrue(setOf(PoseWire.D_TICK, PoseWire.D_LOOK, PoseWire.D_ROUTE, PoseWire.D_ROOMS, TurnLink.PING, TurnLink.PONG, TurnLink.MISSING, 3, 4, 7, 8).size == 11, "every TURN kind its own number")
        // Positions past the 24-bit range are held at its ends, never wrapped to the other side of the world.
        val far = PoseWire.readTick(PoseWire.tickBody(1, 1e6, -1e6, 0.0, 0f, 0f, 0, null))!!
        assertTrue(far.pos!!.x > 32000 && far.pos.y < -32000)
    }

    @Test fun lookAndRelayRoundTrip() {
        val look = PoseWire.look(-170.25f, 89.9f)
        val h = assertNotNull(PoseWire.readLook(PoseWire.lookBody(65535, look)))
        assertEquals(65535L, h.raw); assertNull(h.pos)
        assertTrue(angle(h.yaw, -170.25f) < 0.01f); near(h.pitch.toDouble(), 89.9, 0.01)
        assertNull(PoseWire.readLook(ByteArray(5)))

        val own = PoseWire.relay(1, -1, 0, 70000, 10.5, 64.0, -3.25, 90f, 10f, 3, 61.0, 42)
        assertEquals(20, own.size)
        val r = PoseWire.readRelay(ByteBuffer.wrap(own, 1, 19))
        assertEquals(70000L and 0xFFFF, r.raw); near(r.pos!!.x, 10.5, 1 / 256.0); near(r.pos.z, -3.25, 1 / 256.0)
        assertEquals(3, r.flags); assertEquals(42, r.item); assertEquals((61 / 4) * 2, r.halfMs)
        val seen = PoseWire.relay(2, 3, 9, 5, 0.0, 0.0, 0.0, 0f, 0f, 0, -1.0, 0)
        assertEquals(22, seen.size); assertEquals(3, seen[1].toInt()); assertEquals(9, seen[2].toInt())
    }

    @Test fun ticksGoWhenTheyChangeOnceMoreWhenTheyStopAndSlowlyWhileStill() {
        val s = PoseWire.Sender()
        val tick = 50_000_000L
        fun at(n: Int, x: Double, item: Int = 1) = s.tick(n * tick, n * 50, x, 64.0, 0.0, 0f, 0f, 0, item)
        assertEquals(1, at(0, 0.0)?.size)                 // the first
        assertEquals(1, at(1, 0.0)?.size)                 // the settled pose, once (the move "stopped")
        assertNull(at(2, 0.0))                            // still: not every tick
        assertNull(at(3, 0.0))
        assertEquals(1, at(4, 0.0)?.size)                 // but every 100 ms
        assertNull(at(5, 0.0))
        // Moving off after a still stretch: the still pose again first (with its own time), then the move.
        val off = at(7, 1.0)!!
        assertEquals(2, off.size)
        near(PoseWire.readTick(off[0])!!.pos!!.x, 0.0, 0.01); near(PoseWire.readTick(off[1])!!.pos!!.x, 1.0, 0.01)
        assertEquals(1, at(8, 2.0)?.size)
        // The held item rides along when it changes (and once a second), not every tick.
        assertEquals(-1, PoseWire.readTick(at(9, 3.0)!!.last())!!.item)
        assertEquals(7, PoseWire.readTick(at(10, 4.0, item = 7)!!.last())!!.item)
        assertEquals(-1, PoseWire.readTick(at(11, 5.0, item = 7)!!.last())!!.item)
        assertEquals(7, PoseWire.readTick(at(31, 6.0, item = 7)!!.last())!!.item)
    }

    @Test fun looksGoAtMostSharingFpsAndSkipTinyOnes() {
        val s = PoseWire.Sender()
        val frame = 16_666_666L
        var now = 1_000_000_000L
        assertEquals(1, s.look(now, 0, 10f, 0f, frame, true)?.size)
        // Too soon after the last: nothing at all (not even a check).
        assertNull(s.look(now + 5_000_000L, 5, 20f, 0f, frame, true))
        now += frame
        // Under 0.05°: skipped with Skip tiny look changes, sent without.
        assertEquals(0, s.look(now, 17, 10.02f, 0f, frame, true)?.size)
        now += frame
        // A turn after a still stretch: the last look again (with its own time) first.
        val turn = s.look(now, 34, 30f, 0f, frame, true)!!
        assertEquals(2, turn.size)
        assertTrue(angle(PoseWire.readLook(turn[0])!!.yaw, 10f) < 0.01f)
        assertEquals(17L, PoseWire.readLook(turn[0])!!.raw)
        now += frame
        assertEquals(1, s.look(now, 51, 40f, 0f, frame, true)?.size)
        val t = PoseWire.Sender()
        assertEquals(1, t.look(now, 0, 10f, 0f, frame, false)?.size)
        assertEquals(1, t.look(now + frame, 17, 10.02f, 0f, frame, false)?.size)
        assertEquals(1, s.looksSkipped)
    }

    @Test fun relayFramesFollowTheSameRules() {
        val s = PoseWire.Sender()
        val tick = 50_000_000L
        fun f(n: Int, x: Double) = PoseWire.relay(1, -1, 0, n, x, 64.0, 0.0, 0f, 0f, 0, 40.0, 1)
        assertEquals(1, s.relayFrames(f(0, 0.0), 0, true).size)
        assertEquals(0, s.relayFrames(f(1, 0.0), tick, true).size)
        assertEquals(1, s.relayFrames(f(3, 0.0), 3 * tick, true).size)      // still: every 100 ms
        assertEquals(0, s.relayFrames(f(4, 0.0), 4 * tick, true).size)
        assertEquals(2, s.relayFrames(f(5, 1.0), 5 * tick, true).size)      // moving off: the still pose first
        // Not needed (TURN reaches them): nothing goes, but it still counts.
        assertEquals(0, s.relayFrames(f(6, 2.0), 6 * tick, false).size)
        assertEquals(1, s.relayFrames(f(7, 3.0), 7 * tick, true).size)
    }

    @Test fun routeChoiceHasHysteresis() {
        val long = PoseWire.ROUTE_DWELL_MS
        // Not live: the relay, whatever was chosen.
        assertFalse(PoseWire.viaTurn(true, false, true))
        // Live, not optimizing: TURN, whatever was chosen.
        assertTrue(PoseWire.viaTurn(false, true, false))
        // Live and optimizing: the choice, and TURN until there is one.
        assertFalse(PoseWire.viaTurn(false, true, true))
        assertTrue(PoseWire.viaTurn(null, true, true))
        val hold = PoseWire.ROUTE_HOLD_MS
        // Not measured: no choice. First measured: at once.
        val r = PoseWire.Route()
        assertNull(r.update(0, -1.0, 40.0))
        assertEquals(true, r.update(1000, 30.0, 40.0))
        // On TURN, it stays until it's 5 ms (or a tenth) slower than the relay, for a while, and the dwell has passed.
        var t = 1000 + long
        assertEquals(true, r.update(t, 45.0, 40.0))
        assertEquals(true, r.update(t, 45.5, 40.0))              // slower, but only just now
        assertEquals(true, r.update(t + hold - 1, 45.5, 40.0))
        assertEquals(true, r.update(t + hold, 44.0, 40.0))       // back within the margin: starts over
        assertEquals(true, r.update(t + hold + 10, 46.0, 40.0))
        assertEquals(false, r.update(t + 2 * hold + 10, 46.0, 40.0))
        // On the relay, it goes back once TURN is 5 ms quicker for a while (no dwell for that).
        t += 2 * hold + 10
        assertEquals(false, r.update(t + 1, 36.0, 40.0))
        assertEquals(false, r.update(t + 2, 34.0, 40.0))
        assertEquals(true, r.update(t + 2 + hold, 34.0, 40.0))
        // Off TURN again: not within the dwell, however clear.
        t += 2 + hold
        assertEquals(true, r.update(t + 1, 200.0, 40.0))
        assertEquals(true, r.update(t + long - 1, 200.0, 40.0))
        assertEquals(false, r.update(t + long, 200.0, 40.0))
        assertEquals(3, r.switches)
        // A tenth on a slow link.
        val s = PoseWire.Route(); s.update(0, 100.0, 100.0)
        assertEquals(true, s.update(long, 109.0, 100.0)); assertEquals(true, s.update(long + hold, 109.0, 100.0))
        s.update(long + hold + 1, 111.0, 100.0); assertEquals(false, s.update(long + 2 * hold + 1, 111.0, 100.0))
    }

    @Test fun turnReplayWindowTakesLatePosesOnceAndNeverReplays() {
        val p = TurnLink.Peer(1, java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 1), javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES"))
        for (c in listOf(1L, 2L, 3L, 5L, 6L)) { assertTrue(p.fresh(c, false)); p.mark(c) }
        assertEquals(1L, p.lost)
        // 4 came late: only where late is fine, and only once (then it isn't lost after all).
        assertFalse(p.fresh(4, false))
        assertTrue(p.fresh(4, true)); p.mark(4)
        assertEquals(0L, p.lost)
        assertFalse(p.fresh(4, true))
        // Anything heard already, or too far back to tell: never.
        for (c in 1L..6L) assertFalse(p.fresh(c, true))
        p.mark(200)
        assertFalse(p.fresh(130, true)); assertTrue(p.fresh(140, true)); assertFalse(p.fresh(200, true)); assertTrue(p.fresh(201, false))
    }

    @Test fun trackTakesTheRelayAtOnceWhenTheyLeaveTurnAndTurnOnAPosition() {
        val t = PoseTrack()
        val base = 5_000_000_000L
        for (n in 0..5) t.heard(null, 1, base + n * 50_000_000L, true, true, (n * 50L), Vec3(n.toDouble(), 64.0, 0.0), 0f, 0f, 0, 1, 15)
        val now = base + 5 * 50_000_000L + 10_000_000L
        // Without word, the relay waits for TURN to go stale...
        assertFalse(t.heard(null, 1, now, false, false, 100, Vec3(5.0, 64.0, 0.0), 0f, 0f, 0, 1, 0))
        // ...told, it's taken at once, with a tick's buffer.
        t.turnLeft(now)
        assertTrue(t.heard(null, 1, now + 1_000_000L, false, false, 101, Vec3(5.0, 64.0, 0.0), 0f, 0f, 0, 1, 0))
        assertFalse(t.timed); assertTrue(t.buffer >= 60_000_000L, "a tick's buffer: ${t.buffer}")
        assertNotNull(t.drawn(now + 2_000_000L))
        // A late TURN frame just after: not taken back.
        assertFalse(t.heard(null, 1, now + 20_000_000L, true, true, 300, Vec3(5.0, 64.0, 0.0), 0f, 0f, 0, 1, 15))
        // Back to TURN later: not on a look (nothing to draw them at), on a tick.
        val later = now + 1_000_000_000L
        t.heard(null, 1, later - 40_000_000L, false, false, 120, Vec3(6.0, 64.0, 0.0), 0f, 0f, 0, 1, 0)
        assertFalse(t.heard(null, 1, later, true, true, 2000, null, 10f, 0f, 0, -1, 15))
        assertNotNull(t.drawn(later))
        assertTrue(t.heard(null, 1, later + 5_000_000L, true, true, 2005, Vec3(6.0, 64.0, 0.0), 0f, 0f, 0, 1, 15))
        assertTrue(t.timed)
        assertNotNull(t.drawn(later + 6_000_000L))
    }

    @Test fun turnCountersUnwrapAndRefuseOldOnes() {
        assertEquals(5L, TurnLink.counter(4, 5))
        // Past 24 bits: the next one, not 16 million back.
        assertEquals(0x1000001L, TurnLink.counter(0xFFFFFF, 1))
        assertEquals(0x2000003L, TurnLink.counter(0x1FFFFF0, 3))
        // A late one (reordered): before the last heard, so it is dropped (<= heard).
        assertTrue(TurnLink.counter(0x1000005, 0xFFFFFE) <= 0x1000005)
        assertTrue(TurnLink.counter(100, 99) <= 100)
    }

    /**
     * The relay allows a game 100 frames a second (adr src/pose.js RATE). The most one can send it: its own pose every
     * tick (20), each of up to three teammates it sees who don't share (20 each, a party of five with one other sharer:
     * without one, nobody is linked and nothing is sent), a terminal while clicking (each change, at most a few a second,
     * and once a second), rooms (one every 1.1 s), and pings (one every 2 s).
     */
    @Test fun relayRateBudget() {
        val s = PoseWire.Sender()
        var own = 0
        for (n in 0 until 200) own += s.relayFrames(PoseWire.relay(1, -1, 0, n, n * 0.25, 64.0, 0.0, 0f, 0f, 0, 40.0, 1), n * 50_000_000L, true).size
        val perSecond = own / 10.0 + 3 * 20 + 6 + 1 / 1.1 + 0.5
        assertTrue(own / 10.0 <= 20.0, "own: ${own / 10.0}/s")
        assertTrue(perSecond < 100, "worst case: $perSecond frames/s")
    }

    @Test fun trackDrawsBetweenTicksAndGoesStale() {
        val t = PoseTrack()
        val base = 5_000_000_000L
        // Ticks over TURN: a block a tick along x, each arriving 30 ms after it ran.
        for (n in 0..10) t.heard(null, 1, base + n * 50_000_000L + 30_000_000L, true, true, (n * 50L) and 0xFFFF, Vec3(n.toDouble(), 64.0, 0.0), 0f, 0f, 0, 1, 15)
        val d = assertNotNull(t.drawn(base + 10 * 50_000_000L + 40_000_000L))
        assertTrue(d.timed && d.direct)
        assertTrue(d.pos.x in 7.0..10.5, "drawn at ${d.pos.x}")
        // Nothing for 300 ms: not drawn (an old pose would pass for where they are).
        assertNull(t.drawn(base + 10 * 50_000_000L + 30_000_000L + PoseTrack.STALE_NS + 1))
    }

    @Test fun relayTicksWaitWhileTurnIsFresh() {
        val t = PoseTrack()
        val base = 5_000_000_000L
        assertTrue(t.heard(null, 1, base, true, true, 100, Vec3(0.0, 64.0, 0.0), 0f, 0f, 0, 1, 0))
        // The relay's tick of the same: skipped while TURN's is fresh...
        assertFalse(t.heard(null, 1, base + 10_000_000L, false, false, 2, Vec3(0.0, 64.0, 0.0), 0f, 0f, 0, 1, 0))
        // ...and taken once TURN has gone quiet.
        assertTrue(t.heard(null, 1, base + PoseTrack.STALE_NS + 20_000_000L, false, false, 9, Vec3(1.0, 64.0, 0.0), 0f, 0f, 0, 1, 0))
        assertFalse(t.timed)
        // Another source (someone else seeing them) only once this one is quiet.
        assertFalse(t.heard(null, 2, base + PoseTrack.STALE_NS + 30_000_000L, false, false, 1, Vec3(5.0, 64.0, 0.0), 0f, 0f, 0, 1, 0))
    }
}
