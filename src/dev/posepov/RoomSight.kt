package com.devgineerclient.posepov

import java.util.Random

/** A design's unturned box ([w] x [d], y [y0] up [h]) in 16³ pieces: piece i is at ([lx], [ly], [lz]). */
class PieceGrid(val w: Int, val d: Int, val y0: Int, val h: Int) {
    val nx = (w + 15) shr 4; val nz = (d + 15) shr 4; val ly0 = y0 shr 4; val ny = ((y0 + h - 1) shr 4) - ly0 + 1
    val size = nx * ny * nz
    fun lx(i: Int) = i / (ny * nz)
    fun lz(i: Int) = (i / ny) % nz
    fun ly(i: Int) = ly0 + i % ny
    fun index(lx: Int, ly: Int, lz: Int) = if (lx !in 0 until nx || lz !in 0 until nz || ly - ly0 !in 0 until ny) -1 else (lx * nz + lz) * ny + (ly - ly0)
    fun of(x: Int, y: Int, z: Int) = index(x shr 4, y shr 4, z shr 4)
}

/**
 * What can be seen from where inside one room, worked out once when its bundle is built: from a few open cells of each piece,
 * rays every way through the room's own blocks until they hit one that hides what's behind it (or leave the box: the rooms
 * beyond are [PovVisibility]'s). Then widened by a piece each way for what the rays missed. Pure (no game), for tests.
 */
object RoomSight {
    private const val SOURCES = 24
    private const val RAYS = 64
    private const val REACH = 128

    /** The pieces seen from piece [from] of [g], as a bitset over the pieces (bit i of byte i / 8). */
    fun from(g: PieceGrid, from: Int, opaque: (Int, Int, Int) -> Boolean, seed: Long): ByteArray {
        val seen = BooleanArray(g.size)
        val x0 = g.lx(from) shl 4; val x1 = minOf(x0 + 15, g.w - 1)
        val z0 = g.lz(from) shl 4; val z1 = minOf(z0 + 15, g.d - 1)
        val y0 = maxOf(g.ly(from) shl 4, g.y0); val y1 = minOf((g.ly(from) shl 4) + 15, g.y0 + g.h - 1)
        val rnd = Random(seed)
        var sources = 0
        for (attempt in 0 until 400) {
            if (sources == SOURCES) break
            val sx = x0 + rnd.nextInt(x1 - x0 + 1); val sy = y0 + rnd.nextInt(y1 - y0 + 1); val sz = z0 + rnd.nextInt(z1 - z0 + 1)
            if (opaque(sx, sy, sz)) continue
            sources++
            for (r in 0 until RAYS) {
                // A way picked evenly over the sphere.
                val u = rnd.nextDouble() * 2 - 1; val a = rnd.nextDouble() * Math.PI * 2; val s = Math.sqrt(1 - u * u)
                cast(g, sx + rnd.nextDouble(), sy + rnd.nextDouble(), sz + rnd.nextDouble(), s * Math.cos(a), u, s * Math.sin(a), opaque, seen)
            }
        }
        // A piece with no open cell: an eye in it is in a wall, and sees whatever it can.
        if (sources == 0) seen.fill(true)
        seen[from] = true
        val wide = BooleanArray(g.size)
        for (i in 0 until g.size) if (seen[i]) {
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val j = g.index(g.lx(i) + dx, g.ly(i) + dy, g.lz(i) + dz)
                if (j >= 0) wide[j] = true
            }
        }
        val out = ByteArray((g.size + 7) shr 3)
        for (i in wide.indices) if (wide[i]) out[i shr 3] = (out[i shr 3].toInt() or (1 shl (i and 7))).toByte()
        return out
    }

    /** One ray from (px, py, pz) along (dx, dy, dz), cell by cell, marking the piece of each cell it passes or stops in. */
    private fun cast(g: PieceGrid, px: Double, py: Double, pz: Double, dx: Double, dy: Double, dz: Double, opaque: (Int, Int, Int) -> Boolean, seen: BooleanArray) {
        var x = Math.floor(px).toInt(); var y = Math.floor(py).toInt(); var z = Math.floor(pz).toInt()
        val sx = if (dx > 0) 1 else -1; val sy = if (dy > 0) 1 else -1; val sz = if (dz > 0) 1 else -1
        val ix = if (dx == 0.0) Double.MAX_VALUE else Math.abs(1 / dx)
        val iy = if (dy == 0.0) Double.MAX_VALUE else Math.abs(1 / dy)
        val iz = if (dz == 0.0) Double.MAX_VALUE else Math.abs(1 / dz)
        var tx = if (dx == 0.0) Double.MAX_VALUE else (if (dx > 0) x + 1 - px else px - x) * ix
        var ty = if (dy == 0.0) Double.MAX_VALUE else (if (dy > 0) y + 1 - py else py - y) * iy
        var tz = if (dz == 0.0) Double.MAX_VALUE else (if (dz > 0) z + 1 - pz else pz - z) * iz
        for (step in 0 until REACH) {
            if (tx < ty && tx < tz) { x += sx; tx += ix } else if (ty < tz) { y += sy; ty += iy } else { z += sz; tz += iz }
            if (x < 0 || z < 0 || x >= g.w || z >= g.d || y < g.y0 || y >= g.y0 + g.h) return
            seen[g.of(x, y, z)] = true
            if (opaque(x, y, z)) return
        }
    }
}
