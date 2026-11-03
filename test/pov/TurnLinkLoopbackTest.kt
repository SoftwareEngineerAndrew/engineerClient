package com.engineerclient.pov

import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real [TurnLink] (sockets, threads, its timers) between two games through [FakeTurnServer] on loopback, under the
 * network going wrong in real time: delay, loss, reordering, an outage. Each check takes a few seconds.
 */
class TurnLinkLoopbackTest {
    private class Pair2(val server: FakeTurnServer) : AutoCloseable {
        val gotA = ConcurrentLinkedQueue<ByteArray>(); val gotB = ConcurrentLinkedQueue<ByteArray>()
        val a = TurnLink({ _, k, b -> if (k == 9) gotA.add(b) }, {}, "127.0.0.1", server.port)
        val b = TurnLink({ _, k, b -> if (k == 9) gotB.add(b) }, {}, "127.0.0.1", server.port)
        var addrA = ""; var addrB = ""
        @Volatile var ticking = true
        private val ticker = Thread {
            while (ticking) { a.tick { addrA = it }; b.tick { addrB = it }; Thread.sleep(20) }
        }.apply { isDaemon = true }

        init {
            a.start("user", "pass", 1); b.start("user", "pass", 2)
            ticker.start()
            until(5000) { addrA.isNotEmpty() && addrB.isNotEmpty() }
            assertTrue(addrA.isNotEmpty() && addrB.isNotEmpty(), "allocated (${a.problem}, ${b.problem})")
            val key = "5a".repeat(32)
            a.add(2, addrB, key); b.add(1, addrA, key)
            until(5000) { a.live(2) && b.live(1) }
            assertTrue(a.live(2) && b.live(1), "linked")
        }

        fun until(ms: Long, done: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (!done() && System.currentTimeMillis() < end) Thread.sleep(10) }

        /** Sends [n] frames from a to b, [gapMs] apart (a 60 fps look stream); how many came, in order of their numbers. */
        fun stream(n: Int, gapMs: Long): List<Int> {
            gotB.clear()
            for (i in 0 until n) { a.broadcast(9, java.nio.ByteBuffer.allocate(4).putInt(i).array()); Thread.sleep(gapMs) }
            Thread.sleep(400)
            return gotB.map { java.nio.ByteBuffer.wrap(it).int }
        }

        override fun close() { ticking = false; a.stop(); b.stop(); server.close() }
    }

    @Test fun cleanLinkCarriesEverythingInOrder() {
        Pair2(FakeTurnServer()).use { p ->
            assertEquals(1, p.server.challenged.coerceAtMost(1), "the 401 challenge was answered")
            val got = p.stream(120, 5)
            assertEquals((0 until 120).toList(), got)
        }
    }

    @Test fun roundTripIsMeasured() {
        val server = FakeTurnServer().apply { impair = { 40.0 } }
        Pair2(server).use { p ->
            p.until(6000) { p.a.rttMs(2) in 70.0..120.0 && p.b.rttMs(1) in 70.0..120.0 }
            assertTrue(p.a.rttMs(2) in 70.0..120.0, "a's round trip: ${p.a.rttMs(2)} (40 ms each way)")
            assertTrue(p.b.rttMs(1) in 70.0..120.0, "b's round trip: ${p.b.rttMs(1)}")
        }
    }

    @Test fun lossIsCountedAndTheLinkStaysUp() {
        val server = FakeTurnServer(seed = 7)
        Pair2(server).use { p ->
            server.impair = { r -> if (r.nextDouble() < 0.2) null else 2.0 }
            val got = p.stream(300, 4)
            assertTrue(got.size in 180..280, "about 80% came: ${got.size}")
            assertEquals(got.sorted(), got, "in order")
            assertTrue(p.b.peer(1)!!.lost > 0, "the gaps were counted")
            assertTrue(p.a.live(2) && p.b.live(1), "still live")
        }
    }

    @Test fun reorderedFramesAreDroppedNotShownOutOfOrder() {
        val server = FakeTurnServer(seed = 3)
        Pair2(server).use { p ->
            // One in ten held back 15 ms (behind the next few).
            server.fifo = false
            server.impair = { r -> if (r.nextDouble() < 0.1) 15.0 else 1.0 }
            val got = p.stream(300, 3)
            assertEquals(got.sorted(), got, "never out of order")
            assertTrue(got.size in 230..299, "the late ones are dropped: ${got.size} of 300")
        }
    }

    @Test fun anOutageEndsTheLinkAndItComesBack() {
        val server = FakeTurnServer(seed = 5)
        Pair2(server).use { p ->
            server.impair = { null }
            val t0 = System.currentTimeMillis()
            p.until(4000) { !p.a.live(2) }
            val down = System.currentTimeMillis() - t0
            assertTrue(!p.a.live(2), "not live during the outage")
            assertTrue(down in 2000..3500, "gone after its 2.5 s: $down ms")
            assertEquals(emptySet(), p.a.broadcast(9, byteArrayOf(1)), "nothing is sent to a teammate that isn't live")
            server.impair = { 1.0 }
            val t1 = System.currentTimeMillis()
            p.until(3000) { p.a.live(2) && p.b.live(1) }
            val back = System.currentTimeMillis() - t1
            assertTrue(p.a.live(2) && p.b.live(1), "live again")
            assertTrue(back < 1000, "back within a second (pings every 200 ms while not live): $back ms")
            assertEquals((0 until 50).toList(), p.stream(50, 5))
        }
    }

    @Test fun jitterBurstsKeepTheLinkLive() {
        val server = FakeTurnServer(seed = 9)
        val sent = AtomicLong()
        Pair2(server).use { p ->
            // Every so often a 400 ms stall (a busy Wi-Fi: what's behind it waits, then comes at once), otherwise 5-25 ms.
            server.impair = { r -> sent.incrementAndGet(); if (sent.get() % 200 == 0L) 400.0 else 5.0 + r.nextDouble() * 20 }
            val got = p.stream(400, 5)
            assertEquals(got.sorted(), got)
            assertTrue(got.size >= 390, "all but a few came: ${got.size} (server relayed ${server.relayedFrames}, dropped ${server.droppedFrames}, b counted lost ${p.b.peer(1)?.lost}, first missing ${(0 until 400).firstOrNull { it !in got }}, last ${got.lastOrNull()})")
            assertTrue(p.a.live(2) && p.b.live(1))
        }
    }
}
