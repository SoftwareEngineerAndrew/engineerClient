package com.engineerclient.p3sim.layout

import com.engineerclient.p3sim.clear.layout.DoorGeometry
import com.engineerclient.p3sim.clear.layout.DoorPlanner
import com.engineerclient.p3sim.clear.layout.Slot
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.layouts.DoorKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The door rules on synthetic rooms: carving, sealing, scrubbing, bodies; the world after the edits is checked cell by cell. */
class DoorPlannerTest {
    private fun cell(w: Map<Triple<Int, Int, Int>, String>, x: Int, y: Int, z: Int) = w[Triple(x, y, z)]

    /** The cells of one side's opening, planes [depths], as a list of (x, y, z). */
    private fun opening(tx: Int, tz: Int, side: Char, depths: IntRange) = depths.flatMap { DoorGeometry.openingCells(tx, tz, side, it) }

    @Test fun aWitherDoorBetweenTwoWallsIsCarvedAndShut() {
        val a = TestRooms.instance("Room_A", 1, 1); val b = TestRooms.instance("Room_B", 2, 1)
        val edits = DoorPlanner.plan(listOf(a, b), mapOf(Slot('E', 1, 1) to DoorKind.WITHER))
        val w = TestRooms.world(listOf(a, b), edits)
        // Plane 0 of both rooms and the gap column hold the closed door; plane 1 behind it is open.
        val (cx, cz) = DungeonFrame.doorSpot('E', 1, 1)
        for (x in cx - 1..cx + 1) for (z in cz - 1..cz + 1) for (y in 69..72) assertEquals("minecraft:coal_block", cell(w, x, y, z), "$x,$y,$z")
        for ((x, y, z) in opening(1, 1, 'E', 1..1) + opening(2, 1, 'W', 1..1)) assertEquals(null, cell(w, x, y, z), "plane 1 $x,$y,$z")
        // The frame stands in the gap column.
        val style = com.engineerclient.p3sim.clear.layout.DoorFrames.styleFor(DoorKind.WITHER, Slot('E', 1, 1), 0L)
        assertEquals(style.lintel, cell(w, cx, 73, cz))
        assertEquals(style.pillars[1], cell(w, cx, 70, cz - 2))
        // The floor of the gap column is the room's own floor; bedrock lies under it.
        assertEquals(TestRooms.FLOOR, cell(w, cx, 68, cz))
        assertEquals("minecraft:bedrock", cell(w, cx, 66, cz))
    }

    @Test fun aConnectedDoorHasOneStyleOnItsThreeCorePlanes() {
        val a = TestRooms.instance("Room_A", 1, 1); val b = TestRooms.instance("Room_B", 2, 1)
        val w = TestRooms.world(listOf(a, b), DoorPlanner.plan(listOf(a, b), mapOf(Slot('E', 1, 1) to DoorKind.NORMAL), 5L))
        val (cx, cz) = DungeonFrame.doorSpot('E', 1, 1)
        for (az in listOf(cz - 2, cz - 1, cz, cz + 1, cz + 2)) {
            val lintel = cell(w, cx, 73, az)
            assertTrue(lintel != null, "no hole in the lintel")
            assertEquals(lintel, cell(w, cx - 1, 73, az)); assertEquals(lintel, cell(w, cx + 1, 73, az))
        }
        for (y in 69..72) { assertEquals(cell(w, cx, y, cz - 2), cell(w, cx - 1, y, cz - 2)); assertEquals(cell(w, cx, y, cz - 2), cell(w, cx + 1, y, cz - 2)) }
    }

    @Test fun aPlainDoorwayIsAirAllTheWayThrough() {
        val a = TestRooms.instance("Room_A", 1, 1); val b = TestRooms.instance("Room_B", 2, 1)
        val w = TestRooms.world(listOf(a, b), DoorPlanner.plan(listOf(a, b), mapOf(Slot('E', 1, 1) to DoorKind.NORMAL)))
        val (cx, cz) = DungeonFrame.doorSpot('E', 1, 1)
        for (x in cx - 2..cx + 2) for (z in cz - 1..cz + 1) for (y in 69..72) assertEquals(null, cell(w, x, y, z), "$x,$y,$z")
    }

    @Test fun anOpenSideWithoutADoorIsWalledUp() {
        val a = TestRooms.instance("Room_A", 1, 1, open = setOf('E'))
        val w = TestRooms.world(listOf(a), DoorPlanner.plan(listOf(a), emptyMap()))
        for ((x, y, z) in opening(1, 1, 'E', 0..1)) assertEquals(TestRooms.WALL, cell(w, x, y, z), "$x,$y,$z")
    }

    @Test fun anUnknownDoorIsAWall() {
        val a = TestRooms.instance("Room_A", 1, 1, open = setOf('E'))
        val b = TestRooms.instance("Room_B", 2, 1, open = setOf('W'))
        val w = TestRooms.world(listOf(a, b), DoorPlanner.plan(listOf(a, b), mapOf(Slot('E', 1, 1) to DoorKind.UNKNOWN)))
        for ((x, y, z) in opening(1, 1, 'E', 0..1) + opening(2, 1, 'W', 0..1)) assertEquals(TestRooms.WALL, cell(w, x, y, z))
        val (cx, cz) = DungeonFrame.doorSpot('E', 1, 1)
        assertEquals(null, cell(w, cx, 70, cz), "no frame in a wall")
    }

    @Test fun aRecordedClosedDoorOnASideTheLayoutLeavesAWallBecomesWall() {
        for (door in listOf("minecraft:coal_block", "minecraft:red_terracotta", "minecraft:infested_chiseled_stone_bricks")) {
            val a = TestRooms.instance("Room_A", 1, 1, closedWith = mapOf('S' to door))
            val w = TestRooms.world(listOf(a), DoorPlanner.plan(listOf(a), emptyMap()))
            for ((x, y, z) in opening(1, 1, 'S', 0..0)) assertEquals(TestRooms.WALL, cell(w, x, y, z), "$door $x,$y,$z")
        }
    }

    @Test fun aWallThatIsNotADoorIsLeftAlone() {
        // Plain stone in the opening of a closed side stays: only door bodies are scrubbed.
        val a = TestRooms.instance("Room_A", 1, 1, closedWith = mapOf('S' to "minecraft:stone"))
        val edits = DoorPlanner.plan(listOf(a), emptyMap())
        assertTrue(edits.isEmpty(), "edits: ${edits.size}")
    }

    @Test fun aThickWallIsCarvedThroughToo() {
        for (thick in 1..3) {
            val a = TestRooms.instance("Room_A", 1, 1, thickWalls = thick)
            val b = TestRooms.instance("Room_B", 2, 1, thickWalls = thick)
            val w = TestRooms.world(listOf(a, b), DoorPlanner.plan(listOf(a, b), mapOf(Slot('E', 1, 1) to DoorKind.NORMAL)))
            for ((x, y, z) in opening(1, 1, 'E', 0..thick - 1) + opening(2, 1, 'W', 0..thick - 1)) assertEquals(null, cell(w, x, y, z), "thick $thick $x,$y,$z")
        }
    }

    @Test fun aRotatedRoomsOpeningFollowsTheTurn() {
        // Canonical north is turned one quarter clockwise by WEST: the opening faces east.
        val a = TestRooms.instance("Room_A", 1, 1, rot = Rot.WEST, open = setOf('N'))
        val world = TestRooms.world(listOf(a), emptyList())
        for ((x, y, z) in opening(1, 1, 'E', 0..1)) assertEquals(null, cell(world, x, y, z), "east opening $x,$y,$z")
        for ((x, y, z) in opening(1, 1, 'N', 0..1)) assertEquals(TestRooms.WALL, cell(world, x, y, z), "north is a wall now $x,$y,$z")
        // And the planner reads the same side: with a door to the east nothing needs carving, and without one the east opening is sealed.
        val b = TestRooms.instance("Room_B", 2, 1, open = setOf('W'))
        val withDoor = TestRooms.world(listOf(a, b), DoorPlanner.plan(listOf(a, b), mapOf(Slot('E', 1, 1) to DoorKind.NORMAL)))
        for ((x, y, z) in opening(1, 1, 'E', 0..1)) assertEquals(null, cell(withDoor, x, y, z))
        val without = TestRooms.world(listOf(a), DoorPlanner.plan(listOf(a), emptyMap()))
        for ((x, y, z) in opening(1, 1, 'E', 0..1)) assertEquals(TestRooms.WALL, cell(without, x, y, z), "sealed $x,$y,$z")
    }

    @Test fun theWallBlockIsTheCommonOneAroundTheOpening() {
        val vol = TestRooms.volume(open = setOf('E'), wall = "minecraft:cobblestone")
        val d = TestRooms.design("Room_C", vol, setOf('E'))
        val inst = com.engineerclient.p3sim.clear.layout.RoomInstance(d, vol, d.place(Rot.SOUTH, listOf(com.engineerclient.p3sim.pack.Tile(1, 1))))
        assertEquals("minecraft:cobblestone", DoorPlanner.wallBlock(inst, 1, 1, 'E'))
        val w = TestRooms.world(listOf(inst), DoorPlanner.plan(listOf(inst), emptyMap()))
        for ((x, y, z) in opening(1, 1, 'E', 0..1)) assertEquals("minecraft:cobblestone", cell(w, x, y, z))
    }

    @Test fun theRoomsOwnSealCellsAreWrittenBeforeTheFill() {
        val vol = TestRooms.volume(open = setOf('E'))
        val base = TestRooms.design("Room_D", vol, setOf('E'))
        val seal = com.engineerclient.p3sim.pack.rooms.SlotSeal(null, false, listOf(
            // Canonical E plane 0 is x = 30; offset 15, y 70.
            com.engineerclient.p3sim.pack.rooms.SealCell(com.engineerclient.p3sim.pack.Pos(30, 70, 15), "minecraft:glass"),
        ))
        val slots = base.slots.map { if (it.side == 'E') it.copy(seal = seal) else it }
        val d = com.engineerclient.p3sim.layout.TestRooms.withSlots(base, slots)
        val inst = com.engineerclient.p3sim.clear.layout.RoomInstance(d, vol, d.place(Rot.SOUTH, listOf(com.engineerclient.p3sim.pack.Tile(1, 1))))
        val w = TestRooms.world(listOf(inst), DoorPlanner.plan(listOf(inst), emptyMap()))
        val (x, z) = DoorGeometry.sideCell(1, 1, 'E', 15, 0)
        assertEquals("minecraft:glass", cell(w, x, 70, z))
        // The rest of the opening is filled with wall.
        val (x2, z2) = DoorGeometry.sideCell(1, 1, 'E', 14, 0)
        assertEquals(TestRooms.WALL, cell(w, x2, 70, z2))
    }

    @Test fun everyDoorOfASmallLayoutIsPassableAndEveryWallIsClosed() {
        // A 2 x 2 block of rooms with a ring of doors but one wall: the world after the edits has no air in a wall and no wall in a doorway.
        val rooms = listOf(
            TestRooms.instance("R00", 0, 0, open = setOf('E', 'S')), TestRooms.instance("R10", 1, 0, open = setOf('W')),
            TestRooms.instance("R01", 0, 1), TestRooms.instance("R11", 1, 1, open = setOf('N')),
        )
        val doors = mapOf(Slot('E', 0, 0) to DoorKind.NORMAL, Slot('S', 1, 0) to DoorKind.WITHER, Slot('S', 0, 0) to DoorKind.NONE, Slot('E', 0, 1) to DoorKind.BLOOD)
        val w = TestRooms.world(rooms, DoorPlanner.plan(rooms, doors))
        for ((slot, kind) in doors) {
            val (cx, cz) = DungeonFrame.doorSpot(slot.axis, slot.tx, slot.tz)
            val centre = cell(w, cx, 70, cz)
            when (kind) {
                DoorKind.NONE -> {
                    for ((tx, tz, side) in DoorGeometry.sidesOf(slot)) for ((x, y, z) in opening(tx, tz, side, 0..1)) assertTrue(cell(w, x, y, z) != null, "wall must be solid: $slot $x,$y,$z")
                    assertEquals(null, centre)
                }
                DoorKind.NORMAL -> assertEquals(null, centre)
                DoorKind.WITHER -> assertEquals("minecraft:coal_block", centre)
                DoorKind.BLOOD -> assertEquals("minecraft:red_terracotta", centre)
                else -> {}
            }
        }
    }
}
