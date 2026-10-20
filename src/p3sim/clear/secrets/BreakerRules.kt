package com.engineerclient.p3sim.clear.secrets

import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.run.DungeonPlan

/**
 * The Dungeonbreaker's rules that belong to the dungeon: it never breaks a block outside a room or on a
 * room's outer ring (so doors, door frames and the walls between rooms stay), a crypt tomb or a Superboom wall, or a secret before it
 * is collected. The item's own rules (bedrock, blocks that carry something, 20 charges, the 10 s regrowth) live with the item
 * ([com.engineerclient.p3sim.SimItems]). Pure.
 */
object BreakerRules {
    /** Hypixel's refusal line for a block that is part of a tomb, a wall, a chest or an uncollected secret. */
    const val REFUSAL = "§cA mystical force prevents you from digging that block!"

    /** The refusal for a block on a room's outer ring or outside any room: door columns and frames, the walls between rooms (45 of 50 ring refusals read this). */
    const val THERE = "§cA mystical force prevents you digging there!"

    /** The refusal for a block inside a room where digging is switched off. */
    const val ROOM_LOCKED = "§cA mystical force prevents you digging in this room!"

    /**
     * Rooms where the Dungeonbreaker refuses every interior block: seen in the recordings in Blood, Fairy, Tic Tac Toe, Boulder, Water Board,
     * Creeper Beams and Teleport Maze (the last four from one scan of a few lines each). Quartz Knight is not one (DR, 139 runs: 68 of its
     * blocks broke, no line). Whether the lock holds for the whole room or only while its puzzle is live is UNMEASURED; the sim locks the
     * whole room.
     */
    val LOCKED_ROOMS: Set<String> = setOf("Blood", "Fairy", "Tic Tac Toe", "Boulder", "Water Board", "Creeper Beams", "Teleport Maze")

    /**
     * The room whose inside this column is, or null: the column of a tile of a room, or a gap column/row strictly inside a room
     * (between two tiles that belong to the same room, and for a corner column all four tiles do).
     */
    fun cellRoom(plan: DungeonPlan, x: Int, z: Int): Int? {
        val tx = tileIndexOrGap(x); val tz = tileIndexOrGap(z)
        if (tx == null || tz == null) return null
        val xs = if (tx.second) listOf(tx.first, tx.first + 1) else listOf(tx.first)
        val zs = if (tz.second) listOf(tz.first, tz.first + 1) else listOf(tz.first)
        var room: Int? = null
        for (a in xs) for (b in zs) {
            val r = plan.roomAt(a, b)?.index ?: return null
            if (room == null) room = r else if (room != r) return null
        }
        return room
    }

    /** (tile index, isGapAfterIt) for a world coordinate, or null outside the grid. */
    private fun tileIndexOrGap(v: Int): Pair<Int, Boolean>? {
        val t = Math.floorDiv(v - DungeonFrame.ORIGIN, DungeonFrame.PITCH)
        if (t !in 0 until DungeonFrame.TILES) return null
        val local = v - DungeonFrame.tileMin(t)
        return if (local <= DungeonFrame.TILE_SIZE - 1) t to false else if (t < DungeonFrame.TILES - 1) t to true else null
    }

    /** Whether the column is in the dungeon's box of tiles (the clear, not the boss). */
    fun inGrid(x: Int, z: Int): Boolean = x in DungeonFrame.ORIGIN..DungeonFrame.DUNGEON_MAX && z in DungeonFrame.ORIGIN..DungeonFrame.DUNGEON_MAX

    /** True when the column is not inside any room, or is on the outer ring of the room it is in (a neighbouring column is not inside the same room). */
    fun isBorderOrOutside(plan: DungeonPlan, x: Int, z: Int): Boolean {
        val room = cellRoom(plan, x, z) ?: return true
        for ((dx, dz) in NEIGHBOURS) if (cellRoom(plan, x + dx, z + dz) != room) return true
        return false
    }

    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /**
     * A reason the Dungeonbreaker may not break the block at (x, y, z), or null. [secretCell] says whether an uncollected secret's
     * block stands there; [groups] holds the crypts and walls that are still standing.
     */
    fun refusal(plan: DungeonPlan, groups: CellGroups, secretCell: (Int, Int, Int) -> Boolean, x: Int, y: Int, z: Int): String? {
        if (groups.standingAt(x, y, z) != null) return REFUSAL
        if (secretCell(x, y, z)) return REFUSAL
        if (isBorderOrOutside(plan, x, z)) return THERE
        if (plan.roomAtBlock(x + 0.5, z + 0.5)?.name in LOCKED_ROOMS) return ROOM_LOCKED
        return null
    }
}
