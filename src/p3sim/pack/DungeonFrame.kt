package com.engineerclient.p3sim.pack

/**
 * The dungeon's world frame (BRIEF "World frame"): Hypixel's real coordinates, so Odin's scanner works unmodified.
 * The 6x6 room grid starts at x,z = -200 and each tile is a 32-block pitch; a room covers 31 blocks of it and the 1-block
 * column between two tiles is where doors stand. +x is east, +z is south, y is absolute (0..255). Pure functions.
 *
 * Time and ticks are not here; see `Run`. The boss arena lives at positive coordinates (x, z >= [ARENA_MIN]).
 */
object DungeonFrame {
    /** x and z of tile (0, 0)'s min corner. */
    const val ORIGIN = -200
    /** Distance between two tiles' min corners. */
    const val PITCH = 32
    /** Blocks a room covers per tile; the 32nd column is the door gap. */
    const val TILE_SIZE = 31
    /** The grid is 6x6 tiles. */
    const val TILES = 6
    /** A whole tile count that every counter uses (36 units, FORMAT-runstate 6.3). */
    const val TILE_COUNT = TILES * TILES

    /** The last block of the dungeon box (tile 5's last block): the box is x,z in `[ORIGIN, DUNGEON_MAX]`. */
    const val DUNGEON_MAX = ORIGIN + PITCH * (TILES - 1) + TILE_SIZE - 1   // -10

    /** The boss arena starts here in both x and z; the arena's extension is clipped to this (it must not touch tile (5,5)). */
    const val ARENA_MIN = -8

    /** Doors are 3 wide, 4 tall (y 69..72, frame top y73), 3 deep around the door spot (FORMAT-layouts 2). */
    const val DOOR_Y_MIN = 69
    const val DOOR_Y_MAX = 72

    /** Min corner (x or z) of tile index [t]. */
    fun tileMin(t: Int): Int = ORIGIN + PITCH * t

    /** Centre column (x or z) of tile index [t]: `-185 + 32 t`. This is the column Odin hashes to name a room. */
    fun tileCentre(t: Int): Int = tileMin(t) + 15

    /** The tile index (0..5) a world coordinate falls in, clipped to the grid; the gap column belongs to the lower tile. */
    fun tileIndex(v: Double): Int = Math.floorDiv(Math.floor(v).toInt() - ORIGIN, PITCH).coerceIn(0, TILES - 1)

    /** The tile index of a world coordinate, or null outside the grid or in a gap column (a door, not a room). */
    fun roomTileIndexOrNull(v: Double): Int? {
        val b = Math.floor(v).toInt()
        val t = Math.floorDiv(b - ORIGIN, PITCH)
        if (t !in 0 until TILES) return null
        return if (b - tileMin(t) <= TILE_SIZE - 1) t else null
    }

    /** The tile a world position stands in, or null in a gap, outside the dungeon, or beyond it. */
    fun tileOf(x: Double, z: Double): Tile? {
        val tx = roomTileIndexOrNull(x) ?: return null
        val tz = roomTileIndexOrNull(z) ?: return null
        return Tile(tx, tz)
    }

    /** True when the column is inside the dungeon box `[ORIGIN, DUNGEON_MAX]` in both axes. */
    fun inDungeonBox(x: Int, z: Int): Boolean = x in ORIGIN..DUNGEON_MAX && z in ORIGIN..DUNGEON_MAX

    /**
     * World (x, z) of the centre of a door slot. `axis 'E'` joins `(tx, tz)` and `(tx+1, tz)` (tx 0..4); `axis 'S'` joins
     * `(tx, tz)` and `(tx, tz+1)` (tz 0..4). 60 slots exist. FORMAT-layouts 2: E: `(-169+32tx, -185+32tz)`, S: `(-185+32tx, -169+32tz)`.
     */
    fun doorSpot(axis: Char, tx: Int, tz: Int): Pair<Int, Int> = when (axis) {
        'E' -> (ORIGIN + 31 + PITCH * tx) to (tileCentre(tz))
        'S' -> tileCentre(tx) to (ORIGIN + 31 + PITCH * tz)
        else -> throw IllegalArgumentException("door axis must be E or S, not '$axis'")
    }

    /** The two tiles a door slot separates. */
    fun slotTiles(axis: Char, tx: Int, tz: Int): Pair<Tile, Tile> = when (axis) {
        'E' -> Tile(tx, tz) to Tile(tx + 1, tz)
        'S' -> Tile(tx, tz) to Tile(tx, tz + 1)
        else -> throw IllegalArgumentException("door axis must be E or S, not '$axis'")
    }

    /** Whether (axis, tx, tz) names one of the 60 slots. */
    fun isSlot(axis: Char, tx: Int, tz: Int): Boolean = when (axis) {
        'E' -> tx in 0..TILES - 2 && tz in 0 until TILES
        'S' -> tx in 0 until TILES && tz in 0..TILES - 2
        else -> false
    }
}
