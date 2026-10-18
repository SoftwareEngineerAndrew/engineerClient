package com.engineerclient.p3sim.layout

import com.engineerclient.p3sim.clear.layout.DoorFrames
import com.engineerclient.p3sim.clear.layout.DoorStyle
import com.engineerclient.p3sim.clear.layout.Edit
import com.engineerclient.p3sim.clear.layout.Slot
import com.engineerclient.p3sim.pack.layouts.DoorKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One uniform frame per doorway: the same style over the three core planes, bedrock under the opening, a floor taken from the room. */
class DoorFramesTest {
    private val floor: (Int, Int) -> String? = { x, _ -> if (x % 2 == 0) "minecraft:andesite" else null }

    private fun at(e: List<Edit>, x: Int, y: Int, z: Int) = e.lastOrNull { it.x == x && it.y == y && it.z == z }?.state

    @Test fun theThreeCorePlanesShareOneStyleOnBothAxes() {
        for (slot in listOf(Slot('E', 1, 2), Slot('S', 3, 1))) {
            for (style in DoorStyle.entries) {
                val e = DoorFrames.frame(slot, style, floor)
                for (a in 13..17) {
                    for (dd in -1..1) {
                        val (x, z) = DoorFrames.planeCell(slot, a, dd)
                        assertEquals(style.lintel, at(e, x, 73, z), "$slot ${style.name} lintel a=$a dd=$dd")
                        if (a == 13 || a == 17) for ((i, y) in (69..72).withIndex()) assertEquals(style.pillars[i], at(e, x, y, z), "$slot ${style.name} pillar y=$y dd=$dd")
                        else for (y in 69..72) assertEquals(null, at(e, x, y, z), "the opening stays the rooms' own")
                    }
                    for (dd in listOf(-2, 2)) {
                        val (x, z) = DoorFrames.planeCell(slot, a, dd)
                        assertEquals(null, at(e, x, 73, z), "outer planes belong to the rooms")
                    }
                }
            }
        }
    }

    @Test fun theLintelRowIsNeverAir() {
        for (style in DoorStyle.entries) assertTrue(style.lintel != "minecraft:air" && style.pillars.none { it == "minecraft:air" } && style.pillars.size == 4)
    }

    @Test fun bedrockLiesUnderTheOpeningAndTheFloorComesFromTheRoom() {
        val slot = Slot('E', 1, 2)
        val e = DoorFrames.frame(slot, DoorStyle.SPRUCE, floor)
        for (a in 13..17) for (dd in -3..3) {
            val (x, z) = DoorFrames.planeCell(slot, a, dd)
            assertEquals("minecraft:bedrock", at(e, x, 66, z)); assertEquals("minecraft:bedrock", at(e, x, 67, z))
        }
        val (gx, gz) = DoorFrames.planeCell(slot, 15, 0)
        assertEquals(if ((gx - 1) % 2 == 0) "minecraft:andesite" else "minecraft:stone", at(e, gx, 68, gz))
    }

    @Test fun entranceAndBloodKeepTheirStyleAndOthersDrawFromTheFive() {
        val slot = Slot('S', 2, 2)
        assertEquals(DoorStyle.COBBLESTONE_ENTRANCE, DoorFrames.styleFor(DoorKind.ENTRANCE, slot, 7))
        assertEquals(DoorStyle.BLOOD, DoorFrames.styleFor(DoorKind.BLOOD, slot, 7))
        val seen = HashSet<DoorStyle>()
        for (seed in 0L..400L) for (kind in listOf(DoorKind.WITHER, DoorKind.NORMAL, DoorKind.FAIRY)) {
            val s = DoorFrames.styleFor(kind, slot, seed)
            assertEquals(s, DoorFrames.styleFor(kind, slot, seed), "same seed, same style")
            seen += s
        }
        assertEquals(setOf(DoorStyle.STONE_BRICKS, DoorStyle.SPRUCE, DoorStyle.STONE_BRICKS_ACACIA, DoorStyle.COBBLESTONE_ACACIA), seen)
    }
}
