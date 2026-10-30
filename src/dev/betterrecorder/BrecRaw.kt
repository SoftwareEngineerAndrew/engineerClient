package com.devgineerclient.betterrecorder

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * Reads a recording's raw sidecar back: every `partNNNN.raw.gz` in order, each a run of gzip members of records
 * `[u32 len][varlong seq][u8 dir][u8 phase][u8 flags][bytes]` (flags bit0: withheld, no bytes). For tests and tools.
 */
object BrecRaw {
    class Record(val seq: Long, val dir: Int, val phase: Int, val withheld: Boolean, val len: Int, val bytes: ByteArray?)

    fun read(dir: Path): List<Record> {
        val parts = Files.list(dir).use { s -> s.filter { it.fileName.toString().matches(Regex("""part\d{4}\.raw\.gz""")) }.sorted().toList() }
        val out = ArrayList<Record>()
        for (p in parts) Files.newInputStream(p).use { read(GZIPInputStream(it, 1 shl 16), out) }
        return out
    }

    fun read(input: InputStream, out: MutableList<Record>) {
        val d = DataInputStream(input)
        while (true) {
            val len = try { d.readInt() } catch (_: EOFException) { return }
            var seq = 0L; var shift = 0
            while (true) { val b = d.readUnsignedByte(); seq = seq or ((b and 0x7F).toLong() shl shift); if (b and 0x80 == 0) break; shift += 7 }
            val dirb = d.readUnsignedByte(); val phase = d.readUnsignedByte(); val withheld = d.readUnsignedByte() and 1 != 0
            val bytes = if (withheld) null else ByteArray(len).also { d.readFully(it) }
            out += Record(seq, dirb, phase, withheld, len, bytes)
        }
    }
}
