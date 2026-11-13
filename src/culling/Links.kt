package com.engineerclient.culling

import com.engineerclient.dungeonpack.pack.DungeonFrame

/**
 * What stands between two neighbouring tiles of the dungeon's 6x6 grid, as the dungeon map draws it: read from the blocks
 * in the 1-block gap between the two tiles ([Links.read]).
 */
enum class Link {
    /** Nothing: two rooms' walls face each other across an empty gap. The map's gap. */
    GAP,
    /** A doorway between two rooms: open, or shut by a wither or blood door. */
    DOOR,
    /** One room on both tiles: its own blocks run across the gap. */
    JOIN,
    /** The gap's chunk isn't loaded. Counts as joined: only what is known to be out of sight is left out. */
    UNKNOWN;

    /** A straight line of tiles goes on through it. */
    val passes: Boolean get() = this != GAP
    /** The tiles either side are one room. */
    val joins: Boolean get() = this == JOIN || this == UNKNOWN
}

/** The loaded blocks, by column. */
fun interface Columns {
    /** Whether column ([x], [z]) has no block at any height; null when its chunk isn't loaded. */
    fun empty(x: Int, z: Int): Boolean?
}

/**
 * The 60 places two tiles meet (the dungeon pack's door slots): axis 'E' between tile (tx, tz) and (tx + 1, tz), 'S'
 * between (tx, tz) and (tx, tz + 1). Pure.
 *
 * The gap between two tiles is one block wide and 31 long. Between two rooms it is empty but for a door: Hypixel's door
 * units are 5 wide around the middle of the side (13 to 17 along it; the opening 14 to 16), every one with blocks in the
 * gap's middle column (its floor and lintel, the door's own blocks while it is shut). Inside one room the room's floor,
 * walls and roof run across the whole gap. So the middle column tells a gap from the rest, and the columns well to either
 * side of a door tell one room from a doorway. Checked on all 31,680 slots of the 528 recorded layouts the bundled packs
 * build (LinksPackTest). One room reads wrong, on the safe side: an entrance design that walls up the whole gap beside
 * its door reads as joined to the room in front of it, so one tile more than needed is drawn from there.
 */
object Links {
    const val COUNT = 60
    /** The middle of a tile side, along it from the tile's corner: where doors stand. */
    const val MIDDLE = 15
    /**
     * Columns of the gap to either side of a door, clear of its unit, along the side. They share the middle column's
     * chunk (8 to 23 along the side is one chunk), so a slot is read whole or not at all.
     */
    val BESIDE = intArrayOf(8, 9, 10, 20, 21, 22, 23)

    private const val E_COUNT = (DungeonFrame.TILES - 1) * DungeonFrame.TILES

    /** The slot's number (0 until [COUNT]), or -1 when ([tx], [tz]) has no such neighbour on the grid. */
    fun index(axis: Char, tx: Int, tz: Int): Int = when (axis) {
        'E' -> if (tx in 0 until DungeonFrame.TILES - 1 && tz in 0 until DungeonFrame.TILES) tz * (DungeonFrame.TILES - 1) + tx else -1
        else -> if (tx in 0 until DungeonFrame.TILES && tz in 0 until DungeonFrame.TILES - 1) E_COUNT + tz * DungeonFrame.TILES + tx else -1
    }

    /** The slot between tile ([tx], [tz]) and its neighbour ([dx], [dz]) away (one of them 0, the other 1 or -1), or -1 off the grid. */
    fun between(tx: Int, tz: Int, dx: Int, dz: Int): Int = when {
        dx > 0 -> index('E', tx, tz)
        dx < 0 -> index('E', tx - 1, tz)
        dz > 0 -> index('S', tx, tz)
        else -> index('S', tx, tz - 1)
    }

    /** World (x, z) of the gap cell [along] the side of slot ([axis], [tx], [tz]). */
    fun cell(axis: Char, tx: Int, tz: Int, along: Int): Pair<Int, Int> =
        if (axis == 'E') (DungeonFrame.tileMin(tx) + DungeonFrame.TILE_SIZE) to (DungeonFrame.tileMin(tz) + along)
        else (DungeonFrame.tileMin(tx) + along) to (DungeonFrame.tileMin(tz) + DungeonFrame.TILE_SIZE)

    /** What stands in slot ([axis], [tx], [tz]), from its gap's columns. */
    fun read(axis: Char, tx: Int, tz: Int, columns: Columns): Link {
        fun empty(along: Int): Boolean? = cell(axis, tx, tz, along).let { (x, z) -> columns.empty(x, z) }
        val middle = empty(MIDDLE) ?: return Link.UNKNOWN
        for (a in BESIDE) if (empty(a) == false) return Link.JOIN
        return if (middle) Link.GAP else Link.DOOR
    }

    /** [read] of slot number [i]. */
    fun read(i: Int, columns: Columns): Link =
        if (i < E_COUNT) read('E', i % (DungeonFrame.TILES - 1), i / (DungeonFrame.TILES - 1), columns)
        else read('S', (i - E_COUNT) % DungeonFrame.TILES, (i - E_COUNT) / DungeonFrame.TILES, columns)
}
