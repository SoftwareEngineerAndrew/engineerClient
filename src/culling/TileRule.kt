package com.engineerclient.culling

import com.engineerclient.dungeonpack.pack.DungeonFrame

/**
 * Which tiles of the dungeon's 6x6 grid matter from where the camera is, and which chunks that leaves. Pure.
 *
 * The rule, on the dungeon map: the whole room the camera is in, and from the camera's own tile every tile north, east,
 * south and west of it in a straight line, through doors and across the tiles of one room, up to the first gap (two
 * rooms' walls with nothing between). Room walls stop every other line of sight, and a doorway is 3 wide and several
 * blocks deep: past it only a narrow cone along its axis shows. What the rule gives up: the far tiles of a big room seen
 * through a door from outside it, and whatever shows through a wall someone broke where the map has a gap.
 */
object TileRule {
    private const val N = DungeonFrame.TILES
    private val DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

    /** Bit of tile ([tx], [tz]) in a tile set: z * 6 + x, as Odin numbers them. */
    fun bit(tx: Int, tz: Int): Long = 1L shl (tz * N + tx)

    /**
     * The tile indices (0..5) a block coordinate stands on along one axis (empty off the grid). A tile is its room's 31
     * blocks and the gap column on either side, so the gap between two tiles is on both: a camera in a doorway is in
     * both rooms.
     */
    fun tilesAt(block: Int): IntRange {
        // the tile whose room, or gap column past it, the block is in; that gap column is the next tile's too
        val t = Math.floorDiv(block - DungeonFrame.ORIGIN, DungeonFrame.PITCH)
        val next = Math.floorDiv(block - DungeonFrame.ORIGIN + 1, DungeonFrame.PITCH)
        return maxOf(0, t)..minOf(N - 1, next)
    }

    /**
     * The tiles that matter from tile ([tx], [tz]): its room (the tiles joined to it, [Link.joins]) and the four straight
     * lines from it ([Link.passes]). [link]: what stands in slot number i ([Links.index]).
     */
    fun relevant(tx: Int, tz: Int, link: (Int) -> Link): Long {
        if (tx !in 0 until N || tz !in 0 until N) return 0L
        var set = bit(tx, tz)
        // the room: every tile joined to this one, through any of its tiles
        val stack = IntArray(N * N); var top = 0
        stack[top++] = tz * N + tx
        while (top > 0) {
            val t = stack[--top]; val x = t % N; val z = t / N
            for (d in DIRS) {
                val slot = Links.between(x, z, d[0], d[1])
                if (slot < 0 || !link(slot).joins) continue
                val b = bit(x + d[0], z + d[1])
                if (set and b == 0L) { set = set or b; stack[top++] = (z + d[1]) * N + x + d[0] }
            }
        }
        // the lines: from the camera's own tile, up to the first gap
        for (d in DIRS) {
            var x = tx; var z = tz
            while (true) {
                val slot = Links.between(x, z, d[0], d[1])
                if (slot < 0 || !link(slot).passes) break
                x += d[0]; z += d[1]
                set = set or bit(x, z)
            }
        }
        return set
    }

    /** [relevant] from every tile the camera's block column ([x], [z]) stands on; 0 when that is off the grid. */
    fun relevantAt(x: Int, z: Int, link: (Int) -> Link): Long {
        var set = 0L
        for (tz in tilesAt(z)) for (tx in tilesAt(x)) set = set or relevant(tx, tz, link)
        return set
    }
}

/**
 * The chunk columns to draw for a set of tiles ([TileRule.bit]): every chunk that touches one of them. Rooms don't line
 * up with chunks (a tile is 32 blocks from -200, so every other chunk lies across two tiles): a chunk goes only when all
 * of it is outside the tiles that matter. Immutable.
 */
class CullMask(val tiles: Long) {
    private val keep = BooleanArray(SPAN * SPAN)
    /** Chunk columns kept, of the [SPAN] x [SPAN] the dungeon covers. */
    val kept: Int

    init {
        for (t in 0 until DungeonFrame.TILE_COUNT) {
            if (tiles and (1L shl t) == 0L) continue
            val x = chunks(t % DungeonFrame.TILES); val z = chunks(t / DungeonFrame.TILES)
            for (cz in z) for (cx in x) keep[(cz - MIN) * SPAN + (cx - MIN)] = true
        }
        kept = keep.count { it }
    }

    /** Whether chunk column ([cx], [cz]) is drawn. */
    fun keeps(cx: Int, cz: Int): Boolean {
        val ix = cx - MIN; val iz = cz - MIN
        return ix in 0 until SPAN && iz in 0 until SPAN && keep[iz * SPAN + ix]
    }

    companion object {
        /** The chunks tile index [t] touches along one axis: its room's blocks and the gap column either side of it. */
        fun chunks(t: Int): IntRange =
            Math.floorDiv(DungeonFrame.tileMin(t) - 1, 16)..Math.floorDiv(DungeonFrame.tileMin(t) + DungeonFrame.TILE_SIZE, 16)

        /** The first chunk of the grid along an axis, and how many it spans (-13, 13). */
        val MIN: Int = chunks(0).first
        val SPAN: Int = chunks(DungeonFrame.TILES - 1).last - MIN + 1
    }
}
