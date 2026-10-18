package com.engineerclient.p3sim.clear.layout

import com.engineerclient.p3sim.pack.DoorModel
import com.engineerclient.p3sim.pack.layouts.DoorKind
import kotlin.random.Random

/**
 * The look of a doorway's frame. On Hypixel one door is ONE style across its three core planes (the gap column and the plane on each
 * side of it): the same lintel and the same pillars in all three. Only the two outer planes carry each room's own arch details.
 * Measured over 209 recorded doors in nine runs: five styles for ordinary and wither doors, a cobblestone one that is always the
 * entrance, and the red-wool one that is always the blood door.
 *
 * [lintel] is the row at y 73 across the five frame cells; [pillars] are the blocks at the two frame columns for y 69 to 72.
 */
enum class DoorStyle(val lintel: String, val pillars: List<String>, val weight: Int) {
    STONE_BRICKS("minecraft:stone_bricks", List(4) { "minecraft:stone_bricks" }, 33),
    SPRUCE("minecraft:spruce_planks", List(4) { "minecraft:spruce_planks" }, 21),
    STONE_BRICKS_ACACIA("minecraft:stone_bricks", listOf("minecraft:stone_bricks", ACACIA, ACACIA, "minecraft:stone_bricks"), 16),
    /** Always the entrance door. */
    COBBLESTONE_ENTRANCE("minecraft:cobblestone", listOf("minecraft:stone_bricks", "minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:stone_bricks"), 0),
    COBBLESTONE_ACACIA("minecraft:cobblestone", listOf("minecraft:cobblestone", ACACIA, ACACIA, "minecraft:cobblestone"), 17),
    /** Always the blood door. */
    BLOOD("minecraft:red_wool", listOf("minecraft:nether_bricks", "minecraft:red_wool", "minecraft:red_wool", "minecraft:nether_bricks"), 0),
}

private const val ACACIA = "minecraft:acacia_wood[axis=y]"

object DoorFrames {
    /** Core planes on each side of the gap column that share one style, and the depth of bedrock under a connected doorway. */
    const val CORE_PLANES = 1
    const val FLOOR_PLANES = 3
    const val BEDROCK_Y_MIN = 66
    const val BEDROCK_Y_MAX = 67
    private const val BEDROCK = "minecraft:bedrock"
    private const val FLOOR_FALLBACK = "minecraft:stone"

    /**
     * The style of the door on [slot]: the entrance and blood doors have theirs; every other door draws from the five ordinary styles
     * by their measured shares, from [seed] and the slot, so a seed always gives the same dungeon.
     */
    fun styleFor(kind: DoorKind, slot: Slot, seed: Long): DoorStyle {
        if (kind == DoorKind.ENTRANCE) return DoorStyle.COBBLESTONE_ENTRANCE
        if (kind == DoorKind.BLOOD) return DoorStyle.BLOOD
        val pool = DoorStyle.entries.filter { it.weight > 0 }
        val key = (slot.axis.code * 36 + slot.tx * 6 + slot.tz).toLong()
        var pick = Random(seed xor (key * 0x9E3779B97F4A7C15uL.toLong())).nextInt(pool.sumOf { it.weight })
        for (s in pool) { pick -= s.weight; if (pick < 0) return s }
        return pool.last()
    }

    /** World (x, z) of the cell at offset [a] along [slot], [dd] blocks from the gap column across it (negative = toward the west or north room). */
    fun planeCell(slot: Slot, a: Int, dd: Int): Pair<Int, Int> {
        val (x, z) = DoorGeometry.gapCell(slot, a)
        return if (slot.axis == 'E') (x + dd) to z else x to (z + dd)
    }

    /**
     * The frame of one connected doorway: [style] written over the gap column and the plane on each side (identical in all three), the
     * floor of the gap column copied from the plane beside it ([floorAt] gives the room's own floor block there, or null for none),
     * and bedrock under the doorway at y 66 and 67 for [FLOOR_PLANES] planes each way, as on Hypixel. The opening itself and the two
     * outer planes are not touched: the rooms bring those.
     */
    fun frame(slot: Slot, style: DoorStyle, floorAt: (Int, Int) -> String?): List<Edit> = buildList {
        for (a in DoorGeometry.FRAME_MIN..DoorGeometry.FRAME_MAX) {
            for (dd in -CORE_PLANES..CORE_PLANES) {
                val (x, z) = planeCell(slot, a, dd)
                add(Edit(x, DoorModel.LINTEL_Y, z, style.lintel))
                if (a == DoorGeometry.FRAME_MIN || a == DoorGeometry.FRAME_MAX) {
                    for ((i, y) in (DoorModel.BODY_Y_MIN..DoorModel.BODY_Y_MAX).withIndex()) add(Edit(x, y, z, style.pillars[i]))
                }
            }
            val (gx, gz) = planeCell(slot, a, 0)
            val (nx, nz) = planeCell(slot, a, -1)
            add(Edit(gx, DoorModel.THRESHOLD_Y, gz, floorAt(nx, nz) ?: FLOOR_FALLBACK))
            for (dd in -FLOOR_PLANES..FLOOR_PLANES) {
                val (x, z) = planeCell(slot, a, dd)
                for (y in BEDROCK_Y_MIN..BEDROCK_Y_MAX) add(Edit(x, y, z, BEDROCK))
            }
        }
    }
}
