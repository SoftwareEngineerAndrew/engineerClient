package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.FloorRules
import com.engineerclient.p3sim.clear.bots.RoomGrids
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.RoomVolume
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomGridTest {
    @Test fun blocksAreSortedIntoStandOnAndWalkThrough() {
        assertTrue(FloorRules.supports("minecraft:stone")); assertTrue(FloorRules.supports("minecraft:bedrock")); assertTrue(FloorRules.supports("minecraft:oak_planks"))
        assertTrue(FloorRules.supports("minecraft:smooth_stone_slab[type=top]")); assertTrue(FloorRules.supports("minecraft:cobblestone_stairs[facing=east]"))
        assertFalse(FloorRules.supports("minecraft:lava[level=0]")); assertFalse(FloorRules.supports("minecraft:air")); assertFalse(FloorRules.supports("minecraft:red_carpet"))
        assertTrue(FloorRules.passable("minecraft:air")); assertTrue(FloorRules.passable("minecraft:red_carpet")); assertTrue(FloorRules.passable("minecraft:stone_pressure_plate[powered=false]"))
        assertTrue(FloorRules.passable("minecraft:oak_stairs[facing=north]")); assertTrue(FloorRules.passable("minecraft:stone_slab[type=bottom]"))
        assertFalse(FloorRules.passable("minecraft:stone_slab[type=top]")); assertFalse(FloorRules.passable("minecraft:stone")); assertFalse(FloorRules.passable("minecraft:bedrock"))
        assertFalse(FloorRules.passable("minecraft:iron_bars")); assertFalse(FloorRules.passable("minecraft:cobblestone_wall")); assertFalse(FloorRules.passable("minecraft:lava"))
        assertTrue(FloorRules.passable("minecraft:snow[layers=1]")); assertFalse(FloorRules.passable("minecraft:snow_block"))
    }

    /** A 31 x 31 room: floor at y 68, a wall across it at canonical x = 15 for z 0..20 (four high), lava at x 25..27, z 25..27, a carpet at (3, 69, 3). */
    private fun volume(): RoomVolume {
        val palette = listOf("minecraft:air", "minecraft:stone", "minecraft:lava[level=0]", "minecraft:red_carpet")
        val w = 31; val d = 31; val h = 12; val y0 = 64
        val cells = ShortArray(w * d * h)
        fun set(x: Int, y: Int, z: Int, p: Int) { cells[((y - y0) * d + z) * w + x] = p.toShort() }
        for (x in 0 until w) for (z in 0 until d) set(x, 68, z, 1)
        for (z in 0..20) for (y in 69..72) set(15, y, z, 1)
        for (x in 25..27) for (z in 25..27) set(x, 68, z, 2)
        set(3, 69, 3, 3)
        return RoomVolume(w, d, h, y0, palette, cells)
    }

    @Test fun theGridOfAPlacedRoomHasItsWallsAndLavaAtTheRightPlaceForEveryRotation() {
        for (rot in Rot.entries) {
            val placement = Placement.of("1x1", 31, 31, rot, listOf(Tile(2, 3)))
            val grid = RoomGrids.fromVolume(volume(), placement)
            fun cell(x: Int, z: Int) = placement.toWorld(x, 69, z).let { grid.walkable(it.x, it.z) }
            assertFalse(cell(15, 5), "the wall ($rot)")
            assertTrue(cell(14, 5) && cell(16, 5), "beside the wall ($rot)")
            assertTrue(cell(15, 25), "past the end of the wall ($rot)")
            assertFalse(cell(26, 26), "lava ($rot)")
            assertTrue(cell(3, 3), "a carpet is walked on ($rot)")
            assertEquals(31 * 31 - 21 - 9, grid.openCount, "open columns ($rot)")
            val a = placement.toWorld(5, 69, 5).let { com.engineerclient.p3sim.clear.bots.Pt(it.x + 0.5, it.z + 0.5) }
            val b = placement.toWorld(25, 69, 5).let { com.engineerclient.p3sim.clear.bots.Pt(it.x + 0.5, it.z + 0.5) }
            assertFalse(grid.lineClear(a, b), "the wall is between ($rot)")
            val path = grid.path(a, b)!!
            assertTrue(path.size >= 2, "the way round has a corner ($rot)")
        }
    }
}
