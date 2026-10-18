package com.engineerclient.p3sim.pack

import com.engineerclient.p3sim.pack.layouts.DoorKind

/**
 * What a door IS, as blocks: the single source for the layout module (which writes the closed doors while the chunks generate) and
 * the doors/state module (which turns them into barrier and then air). Pure; nothing here reads a pack.
 *
 * There is no doors pack: the rooms pack holds the doorways of the rooms themselves (the `slots` of a design: the opening is
 * 3 wide and 4 tall, door band y 69..72, threshold y 68, lintel y 73, frame columns at offsets 13 and 17 of the tile), and everything
 * Odin and the clear logic care about is the BODY below. The decoration around it (eight cosmetic styles on Hypixel: wool, brick,
 * planks...) changes nothing but looks, so the layout module builds a plain frame itself (stone bricks) where a slot needs one.
 * Measured on Hypixel (FORMAT-layouts 6.1, FORMAT-runstate 4): the body is a 3 x 4 x 3 volume of 36 blocks around the slot's
 * centre, `x in cx-1..cx+1`, `z in cz-1..cz+1`, `y in 69..72`; a wither door is coal, the blood door red terracotta, the entrance
 * door infested stone; on use all 36 become barrier in one tick and air 14 ticks later (15 for the entrance). A plain door has no
 * body (an open doorway). The frame top (lintel) at y 73 is what Odin reads as "a door is here".
 */
object DoorModel {
    const val BODY_Y_MIN = DungeonFrame.DOOR_Y_MIN   // 69
    const val BODY_Y_MAX = DungeonFrame.DOOR_Y_MAX   // 72
    /** The frame top Odin reads (air there = no door). */
    const val LINTEL_Y = 73
    /** The floor of the opening. */
    const val THRESHOLD_Y = 68
    /** Blocks on each side of the centre column (the body is 3 wide and 3 deep). */
    const val HALF = 1
    /** Blocks in one door body: 3 x 4 x 3. */
    const val BODY_SIZE = 36
    /** The block a falling door holds for [com.engineerclient.p3sim.pack.runstate.DoorRules.barrierTicks] ticks. */
    const val FALLING_BLOCK = "minecraft:barrier"
    const val AIR = "minecraft:air"

    /** The block state string of a CLOSED door of [kind]; null for kinds with no body (plain doorway, wall, unknown). */
    fun bodyBlock(kind: DoorKind): String? = when (kind) {
        DoorKind.WITHER, DoorKind.FAIRY -> "minecraft:coal_block"
        DoorKind.BLOOD -> "minecraft:red_terracotta"
        DoorKind.ENTRANCE -> "minecraft:infested_chiseled_stone_bricks"
        else -> null
    }

    /** The 36 cells of the body around the slot centre ([cx], [cz]), in a stable order: y, then z, then x. */
    fun bodyCells(cx: Int, cz: Int): List<Pos> = buildList(BODY_SIZE) {
        for (y in BODY_Y_MIN..BODY_Y_MAX) for (z in cz - HALF..cz + HALF) for (x in cx - HALF..cx + HALF) add(Pos(x, y, z))
    }

    /** Whether the block ([x], [y], [z]) is inside the body of the door centred on ([cx], [cz]). */
    fun inBody(cx: Int, cz: Int, x: Int, y: Int, z: Int): Boolean =
        y in BODY_Y_MIN..BODY_Y_MAX && x in cx - HALF..cx + HALF && z in cz - HALF..cz + HALF
}
