package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.layouts.DoorKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The engine's made-up dungeon, and the arena region's clip. */
class StubDungeonTest {
    @AfterEach fun reset() = WorldRegions.clear()

    @Test fun theStubIsASnakeOf36RoomsWithOneDoorwayBetweenNeighbours() {
        val p = StubDungeon.plan(5)
        assertEquals(36, p.rooms.size); assertEquals(36, p.tileUnits)
        assertEquals(60, p.doors.size)
        assertEquals(35, p.doors.count { it.kind.connected }, "one doorway between each two neighbours of the snake")
        assertEquals(RoomType.ENTRANCE, p.rooms.first().type); assertEquals(RoomType.BLOOD, p.rooms.last().type)
        assertEquals(DoorKind.ENTRANCE, p.doors.first { it.roomA == 0 && it.roomB == 1 || it.roomA == 1 && it.roomB == 0 }.kind)
        assertEquals((0 until 36).toList(), p.path)
        val tiles = (0 until 6).flatMap { z -> (0 until 6).map { x -> StubDungeon.snakeIndex(x, z) } }.sorted()
        assertEquals((0 until 36).toList(), tiles, "the snake visits every tile once")
        // A plan the run state can be made from.
        val s = RunState(p, RunBus()) { 0 }
        assertEquals(36, s.rooms.size); assertEquals(36, s.counters.total)
    }

    @Test fun theStubRegionLiesInsideTheDungeonBox() {
        val r = StubDungeon.region()
        assertEquals(DungeonFrame.ORIGIN, r.minX); assertEquals(DungeonFrame.DUNGEON_MAX, r.maxX)
        assertTrue(r.intersects(-200, -200) && r.intersects(-16, -16))
        assertFalse(r.intersects(0, 0), "the arena's chunks are not the dungeon's")
    }

    @Test fun theRoomsAreRoofedAndJoinedOnlyWhereTheSnakeGoes() {
        val k = StubDungeon::kindAt
        val centre0 = DungeonFrame.tileCentre(0); val centre1 = DungeonFrame.tileCentre(1)
        assertEquals(StubDungeon.Kind.AIR, k(centre0, 70, centre0), "inside a room")
        assertEquals(StubDungeon.Kind.BRICK, k(centre0, StubDungeon.FLOOR_Y, centre0)); assertEquals(StubDungeon.Kind.BRICK, k(centre0, StubDungeon.CEILING_Y, centre0))
        assertEquals(StubDungeon.Kind.BEDROCK, k(centre0, 66, centre0)); assertEquals(StubDungeon.Kind.AIR, k(centre0, 40, centre0))
        // Tile (0,0) and (1,0) are the first two rooms of the snake: a doorway on the gap column at z = tile centre, y 69..72, a lintel at 73.
        val gapX = DungeonFrame.ORIGIN + 31
        for (y in 69..72) for (dz in -1..1) assertEquals(StubDungeon.Kind.AIR, k(gapX, y, centre0 + dz), "door at y $y")
        assertEquals(StubDungeon.Kind.BRICK, k(gapX, 73, centre0)); assertEquals(StubDungeon.Kind.BRICK, k(gapX, 70, centre0 + 2))
        // Tile (0,0) and (0,1) are not neighbours on the snake: the wall between them is shut.
        val gapZ = DungeonFrame.ORIGIN + 31
        for (y in 69..72) assertEquals(StubDungeon.Kind.BRICK, k(centre0, y, gapZ))
        // The outer edge of the dungeon is closed, and nothing is outside the box.
        for (y in 69..72) assertEquals(StubDungeon.Kind.BRICK, k(DungeonFrame.ORIGIN, y, centre0))
        assertEquals(StubDungeon.Kind.AIR, k(DungeonFrame.DUNGEON_MAX + 1, 70, centre0)); assertEquals(StubDungeon.Kind.AIR, k(0, 70, 0))
        // Light emitters under the ceiling.
        val lanterns = (DungeonFrame.ORIGIN until DungeonFrame.ORIGIN + 31).sumOf { x -> (DungeonFrame.ORIGIN until DungeonFrame.ORIGIN + 31).count { z -> k(x, StubDungeon.CEILING_Y - 1, z) == StubDungeon.Kind.LANTERN } }
        assertEquals(16, lanterns, "four by four sea lanterns in a room")
        assertTrue(centre1 > centre0)
    }

    @Test fun theArenaIsClippedToItsOwnRectangleOnlyWhileItsRegionIsRegistered() {
        assertFalse(ArenaRegion.active)
        ArenaRegion.register()
        assertTrue(ArenaRegion.active)
        assertEquals(-8, ArenaRegion.clippedMin(-16, true)); assertEquals(-16, ArenaRegion.clippedMin(-16, false))
        assertEquals(-8, ArenaRegion.clippedMin(-8, true)); assertEquals(0, ArenaRegion.clippedMin(0, true))
        assertFalse(ArenaRegion.keeps(-12, 50, true), "the extension's corner in dungeon tile (5,5) is not written")
        assertFalse(ArenaRegion.keeps(50, -10, true))
        assertTrue(ArenaRegion.keeps(-8, -8, true)); assertTrue(ArenaRegion.keeps(143, 100, true))
        assertTrue(ArenaRegion.keeps(-12, -12, false), "unclipped, the boss practice has it as before")
        WorldRegions.noteUsed(ArenaRegion.NAME); WorldRegions.worldStopped()
        assertFalse(ArenaRegion.active, "a world that is gone takes its clip with it")
    }

    @Test fun theArenaRegionTouchesOnlyChunksFromItsCorner() {
        assertTrue(ArenaRegion.intersects(-16, -16), "the shared chunk: the arena writes its part, the dungeon its own")
        assertFalse(ArenaRegion.intersects(-32, -16)); assertFalse(ArenaRegion.intersects(-16, -32))
        assertEquals(DungeonFrame.ARENA_MIN, ArenaRegion.CLIP_MIN)
        assertTrue(DungeonFrame.DUNGEON_MAX < ArenaRegion.CLIP_MIN, "the two rectangles never overlap")
    }
}
