package com.devgineerclient.posepov

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two links through a real TURN server: each allocates, binds a channel to the other's relayed address, and a frame
 * crosses. Only with TURN_TEST=host:port:user:pass set (a local coturn, or Cloudflare with credentials from the relay).
 */
class TurnLinkLiveTest {
    @Test fun twoGamesSwapFramesThroughTurn() {
        val spec = System.getenv("TURN_TEST")
        assumeTrue(spec != null, "TURN_TEST isn't set")
        val (host, port, user, pass) = spec!!.split(":", limit = 4)
        val got = LinkedBlockingQueue<Triple<Int, Int, ByteArray>>()
        val a = TurnLink({ s, k, b -> got.add(Triple(s, k, b)) }, ::println, host, port.toInt())
        val b = TurnLink({ s, k, b -> got.add(Triple(s, k, b)) }, ::println, host, port.toInt())
        a.start(user, pass, 1); b.start(user, pass, 2)
        var addrA = ""; var addrB = ""
        fun tickUntil(ms: Long, done: () -> Boolean) {
            val until = System.currentTimeMillis() + ms
            while (!done() && System.currentTimeMillis() < until) { a.tick { addrA = it }; b.tick { addrB = it }; Thread.sleep(20) }
        }
        tickUntil(10_000) { addrA.isNotEmpty() && addrB.isNotEmpty() }
        assertTrue(addrA.isNotEmpty() && addrB.isNotEmpty(), "both allocated (${a.problem}, ${b.problem})")
        println("relayed: $addrA and $addrB")
        val key = "ab".repeat(32)
        a.add(2, addrB, key); b.add(1, addrA, key)
        tickUntil(10_000) { a.live(2) && b.live(1) && a.rttMs(2) > 0 && b.rttMs(1) > 0 }
        assertTrue(a.live(2) && b.live(1), "each hears the other")
        assertEquals(setOf(2), a.broadcast(9, byteArrayOf(1, 2, 3)))
        val f = got.poll(2, TimeUnit.SECONDS)
        assertNotNull(f, "the frame came")
        assertEquals(1, f.first); assertEquals(9, f.second); assertContentEquals(byteArrayOf(1, 2, 3), f.third)
        // A frame sealed with another key isn't read.
        b.add(1, addrA, "cd".repeat(32))
        a.broadcast(9, byteArrayOf(4))
        assertEquals(null, got.poll(500, TimeUnit.MILLISECONDS))
        println("round trips: ${a.rttMs(2)} ms, ${b.rttMs(1)} ms; server ${a.serverRttMs} ms")
        a.stop(); b.stop()
    }
}
