package com.engineerclient.recorder

import com.google.gson.JsonParser
import net.minecraft.network.ConnectionProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure parts of the wire tap: the between-worlds cache, phase ids, and command redaction. */
class WireTapTest {

    private fun line(seq: Long, body: String = "\"p\":\"x\"", type: String = "minecraft:x") =
        ConfigCache.Line(seq, 0, 0, 1000 + seq, 0, "config", type, body)

    @Test
    fun `the cache hands back everything in arrival order and is empty afterwards`() {
        val c = ConfigCache(1 shl 20)
        c.add(line(1)); c.add(ConfigCache.Raw(2, 1002, 0, 3, byteArrayOf(1, 2, 3), false, 3)); c.add(line(3))
        val t = c.take()
        assertEquals(listOf(1L, 2L, 3L), t.entries.map { it.seq })
        assertNull(t.dropped)
        assertEquals(0, c.size())
        assertEquals(0L, c.bytes())
    }

    @Test
    fun `past the cap the oldest go first and are counted`() {
        val body = "x".repeat(1000)
        val one = line(0, body).cost
        val c = ConfigCache(one * 3)
        for (i in 1L..5L) c.add(line(i, body, if (i % 2 == 0L) "minecraft:even" else "minecraft:odd"))
        val t = c.take()
        assertEquals(listOf(3L, 4L, 5L), t.entries.map { it.seq })
        val d = assertNotNull(t.dropped)
        assertEquals(2, d.lines)
        assertEquals(1L, d.seqA); assertEquals(2L, d.seqB)
        assertEquals(mapOf("minecraft:odd" to 1L, "minecraft:even" to 1L), d.types)
        val gap = JsonParser.parseString("{" + d.json() + "}").asJsonObject
        assertEquals("config_cache", gap["why"].asString)
        assertEquals(2, gap["lines"].asInt)
        // A fresh take after the drop has nothing left to report.
        assertNull(c.take().dropped)
    }

    @Test
    fun `one entry bigger than the cap is still kept`() {
        val c = ConfigCache(10)
        c.add(line(1, "y".repeat(100)))
        assertEquals(1, c.take().entries.size)
    }

    @Test
    fun `clear forgets entries and drops`() {
        val c = ConfigCache(200)
        repeat(10) { c.add(line(it.toLong(), "z".repeat(50))) }
        c.clear()
        val t = c.take()
        assertTrue(t.entries.isEmpty())
        assertNull(t.dropped)
    }

    @Test
    fun `withheld raw frames cost only their header`() {
        val r = ConfigCache.Raw(1, 0, 1, 2, null, true, 5000)
        assertEquals(48L, r.cost)
    }

    @Test
    fun `phase ids are fixed and distinct`() {
        val ids = ConnectionProtocol.entries.associateWith { WireTap.phaseId(it) }
        assertEquals(ConnectionProtocol.entries.size, ids.values.toSet().size)
        assertEquals(4, WireTap.phaseId(ConnectionProtocol.PLAY))
        assertEquals(3, WireTap.phaseId(ConnectionProtocol.CONFIGURATION))
        assertEquals("configuration", WireTap.phaseName(ConnectionProtocol.CONFIGURATION))
    }

    @Test
    fun `typed commands keep their name only`() {
        assertEquals("""{"command":"msg","args":"<redacted>"}""", WireTap.redactedCommand("msg Someone hello there"))
        assertEquals("""{"command":"warp","args":null}""", WireTap.redactedCommand("warp"))
        assertEquals("""{"command":"p","args":"<redacted>"}""", WireTap.redactedCommand(" p join x "))
    }
}
