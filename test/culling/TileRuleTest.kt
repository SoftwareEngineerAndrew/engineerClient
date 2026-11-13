package com.engineerclient.culling

import com.engineerclient.dungeonpack.pack.DungeonFrame
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileRuleTest {
    /** A dungeon's slots, every one a gap but those named: "E 1,1" is the slot east of tile (1, 1), "S 1,1" the one south of it. */
    private fun links(join: String = "", door: String = "", unknown: String = ""): (Int) -> Link {
        val all = Array(Links.COUNT) { Link.GAP }
        fun put(names: String, link: Link) {
            for (m in Regex("([ES]) (\\d),(\\d)").findAll(names)) {
                val i = Links.index(m.groupValues[1][0], m.groupValues[2].toInt(), m.groupValues[3].toInt())
                assertTrue(i >= 0, m.value)
                all[i] = link
            }
        }
        put(join, Link.JOIN); put(door, Link.DOOR); put(unknown, Link.UNKNOWN)
        return { all[it] }
    }

    private fun tiles(vararg t: Pair<Int, Int>): Long = t.fold(0L) { set, (x, z) -> set or TileRule.bit(x, z) }
    private fun names(set: Long): List<String> = (0 until 36).filter { set and (1L shl it) != 0L }.map { "${it % 6},${it / 6}" }
    private fun assertTiles(want: Long, got: Long) = assertEquals(names(want), names(got))

    @Test fun theRoomYouAreInAndTheFourLinesToTheFirstGap() {
        // The map this was drawn on: a 2x2 room on (1,1)..(2,2), the camera on its north-west tile. North a door to a
        // room at the grid's edge; west a door into one tile of a tall room (0,0)..(0,3); east the room's own tile, then
        // a gap; south its own tile, a door, a room, a door, a room, a gap.
        val map = links(
            join = "E 1,1 S 1,1 E 1,2 S 2,1  S 0,0 S 0,1 S 0,2",
            door = "S 1,0  E 0,1  S 1,2 S 1,3  E 2,2 S 2,2 E 3,3",
        )
        assertTiles(tiles(1 to 1, 2 to 1, 1 to 2, 2 to 2, 1 to 0, 0 to 1, 1 to 3, 1 to 4), TileRule.relevant(1, 1, map))
        // from the room's south-east tile the lines are that tile's: its own doors, east and south
        assertTiles(tiles(1 to 1, 2 to 1, 1 to 2, 2 to 2, 3 to 2, 2 to 3), TileRule.relevant(2, 2, map))
    }

    @Test fun aRoomAloneKeepsOnlyItself() {
        assertTiles(tiles(3 to 2), TileRule.relevant(3, 2, links()))
    }

    @Test fun aLineGoesThroughDoorsAndRoomsButNotSideways() {
        // east of (0,2): a door, a room two tiles long east to west with a third tile south of its first, a door, a room, a gap
        val map = links(door = "E 0,2 E 2,2", join = "E 1,2 S 1,2")
        assertTiles(tiles(0 to 2, 1 to 2, 2 to 2, 3 to 2), TileRule.relevant(0, 2, map))
    }

    @Test fun whatIsNotLoadedCountsAsOpen() {
        val map = links(unknown = "E 2,2 S 2,2 E 3,3")
        // joined through the unknown slots (one room, as far as anyone knows), and the lines go on through them
        assertTiles(tiles(2 to 2, 3 to 2, 2 to 3), TileRule.relevant(2, 2, map))
        assertTiles(tiles(3 to 3, 4 to 3), TileRule.relevant(3, 3, map))
    }

    @Test fun offTheGridNothingMatters() {
        assertEquals(0L, TileRule.relevant(-1, 2, links()))
        assertEquals(0L, TileRule.relevantAt(-202, -185, links()))
        assertEquals(0L, TileRule.relevantAt(-185, -8, links()))
    }

    @Test fun aBlockIsOnItsTileAndAGapColumnOnBoth() {
        assertTrue(TileRule.tilesAt(-202).isEmpty())
        assertEquals(0..0, TileRule.tilesAt(-201))
        assertEquals(0..0, TileRule.tilesAt(-200))
        assertEquals(0..0, TileRule.tilesAt(-170))
        assertEquals(0..1, TileRule.tilesAt(-169))
        assertEquals(1..1, TileRule.tilesAt(-168))
        assertEquals(4..5, TileRule.tilesAt(-41))
        assertEquals(5..5, TileRule.tilesAt(-10))
        assertEquals(5..5, TileRule.tilesAt(-9))
        assertTrue(TileRule.tilesAt(-8).isEmpty())
    }

    @Test fun inADoorwayBothRoomsMatter() {
        // two 1x2 rooms side by side, a door between their north tiles; the camera in that door's gap column
        val map = links(join = "S 1,1 S 2,1", door = "E 1,1")
        val both = tiles(1 to 1, 1 to 2, 2 to 1, 2 to 2)
        assertTiles(both, TileRule.relevantAt(DungeonFrame.tileMin(1) + 31, DungeonFrame.tileCentre(1), map))
        // a step either side it is one room and the tile through the door
        assertTiles(tiles(1 to 1, 1 to 2, 2 to 1), TileRule.relevantAt(DungeonFrame.tileMin(1) + 30, DungeonFrame.tileCentre(1), map))
        assertTiles(tiles(2 to 1, 2 to 2, 1 to 1), TileRule.relevantAt(DungeonFrame.tileMin(2), DungeonFrame.tileCentre(1), map))
    }

    @Test fun theGridIsThirteenChunksFromMinusThirteen() {
        assertEquals(-13, CullMask.MIN)
        assertEquals(13, CullMask.SPAN)
        assertEquals(-13..-11, CullMask.chunks(0))
        assertEquals(-3..-1, CullMask.chunks(5))
    }

    @Test fun aTileKeepsTheChunksItTouches() {
        val m = CullMask(tiles(2 to 3))
        assertEquals(9, m.kept)
        // its own chunk and the one either side that it shares with the next tile
        for (cx in -9..-7) for (cz in -7..-5) assertTrue(m.keeps(cx, cz), "$cx,$cz")
        assertFalse(m.keeps(-10, -6)); assertFalse(m.keeps(-6, -6)); assertFalse(m.keeps(-8, -8)); assertFalse(m.keeps(-8, -4))
        // nothing off the grid: the boss arena, the void around the dungeon
        assertFalse(m.keeps(0, 0)); assertFalse(m.keeps(-14, -6)); assertFalse(m.keeps(-8, 5))
    }

    @Test fun aChunkIsKeptExactlyWhenItTouchesATileThatMatters() {
        val rnd = Random(7)
        repeat(200) {
            val set = rnd.nextLong() and rnd.nextLong() and ((1L shl 36) - 1)
            val m = CullMask(set)
            for (cz in -16..2) for (cx in -16..2) {
                // by blocks: the chunk's 16 against each tile's room and the gap column either side of it
                fun touches(c: Int, t: Int) = c * 16 + 15 >= DungeonFrame.tileMin(t) - 1 && c * 16 <= DungeonFrame.tileMin(t) + 31
                val want = (0 until 36).any { t -> set and (1L shl t) != 0L && touches(cx, t % 6) && touches(cz, t / 6) }
                assertEquals(want, m.keeps(cx, cz), "chunk $cx,$cz of ${names(set)}")
            }
        }
    }

    @Test fun aSlotReadsFromItsGapColumns() {
        // slot E 1,2: the gap column x = -137, along z from -136; its middle z = -121
        val (x, z) = Links.cell('E', 1, 2, Links.MIDDLE)
        assertEquals(DungeonFrame.doorSpot('E', 1, 2), x to z)
        val blocks = HashSet<Pair<Int, Int>>()
        val world = Columns { cx, cz -> (cx to cz) !in blocks }
        assertEquals(Link.GAP, Links.read('E', 1, 2, world))
        blocks += x to z
        assertEquals(Link.DOOR, Links.read('E', 1, 2, world))
        // a door unit is 5 wide: still a door
        for (a in 13..17) blocks += Links.cell('E', 1, 2, a)
        assertEquals(Link.DOOR, Links.read('E', 1, 2, world))
        // a room's blocks across the gap, away from where a door would be
        blocks += Links.cell('E', 1, 2, 22)
        assertEquals(Link.JOIN, Links.read('E', 1, 2, world))
        // and with the middle open (a room with a hole there)
        blocks -= x to z
        assertEquals(Link.JOIN, Links.read('E', 1, 2, world))
        assertEquals(Link.UNKNOWN, Links.read('E', 1, 2) { _, _ -> null })
        // every column a slot is read from is in one chunk
        for (axis in "ES") for (t in 0 until 5) {
            val cells = (Links.BESIDE.toList() + Links.MIDDLE).map { Links.cell(axis, t, 5 - t, it) }
            assertEquals(1, cells.map { (cx, cz) -> (cx shr 4) to (cz shr 4) }.distinct().size, "$axis $t")
        }
    }

    @Test fun slotsAreNumberedOnceEach() {
        val seen = HashSet<Int>()
        for (axis in "ES") for (tz in 0 until 6) for (tx in 0 until 6) {
            val i = Links.index(axis, tx, tz)
            assertEquals(DungeonFrame.isSlot(axis, tx, tz), i >= 0, "$axis $tx,$tz")
            if (i >= 0) assertTrue(seen.add(i) && i < Links.COUNT)
        }
        assertEquals(Links.COUNT, seen.size)
        // the same slot from either side
        assertEquals(Links.index('E', 2, 3), Links.between(2, 3, 1, 0)); assertEquals(Links.index('E', 2, 3), Links.between(3, 3, -1, 0))
        assertEquals(Links.index('S', 2, 3), Links.between(2, 3, 0, 1)); assertEquals(Links.index('S', 2, 3), Links.between(2, 4, 0, -1))
        assertEquals(-1, Links.between(0, 3, -1, 0)); assertEquals(-1, Links.between(5, 3, 1, 0)); assertEquals(-1, Links.between(2, 0, 0, -1)); assertEquals(-1, Links.between(2, 5, 0, 1))
    }
}
