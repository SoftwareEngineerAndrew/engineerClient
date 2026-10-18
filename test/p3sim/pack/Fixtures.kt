package com.engineerclient.p3sim.pack

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * Synthetic pack builders for the tests: a tiny reference ENCODER of the formats in FORMAT-rooms.md section 5 (so the
 * Kotlin decoder is checked against an independent writer of the spec) and helpers that gzip JSON text. Nothing here is
 * recorded data; every block, name and number is made up.
 */
object Fixtures {
    fun varint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v >= 0x80) { out.write((v and 0x7F) or 0x80); v = v ushr 7 }
        out.write(v)
    }

    private fun u16(out: ByteArrayOutputStream, v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }

    private fun palette(out: ByteArrayOutputStream, palette: List<String>) {
        varint(out, palette.size)
        for (s in palette) { val b = s.toByteArray(Charsets.UTF_8); varint(out, b.size); out.write(b) }
    }

    fun zlib(raw: ByteArray): ByteArray {
        val d = Deflater(); d.setInput(raw); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end(); return out.toByteArray()
    }

    /** An "RVL1" volume blob: [cells] are palette indexes in x-fastest, then z, then y order. Runs are written greedily. */
    fun volume(w: Int, d: Int, h: Int, y0: Int, palette: List<String>, cells: IntArray): ByteArray {
        require(cells.size == w * d * h)
        val out = ByteArrayOutputStream()
        out.write("RVL1".toByteArray(Charsets.US_ASCII)); u16(out, w); u16(out, d); u16(out, h); u16(out, y0)
        palette(out, palette)
        var i = 0
        while (i < cells.size) {
            var j = i
            while (j < cells.size && cells[j] == cells[i]) j++
            varint(out, j - i); varint(out, cells[i]); i = j
        }
        return zlib(out.toByteArray())
    }

    /** An "RPT1" patch blob over a parent box; [changes] are (linear index to palette index), indexes strictly increasing. */
    fun patch(w: Int, d: Int, h: Int, y0: Int, palette: List<String>, changes: List<Pair<Int, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RPT1".toByteArray(Charsets.US_ASCII)); u16(out, w); u16(out, d); u16(out, h); u16(out, y0)
        palette(out, palette)
        varint(out, changes.size)
        var prev = 0
        for ((n, c) in changes.withIndex()) { varint(out, if (n == 0) c.first else c.first - prev); varint(out, c.second); prev = c.first }
        return zlib(out.toByteArray())
    }

    fun crcHex(b: ByteArray): String { val c = CRC32(); c.update(b); return "%08x".format(c.value) }

    fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject
}
