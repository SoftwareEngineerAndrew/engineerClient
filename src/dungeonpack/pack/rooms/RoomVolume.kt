package com.engineerclient.dungeonpack.pack.rooms

import com.engineerclient.dungeonpack.pack.PackFormatException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * One decoded block volume of a room design, in the canonical room frame (FORMAT-rooms 2): `x` east 0..W-1, `z` south
 * 0..D-1, `y` the ABSOLUTE world y from [y0] up. Cells hold an index into [palette]; entry 0 is `minecraft:air`.
 * The states are plain strings (`minecraft:stone_stairs[facing=east,half=bottom]`): turning them into Minecraft block
 * states is the layout builder's job, so this class runs in headless tests.
 */
class RoomVolume(val w: Int, val d: Int, val h: Int, val y0: Int, val palette: List<String>, val cells: ShortArray) {
    init {
        require(cells.size == w * d * h) { "volume is ${w}x${d}x$h but has ${cells.size} cells" }
    }

    /** Linear index of canonical cell (x, y, z): x fastest, then z, then y (FORMAT-rooms 5.1). */
    fun index(x: Int, y: Int, z: Int): Int = ((y - y0) * d + z) * w + x

    fun contains(x: Int, y: Int, z: Int): Boolean = x in 0 until w && z in 0 until d && y >= y0 && y < y0 + h

    /** The palette index at a cell; 0 (air) outside the box. */
    fun paletteIndexAt(x: Int, y: Int, z: Int): Int = if (contains(x, y, z)) cells[index(x, y, z)].toInt() and 0xFFFF else 0

    /** The state string at a cell; `minecraft:air` outside the box. */
    fun stateAt(x: Int, y: Int, z: Int): String = palette[paletteIndexAt(x, y, z)]

    fun isAir(x: Int, y: Int, z: Int): Boolean = paletteIndexAt(x, y, z) == 0 || palette[paletteIndexAt(x, y, z)] == AIR

    /** How many cells are not air. */
    fun nonAirCount(): Int {
        val air = BooleanArray(palette.size) { palette[it] == AIR }
        var n = 0
        for (c in cells) if (!air[c.toInt() and 0xFFFF]) n++
        return n
    }

    /** A copy with [patch] applied (FORMAT-rooms 5.2). Cells the patch sets to `minecraft:air` are cleared. */
    fun withPatch(patch: RoomPatch): RoomVolume {
        if (patch.w != w || patch.d != d || patch.h != h || patch.y0 != y0) {
            throw PackFormatException("patch box ${patch.w}x${patch.d}x${patch.h}@${patch.y0} does not match its volume ${w}x${d}x${h}@$y0")
        }
        val pal = ArrayList(palette)
        val map = HashMap<String, Int>()
        pal.forEachIndexed { i, s -> map.putIfAbsent(s, i) }
        val remap = IntArray(patch.palette.size) { i ->
            val s = patch.palette[i]
            map.getOrPut(s) { pal += s; pal.size - 1 }
        }
        if (pal.size > 0xFFFF) throw PackFormatException("patched palette has ${pal.size} entries")
        val out = cells.copyOf()
        for (k in patch.indices.indices) out[patch.indices[k]] = remap[patch.values[k]].toShort()
        return RoomVolume(w, d, h, y0, pal, out)
    }

    companion object {
        const val AIR = "minecraft:air"
    }
}

/** A sparse set of cell changes over a parent volume's box (FORMAT-rooms 5.2): `indices` are linear cell indexes, strictly increasing. */
class RoomPatch(val w: Int, val d: Int, val h: Int, val y0: Int, val palette: List<String>, val indices: IntArray, val values: IntArray)

/** Decoder for the `rooms.blocks.bin` blobs (FORMAT-rooms 5). Pure functions; every malformed input is a [PackFormatException]. */
object RoomBlobs {
    /** Upper bound on a decoded volume so a corrupt header cannot ask for gigabytes (the largest real box is 127x127x160). */
    private const val MAX_CELLS = 64 * 1024 * 1024

    fun crc32Hex(bytes: ByteArray, off: Int = 0, len: Int = bytes.size): String {
        val c = CRC32(); c.update(bytes, off, len)
        return "%08x".format(c.value)
    }

    /** Inflates one blob (a complete zlib stream). */
    fun inflate(compressed: ByteArray): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(compressed)
            val out = ByteArrayOutputStream(compressed.size * 4)
            val buf = ByteArray(1 shl 16)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw PackFormatException("the blob ends early")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } catch (e: java.util.zip.DataFormatException) {
            throw PackFormatException("the blob is not a zlib stream: ${e.message}", e)
        } finally {
            inf.end()
        }
    }

    /** Decodes a `kind: "volume"` blob. */
    fun decodeVolume(compressed: ByteArray): RoomVolume {
        val r = Reader(inflate(compressed))
        r.magic("RVL1")
        val w = r.u16(); val d = r.u16(); val h = r.u16(); val y0 = r.u16()
        val cellCount = w.toLong() * d * h
        if (cellCount > MAX_CELLS) throw PackFormatException("volume ${w}x${d}x$h is implausibly large")
        val palette = r.palette()
        if (palette.isEmpty() || palette[0] != RoomVolume.AIR) throw PackFormatException("palette entry 0 must be minecraft:air")
        val cells = ShortArray(cellCount.toInt())
        var i = 0
        while (i < cells.size) {
            val run = r.varint()
            val pal = r.varint()
            if (run < 1) throw PackFormatException("run length $run")
            if (pal >= palette.size) throw PackFormatException("palette index $pal of ${palette.size}")
            if (run > cells.size - i) throw PackFormatException("runs cover more than $cellCount cells")
            java.util.Arrays.fill(cells, i, i + run, pal.toShort())
            i += run
        }
        return RoomVolume(w, d, h, y0, palette, cells)
    }

    /** Decodes a `kind: "patch"` blob. */
    fun decodePatch(compressed: ByteArray): RoomPatch {
        val r = Reader(inflate(compressed))
        r.magic("RPT1")
        val w = r.u16(); val d = r.u16(); val h = r.u16(); val y0 = r.u16()
        val cellCount = w.toLong() * d * h
        val palette = r.palette()
        val k = r.varint()
        if (k > cellCount) throw PackFormatException("patch has $k changes in a box of $cellCount cells")
        val indices = IntArray(k)
        val values = IntArray(k)
        var prev = -1L
        for (n in 0 until k) {
            val delta = r.varint().toLong()
            // The first delta is the index itself (may be 0); later ones must move forward.
            val idx = if (n == 0) delta else prev + delta
            if (n > 0 && delta < 1) throw PackFormatException("patch indices are not increasing")
            if (idx >= cellCount) throw PackFormatException("patch index $idx outside the box")
            val pal = r.varint()
            if (pal >= palette.size) throw PackFormatException("patch palette index $pal of ${palette.size}")
            indices[n] = idx.toInt(); values[n] = pal
            prev = idx
        }
        return RoomPatch(w, d, h, y0, palette, indices, values)
    }

    private class Reader(val b: ByteArray) {
        var p = 0
        fun need(n: Int) { if (p + n > b.size) throw PackFormatException("the blob ends early") }
        fun magic(m: String) {
            need(4)
            val got = String(b, 0, 4, Charsets.US_ASCII)
            if (got != m) throw PackFormatException("bad magic '$got', expected '$m'")
            p = 4
        }
        fun u16(): Int { need(2); val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8); p += 2; return v }
        fun varint(): Int {
            var v = 0L; var shift = 0
            while (true) {
                need(1)
                val x = b[p++].toInt() and 0xFF
                v = v or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) break
                shift += 7
                if (shift > 35) throw PackFormatException("varint too long")
            }
            if (v > Int.MAX_VALUE) throw PackFormatException("varint too large")
            return v.toInt()
        }
        fun palette(): List<String> {
            val n = varint()
            if (n > 0xFFFF) throw PackFormatException("palette has $n entries")
            val out = ArrayList<String>(n)
            repeat(n) {
                val len = varint()
                need(len)
                out += String(b, p, len, Charsets.UTF_8); p += len
            }
            return out
        }
    }
}

/** Random access to the compressed blobs. Production reads the file; tests hand in bytes. */
interface BlobSource : Closeable {
    val size: Long
    fun read(off: Long, len: Int): ByteArray
}

/** [BlobSource] over `rooms.blocks.bin`. Thread-safe: reads are serialised. */
class FileBlobSource(path: java.nio.file.Path) : BlobSource {
    private val file = RandomAccessFile(path.toFile(), "r")
    override val size: Long = file.length()
    @Synchronized override fun read(off: Long, len: Int): ByteArray {
        if (off < 0 || len < 0 || off + len > size) throw PackFormatException("blob $off+$len is outside the file ($size bytes)")
        val b = ByteArray(len)
        file.seek(off); file.readFully(b)
        return b
    }
    override fun close() = file.close()
}

class BytesBlobSource(private val bytes: ByteArray) : BlobSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(off: Long, len: Int): ByteArray {
        if (off < 0 || len < 0 || off + len > bytes.size) throw PackFormatException("blob $off+$len is outside the data (${bytes.size} bytes)")
        return bytes.copyOfRange(off.toInt(), (off + len).toInt())
    }
    override fun close() {}
}
