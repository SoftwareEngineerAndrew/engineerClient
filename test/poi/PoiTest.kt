package com.engineerclient.poi

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PointsOfInterestTest {
    private fun at(x: Double, y: Double, z: Double) = PointsOfInterest.at(x, y, z)

    @Test fun whereLeapsLandedAreEarlyEnters() {
        // The most common leap targets in Andrew's ADR recordings (2026-11-07), each block's centre.
        assertSame(PointsOfInterest.EE2, at(61.0, 132.0, 139.0))
        assertSame(PointsOfInterest.EE2, at(64.0, 136.0, 139.0))
        assertSame(PointsOfInterest.EE2, at(41.0, 109.0, 123.0))
        assertSame(PointsOfInterest.EE3, at(2.0, 109.0, 105.0))
        assertSame(PointsOfInterest.EE3, at(6.0, 110.0, 110.0))
        assertSame(PointsOfInterest.EE3, at(19.0, 122.0, 91.0))
        assertSame(PointsOfInterest.EE3, at(16.0, 123.0, 94.0))
        assertSame(PointsOfInterest.EE4, at(54.0, 115.0, 48.0))
        assertSame(PointsOfInterest.EE4, at(54.0, 115.0, 50.0))
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

    @Test fun callouts() {
        val yes = mapOf(
            "at ee3!" to PointsOfInterest.EE3, "at hee2!" to PointsOfInterest.EE2, "at high ee2!" to PointsOfInterest.EE2,
            "at ee2 safe spot!" to PointsOfInterest.EE2, "at split ee2!" to PointsOfInterest.EE2, "at 3 safespot!" to PointsOfInterest.EE3,
            "at early enter 3!" to PointsOfInterest.EE3, "at low ee3!" to PointsOfInterest.EE3, "at core!" to PointsOfInterest.EE4,
            "outside core" to PointsOfInterest.EE4, "At EE2!" to PointsOfInterest.EE2, ">>>  at ee2 ^_^!! <<<<" to PointsOfInterest.EE2,
            "at levers dev!" to PointsOfInterest.EE2, "ee4" to PointsOfInterest.EE4,
        )
        for ((m, p) in yes) assertSame(p, PointsOfInterest.callout(m), m)
        for (m in listOf("inside core", "in core", "in the core", "no ee2", "ee3?", "can't ee3", "melody 33%", "Leaped to p3wr!", "at ss!",
            "at 3 [s4]", "bers core", "free2play", "see3"))
            assertNull(PointsOfInterest.callout(m), m)
    }
}

class PoiWireTest {
    @Test fun roundTrip() {
        val f = PoiWire.encode(PointsOfInterest.EE3.id, 3, 4_260)
        assertEquals(PoiWire.KIND, f[0].toInt())
        val h = PoiWire.decode(ByteBuffer.wrap(f, 1, f.size - 1).slice())!!
        assertEquals(PointsOfInterest.EE3.id, h.poi); assertEquals(3, h.area); assertEquals(4_200L, h.heldMs)
    }

    @Test fun heldCapsAndNone() {
        val h = PoiWire.decode(ByteBuffer.wrap(PoiWire.encode(0, 0, 999_999), 1, 3).slice())!!
        assertEquals(0, h.poi); assertEquals(0, h.area); assertEquals(25_500L, h.heldMs)
    }

    @Test fun onlyWhatAGameCouldSend() {
        assertNull(PoiWire.decode(ByteBuffer.wrap(byteArrayOf(1, 2))))
        assertNull(PoiWire.decode(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))))
        assertNull(PoiWire.decode(ByteBuffer.wrap(byteArrayOf(1, 6, 0))))
        // A spot this game doesn't know (a newer one's): none, the area kept.
        val h = PoiWire.decode(ByteBuffer.wrap(byteArrayOf(99, 2, 10)))!!
        assertEquals(0, h.poi); assertEquals(2, h.area)
    }
}

class PoiPresenceTest {
    private val ee3 = PointsOfInterest.EE3.id

    @Test fun aFreshShareWins() {
        val p = PoiPresence()
        p.shared("a", ee3, 3, 2_000, 10_000)
        p.seen("a", 0, 2, 10_500, PoiPresence.Source.SEEN)
        val w = p.where("a", 11_000, 2)!!
        assertEquals(ee3, w.poi); assertEquals(PoiPresence.Source.SHARED, w.source); assertEquals(8_000, w.since)
        // Stale: the newer sighting stands.
        assertEquals(0, p.where("a", 10_000 + PoiPresence.SHARE_FRESH_MS + 1, 2)!!.poi)
    }

    @Test fun theSameSpotKeepsItsFirstTime() {
        val p = PoiPresence()
        p.shared("a", ee3, 3, 1_000, 10_000)
        p.shared("a", ee3, 3, 1_900, 11_000) // rounded down, and late
        assertEquals(9_000, p.where("a", 11_000, 2)!!.since)
        p.seen("b", ee3, 3, 5_000, PoiPresence.Source.SEEN)
        p.seen("b", ee3, 3, 6_000, PoiPresence.Source.POSE)
        assertEquals(5_000, p.where("b", 6_000, 2)!!.since)
    }

    @Test fun calloutsOnlyInTheirSectionAndOnlyWhenNewest() {
        val p = PoiPresence()
        p.seen("a", 0, 1, 1_000, PoiPresence.Source.SEEN)
        p.callout("a", ee3, 2_000, 2)
        assertEquals(PoiPresence.Source.CHAT, p.where("a", 3_000, 2)!!.source)
        // Not in another section.
        assertEquals(PoiPresence.Source.SEEN, p.where("a", 3_000, 3)!!.source)
        // Seen somewhere after it: that stands.
        p.seen("a", 0, 1, 2_500, PoiPresence.Source.SEEN)
        assertEquals(0, p.where("a", 3_000, 2)!!.poi)
    }

    @Test fun earliestSourceGivesTheTime() {
        val p = PoiPresence()
        p.callout("a", ee3, 1_000, 2)
        p.shared("a", ee3, 3, 500, 2_000)
        val w = p.where("a", 2_000, 2)!!
        assertEquals(PoiPresence.Source.SHARED, w.source); assertEquals(1_000, w.since)
    }

    @Test fun forgotten() {
        val p = PoiPresence()
        p.shared("a", ee3, 3, 0, 1_000)
        p.forget("a")
        assertNull(p.where("a", 1_000, 2))
    }
}
