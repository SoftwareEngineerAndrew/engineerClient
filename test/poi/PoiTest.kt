package com.engineerclient.poi

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PointsOfInterestTest {
    private fun at(x: Double, y: Double, z: Double) = PointsOfInterest.at(x, y, z)

    @Test fun standingOnTheSpots() {
        // On each spot's ground (P3 Sim's arena), or just above it: edges count, as a player's feet still are on it there.
        assertSame(PointsOfInterest.EE2, at(61.0, 132.0, 139.0))
        assertSame(PointsOfInterest.EE2, at(57.0, 131.5, 138.5))   // the front's west end, a stair's low half
        assertSame(PointsOfInterest.EE2, at(61.5, 133.2, 142.5))   // jumping, by the levers
        assertSame(PointsOfInterest.EE3, at(2.0, 109.0, 105.0))
        assertSame(PointsOfInterest.EE3, at(3.0, 109.0, 98.0))      // the walkway's corner
        assertSame(PointsOfInterest.EE3, at(5.0, 109.0, 111.5))     // the walkway in
        assertSame(PointsOfInterest.EE3, at(8.5, 109.0, 120.0))
        assertSame(PointsOfInterest.EE3_HIGH, at(18.7, 121.5, 91.5))
        assertSame(PointsOfInterest.EE3_HIGH, at(18.7, 123.0, 99.5))
        assertSame(PointsOfInterest.EE4, at(54.0, 115.0, 48.0))
        assertSame(PointsOfInterest.EE4, at(54.0, 115.0, 50.0))
        assertSame(PointsOfInterest.EE4, at(51.5, 114.0, 51.5))     // a step down beside the door
    }

    @Test fun offTheGroundIsNot() {
        // Where recorded leaps went that isn't the spots' ground (people leap wrong), and spots that aren't early enters.
        assertNull(at(3.5, 109.0, 104.0))   // beside EE3's walkway: over the lava
        assertNull(at(6.0, 110.0, 110.0))   // the gap between EE3's walkways
        assertNull(at(64.0, 136.0, 139.0))  // up the side of S2's device
        assertNull(at(58.0, 132.0, 141.0))  // beside EE2's platform: a drop
        assertNull(at(16.0, 123.0, 94.0))   // off EE3 high's ledge
        assertNull(at(18.7, 129.5, 95.0))   // the ledge's top slab
        assertNull(at(41.0, 109.0, 123.0))  // the S2 safe spot: not an early enter
        assertNull(at(54.0, 115.0, 40.0))   // S4's corridor, not EE4's walkway
    }

    @Test fun p3SimsSpotsAreEarlyEnters() {
        // P3Plan.defaultEarlyEnters() and its default spots.
        assertSame(PointsOfInterest.EE2, at(60.6, 132.0, 139.0))
        assertSame(PointsOfInterest.EE3, at(1.9, 109.0, 104.6))
        assertSame(PointsOfInterest.EE3, at(2.0, 109.0, 102.0))
        assertSame(PointsOfInterest.EE4, at(54.5, 115.06, 50.5))
    }

    @Test fun theCoreIsNotEe4() {
        // Recore's core box starts at z 54; P3 Sim's recore spot is in it.
        assertNull(at(54.5, 115.0, 54.0))
        assertNull(at(54.4, 115.0, 57.6))
        assertSame(PointsOfInterest.EE4, at(54.5, 115.0, 53.99))
    }

    @Test fun ordinaryPlacesAreNot() {
        assertNull(at(110.5, 121.5, 93.5)) // Simon Says
        assertNull(at(108.0, 120.0, 94.0))
        assertNull(at(73.0, 115.0, 45.0))  // S4, by its terminals
        assertNull(at(1.0, 109.0, 78.0))   // S3's 4th terminal
        assertNull(at(60.0, 120.0, 126.0)) // S2's 2nd terminal
    }
}

class PoiWireTest {
    private fun body(f: ByteArray) = ByteBuffer.wrap(f, 1, f.size - 1).slice()

    @Test fun roundTrip() {
        val f = PoiWire.encode(7, PointsOfInterest.EE3.id, 4_260)
        assertEquals(PoiWire.KIND, f[0].toInt())
        val h = PoiWire.decode(body(f))!!
        assertEquals(7, h.seq); assertEquals(PointsOfInterest.EE3.id, h.poi); assertEquals(4_200L, h.heldMs)
    }

    @Test fun heldCapsAndSequenceWraps() {
        val h = PoiWire.decode(body(PoiWire.encode(256 + 3, 0, 999_999)))!!
        assertEquals(3, h.seq); assertEquals(0, h.poi); assertEquals(PoiWire.MAX_HELD_MS, h.heldMs)
        assertTrue(PoiWire.after(0, 255)); assertTrue(PoiWire.after(5, 4)); assertFalse(PoiWire.after(4, 5)); assertFalse(PoiWire.after(4, 4))
    }

    @Test fun onlyWhatAGameCouldSend() {
        assertNull(PoiWire.decode(ByteBuffer.wrap(byteArrayOf(1, 2))))
        assertNull(PoiWire.decode(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))))
        // A spot this game doesn't know (a newer one's): none.
        assertEquals(0, PoiWire.decode(ByteBuffer.wrap(byteArrayOf(1, 99, 10)))!!.poi)
    }
}

class PoiPresenceTest {
    private val ee3 = PointsOfInterest.EE3.id
    private val seen = PoiPresence.Source.SEEN

    @Test fun aFreshShareIsSure() {
        val p = PoiPresence()
        assertTrue(p.shared("a", 0, ee3, 2_000, 10_000))
        val w = p.sureAt("a", 10_100)!!
        assertEquals(ee3, w.poi); assertEquals(8_000, w.since); assertEquals(8_250, w.sureFrom)
        assertEquals(setOf(PoiPresence.Source.SHARED), w.sources)
        // Stale: unknown, not where they were.
        assertNull(p.sureAt("a", 10_000 + PoiPresence.SHARE_FRESH_MS + 1))
    }

    @Test fun anySourceSayingOtherwiseIsNo() {
        val p = PoiPresence()
        p.shared("a", 0, ee3, 2_000, 10_000)
        p.seen("a", 0, 10_000, seen) // their player, in view, isn't on it (or is in a leap's drag)
        assertNull(p.sureAt("a", 10_050))
        assertEquals(mapOf(PoiPresence.Source.SHARED to ee3, seen to 0), p.current("a", 10_050))
        // Once both have them on it, and have for long enough: yes, by both.
        for (t in 10_050L..10_400L step 50) { p.seen("a", ee3, t, seen); if (t % 250 == 0L) p.shared("a", (t / 250).toInt(), ee3, t - 8_000, t) }
        assertNull(p.sureAt("a", 10_250))
        val w = p.sureAt("a", 10_300)!!
        assertEquals(setOf(PoiPresence.Source.SHARED, seen), w.sources); assertEquals(8_000, w.since); assertEquals(10_300, w.sureFrom)
    }

    @Test fun onlyOnceSettled() {
        val p = PoiPresence()
        for (t in 0L..200L step 50) p.seen("a", ee3, t, seen)
        assertNull(p.sureAt("a", 200))
        p.seen("a", ee3, 250, seen)
        assertEquals(0, p.sureAt("a", 250)!!.since)
    }

    @Test fun outOfViewIsUnknownAndStartsAgain() {
        val p = PoiPresence()
        for (t in 0L..1_000L step 50) p.seen("a", ee3, t, seen)
        assertEquals(ee3, p.sureAt("a", 1_000)!!.poi)
        // No reading for a few ticks: not "still there".
        assertNull(p.sureAt("a", 1_000 + PoiPresence.SIGHT_FRESH_MS + 1))
        // Back in view on it: nothing says they stayed, so it settles again.
        p.seen("a", ee3, 1_300, seen)
        assertNull(p.sureAt("a", 1_300))
        for (t in 1_350L..1_550L step 50) p.seen("a", ee3, t, seen)
        assertEquals(1_300, p.sureAt("a", 1_550)!!.since)
    }

    @Test fun anOlderFrameIsDropped() {
        val p = PoiPresence()
        p.shared("a", 10, 0, 0, 1_000)
        // "On EE3" sent before "off it", come in after it: dropped.
        assertFalse(p.shared("a", 9, ee3, 3_000, 1_010))
        assertNull(p.sureAt("a", 1_300))
        // Across the wrap, and after a restart once the last is stale.
        p.shared("a", 255, 0, 0, 2_000)
        assertTrue(p.shared("a", 0, 0, 0, 2_250))
        assertTrue(p.shared("a", 200, ee3, 5_000, 2_250 + PoiPresence.SHARE_FRESH_MS + 1))
    }

    @Test fun theirOwnTimeOnItSaysWhenTheyGotBack() {
        val p = PoiPresence()
        p.shared("a", 0, ee3, 5_000, 10_000)
        // Stepped off and on between frames: their game says 0.1 s, so it settles again.
        p.shared("a", 1, ee3, 100, 10_250)
        assertNull(p.sureAt("a", 10_300))
        assertEquals(10_150, p.sureAt("a", 10_400)!!.since)
        // Past 25.5 s their time tops out: the first time stands.
        val q = PoiPresence()
        q.shared("b", 0, ee3, PoiWire.MAX_HELD_MS, 30_000)
        q.shared("b", 1, ee3, PoiWire.MAX_HELD_MS, 30_250)
        assertEquals(30_000 - PoiWire.MAX_HELD_MS, q.sureAt("b", 30_250)!!.since)
    }

    @Test fun noSpotIsNothingToHighlight() {
        val p = PoiPresence()
        for (t in 0L..500L step 50) p.seen("a", 0, t, seen)
        assertNull(p.sureAt("a", 500))
    }

    @Test fun forgotten() {
        val p = PoiPresence()
        p.shared("a", 0, ee3, 1_000, 1_000)
        p.forget("a")
        assertNull(p.sureAt("a", 1_000))
    }
}
