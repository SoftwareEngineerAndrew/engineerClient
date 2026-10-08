package com.engineerclient.betterpf

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.InfestedBlock
import net.minecraft.world.level.chunk.status.ChunkStatus

/**
 * The per-run part of the dungeon geometry: two blocks per door spot ("dslots", [readDoors]). Every
 * room and the boss arena are the same in every run and already in the server's room library, so
 * nothing else is scanned or sent - the viewer builds the rooms from the library and each doorway
 * from these two blocks. After that, the run only records changes (block updates).
 */
class GeometryCapture(private val emit: (String) -> Unit) {

    // Every place a door can be: the middle of each tile edge inside the grid, as a box
    // (DOOR_ALONG either side of the middle, DOOR_ACROSS blocks into each room) whose centre
    // column is what [readDoors] reads. Each spot: x0, z0, w, d.
    private val doorSpots = ArrayList<IntArray>().apply {
        val across = 2 * DOOR_ACROSS + 1; val along = 2 * DOOR_ALONG + 1
        for (i in 1..5) for (j in 0..5) {
            val gap = GRID_ORIGIN - 1 + 32 * i; val mid = GRID_ORIGIN + 32 * j + 15
            add(intArrayOf(gap - DOOR_ACROSS, mid - DOOR_ALONG, across, along))
            add(intArrayOf(mid - DOOR_ALONG, gap - DOOR_ACROSS, along, across))
        }
    }
    private val doorsDone = HashSet<Int>()

    /**
     * Each door spot, once, as soon as its chunk is here (before anyone opens anything): two blocks
     * in the middle of the gap. y 73 is the door frame's top - only there with a door, and its
     * block is the door's style (stone bricks, cobblestone, planks; red wool for blood). y 69 is the
     * door itself: coal a wither door, red terracotta blood, infested stone the entrance, barrier
     * one falling, air an open doorway. One line of the spots read that tick:
     * {"k":"dslots","t":t,"d":[[x, z, what, top], ...]} - what: "-" none, "n" open, "w" wither,
     * "b" blood, "e" entrance, "f" falling. (Verified against several thousand recorded doors.)
     */
    private fun readDoors(level: ClientLevel, t: Int) {
        val read = ArrayList<String>()
        for ((i, spot) in doorSpots.withIndex()) {
            if (i in doorsDone) continue
            val x = spot[0] + spot[2] / 2; val z = spot[1] + spot[3] / 2
            if (!loaded(level, x, z, 1, 1)) continue
            doorsDone += i
            val top = level.getBlockState(BlockPos(x, 73, z))
            val door = level.getBlockState(BlockPos(x, 69, z)).block
            val what = when {
                top.isAir -> "-"
                door == Blocks.COAL_BLOCK -> "w"
                door == Blocks.DYED_TERRACOTTA.red() -> "b"
                door is InfestedBlock -> "e"
                door == Blocks.BARRIER -> "f"
                else -> "n"
            }
            val topName = if (top.isAir) "" else BuiltInRegistries.BLOCK.getKey(top.block).toString()
            read += "[$x,$z,\"$what\",${jsonString(topName)}]"
        }
        if (read.isNotEmpty()) emit("""{"k":"dslots","t":$t,"d":[${read.joinToString(",")}]}""")
    }

    fun tick(level: ClientLevel, t: Int) {
        if (t % 10 == 0) readDoors(level, t)
    }

    private fun loaded(level: ClientLevel, x0: Int, z0: Int, w: Int, d: Int): Boolean {
        for (cx in (x0 shr 4)..((x0 + w - 1) shr 4)) for (cz in (z0 shr 4)..((z0 + d - 1) shr 4))
            if (level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) == null) return false
        return true
    }

    companion object {
        /** World x/z of tile 0's first block; tile i covers [ORIGIN + 32i, ORIGIN + 32i + 30], gaps between. */
        const val GRID_ORIGIN = -200
        const val DOOR_Y = 67
        const val DOOR_H = 10
        const val DOOR_ALONG = 3
        const val DOOR_ACROSS = 2

        fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (ch in s) when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch < ' ' -> sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", ch.code))
                else -> sb.append(ch)
            }
            return sb.append('"').toString()
        }
    }
}
