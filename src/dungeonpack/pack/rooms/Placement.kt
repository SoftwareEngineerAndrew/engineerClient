package com.engineerclient.dungeonpack.pack.rooms

import com.engineerclient.dungeonpack.pack.DungeonFrame
import com.engineerclient.dungeonpack.pack.Rot
import com.engineerclient.dungeonpack.pack.Tile

/** Where a canonical room-frame cell lands in the world. */
data class WorldPos(val x: Int, val y: Int, val z: Int)

/** A placement the law of FORMAT-rooms 4 refuses (wrong rotation for the shape, tiles that do not match, ...). The message is plain English. */
class PlacementException(message: String) : RuntimeException(message)

/**
 * Where one room design stands in the world: the position law of FORMAT-rooms 4.2 and the tile law of 4.3, as pure
 * functions. [rot] is the rotation actually used (Fairy is always SOUTH, 4.5, whatever the layout says); [quarterTurns]
 * is how many clockwise quarter turns every block STATE must also be rotated by (`BlockState.rotate`, FORMAT-rooms 3.3).
 *
 * Build one with [of]; it asserts the tile law, so a placement that exists is consistent with the layout's tiles.
 * Test vectors: FORMAT-rooms 4.4 (see `PlacementTest`).
 */
class Placement private constructor(
    val shape: String,
    /** Canonical footprint: x extent and z extent in blocks (31 per tile + 32 per extra tile along that axis). */
    val w: Int,
    val d: Int,
    val rot: Rot,
    /** The world tiles this room covers (the layout's `tiles`, sorted row-major). */
    val worldTiles: List<Tile>,
    /** World min corner of the placed box: `-200 + 32 * min tx`, `-200 + 32 * min tz`. */
    val originX: Int,
    val originZ: Int,
) {
    /** The quarter turns to rotate each block state by. */
    val quarterTurns: Int get() = rot.q

    /** The placed box's size in the world: (w, d) for SOUTH and NORTH, swapped for WEST and EAST. */
    val boxW: Int get() = if (rot == Rot.SOUTH || rot == Rot.NORTH) w else d
    val boxD: Int get() = if (rot == Rot.SOUTH || rot == Rot.NORTH) d else w

    /** The world tiles covered (same as [worldTiles]). */
    fun tiles(): List<Tile> = worldTiles

    /** The world block of canonical cell (x, y, z); y is unchanged (FORMAT-rooms 4.2). */
    fun toWorld(x: Int, y: Int, z: Int): WorldPos {
        val px: Int; val pz: Int
        when (rot) {
            Rot.SOUTH -> { px = x; pz = z }
            Rot.NORTH -> { px = w - 1 - x; pz = d - 1 - z }
            Rot.WEST -> { px = d - 1 - z; pz = x }
            Rot.EAST -> { px = z; pz = w - 1 - x }
        }
        return WorldPos(originX + px, y, originZ + pz)
    }

    /** The canonical cell (x, z) of a world column, or null when it is outside the placed box. The inverse of [toWorld]. */
    fun toCanonical(wx: Int, wz: Int): Pair<Int, Int>? {
        val px = wx - originX; val pz = wz - originZ
        if (px !in 0 until boxW || pz !in 0 until boxD) return null
        return when (rot) {
            Rot.SOUTH -> px to pz
            Rot.NORTH -> (w - 1 - px) to (d - 1 - pz)
            Rot.WEST -> pz to (d - 1 - px)
            Rot.EAST -> (w - 1 - pz) to px
        }
    }

    /**
     * Whether world column ([wx], [wz]) belongs to this room: it lies in one of the room's tiles, or in the 1-block gap column or row between two of
     * them. The box of an L room also covers the corner that is a neighbour's, which this says is not the room's (a resync must not clear it).
     */
    fun ownsColumn(wx: Int, wz: Int): Boolean {
        fun axis(v: Int): List<Int> {
            val t = Math.floorDiv(v - DungeonFrame.ORIGIN, DungeonFrame.PITCH)
            return if (v - DungeonFrame.tileMin(t) == DungeonFrame.TILE_SIZE) listOf(t, t + 1) else listOf(t)
        }
        return axis(wx).all { tx -> axis(wz).all { tz -> Tile(tx, tz) in tileSet } }
    }

    private val tileSet: Set<Tile> = worldTiles.toSet()

    /** The world tile that canonical tile cell (ix, iz) lands on (FORMAT-rooms 4.3). */
    fun worldTileOf(ix: Int, iz: Int): Tile = placeTile(rot, ix, iz, nx, nz, tx0, tz0)

    private val tx0 = worldTiles.minOf { it.x }
    private val tz0 = worldTiles.minOf { it.z }
    private val nx get() = canonicalSize(shape).first
    private val nz get() = canonicalSize(shape).second

    companion object {
        /** FORMAT-rooms 2: canonical tile cells by shape. */
        fun canonicalTiles(shape: String): List<Tile> = when (shape) {
            "1x1" -> listOf(Tile(0, 0))
            "1x2" -> listOf(Tile(0, 0), Tile(1, 0))
            "1x3" -> listOf(Tile(0, 0), Tile(1, 0), Tile(2, 0))
            "1x4" -> listOf(Tile(0, 0), Tile(1, 0), Tile(2, 0), Tile(3, 0))
            "2x2" -> listOf(Tile(0, 0), Tile(1, 0), Tile(0, 1), Tile(1, 1))
            "L" -> listOf(Tile(0, 0), Tile(0, 1), Tile(1, 1))
            else -> throw PlacementException("unknown room shape '$shape'")
        }

        /** (nx, nz): the tile counts along x and z of the canonical bounding box. */
        fun canonicalSize(shape: String): Pair<Int, Int> {
            val t = canonicalTiles(shape)
            return (t.maxOf { it.x } + 1) to (t.maxOf { it.z } + 1)
        }

        /** Block extent of the canonical footprint for a shape: 31 per tile + 32 per extra tile along that axis. */
        fun footprint(shape: String): Pair<Int, Int> {
            val (nx, nz) = canonicalSize(shape)
            return (31 + 32 * (nx - 1)) to (31 + 32 * (nz - 1))
        }

        /**
         * Odin rotations the law accepts per shape (FORMAT-rooms 4.1; L's EAST is implemented by symmetry, never observed).
         * A design's own `rotations` list is the narrower authority ([RoomDesign.place] checks it too).
         */
        fun legalRotations(shape: String): List<Rot> = when (shape) {
            "1x1", "L" -> Rot.entries
            "1x2", "1x3", "1x4" -> listOf(Rot.SOUTH, Rot.WEST)
            "2x2" -> listOf(Rot.SOUTH)
            else -> emptyList()
        }

        /**
         * The world tiles a design of [shape] covers when placed [rot] with its tile box starting at ([tx0], [tz0]): the image of
         * the canonical tiles under the tile law (FORMAT-rooms 4.3), sorted row-major. The inverse job of [of], which checks a
         * given tile set; this makes one (a spike or a generator choosing where a room goes).
         */
        fun tilesFor(shape: String, rot: Rot, tx0: Int, tz0: Int): List<Tile> {
            val (nx, nz) = canonicalSize(shape)
            return canonicalTiles(shape).map { placeTile(rot, it.x, it.z, nx, nz, tx0, tz0) }.sortedWith(compareBy({ it.z }, { it.x }))
        }

        private fun placeTile(rot: Rot, ix: Int, iz: Int, nx: Int, nz: Int, tx0: Int, tz0: Int): Tile {
            val a: Int; val b: Int
            when (rot) {
                Rot.SOUTH -> { a = ix; b = iz }
                Rot.NORTH -> { a = nx - 1 - ix; b = nz - 1 - iz }
                Rot.WEST -> { a = nz - 1 - iz; b = ix }
                Rot.EAST -> { a = iz; b = nx - 1 - ix }
            }
            return Tile(tx0 + a, tz0 + b)
        }

        /**
         * The placement of a design of [shape] (footprint [w] x [d]) with the layout's rotation word and world tiles.
         * [fairy]: the Fairy room, which is always SOUTH. Throws [PlacementException] when the rotation is not one that
         * occurs for the shape, or when the canonical tiles do not land exactly on [worldTiles] (tile law).
         */
        fun of(shape: String, w: Int, d: Int, rotWord: Rot, worldTiles: List<Tile>, fairy: Boolean = false): Placement {
            val rot = if (fairy) Rot.SOUTH else rotWord
            if (rot !in legalRotations(shape)) throw PlacementException("a $shape room is never placed $rot")
            val (fw, fd) = footprint(shape)
            if (w != fw || d != fd) throw PlacementException("a $shape room is ${fw}x$fd blocks, but the design says ${w}x$d")
            if (worldTiles.isEmpty()) throw PlacementException("a room needs at least one tile")
            val sorted = worldTiles.sortedWith(compareBy({ it.z }, { it.x }))
            val tx0 = sorted.minOf { it.x }; val tz0 = sorted.minOf { it.z }
            val (nx, nz) = canonicalSize(shape)
            val image = canonicalTiles(shape).map { placeTile(rot, it.x, it.z, nx, nz, tx0, tz0) }.toSet()
            if (image != sorted.toSet() || sorted.size != image.size) {
                throw PlacementException("a $shape room placed $rot covers $image, not the layout's tiles $sorted")
            }
            for (t in sorted) if (t.x !in 0..5 || t.z !in 0..5) throw PlacementException("tile ${t.x},${t.z} is outside the 6x6 grid")
            return Placement(shape, w, d, rot, sorted, -200 + 32 * tx0, -200 + 32 * tz0)
        }
    }
}
