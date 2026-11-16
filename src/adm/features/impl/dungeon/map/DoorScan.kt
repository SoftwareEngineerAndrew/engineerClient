package com.adm.features.impl.dungeon.map

import com.adm.AdmMod.mc
import com.adm.events.LevelEvent
import com.adm.events.MapUpdateEvent
import com.adm.events.RoomEnterEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.features.impl.dungeon.map.tile.DoorRotation
import com.adm.features.impl.dungeon.map.tile.DoorType
import com.adm.features.impl.dungeon.map.tile.DungeonDoor
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.Colors
import com.adm.utils.IVec2
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus

/**
 * Doors read from the world ahead of time (dtMap's door scan, BSD 3-Clause) and kept hidden; once a room is on the map
 * its doors go on too, so the rooms behind them show as unknown without waiting for the map item.
 */
object DoorScan {
    private const val W = 6
    /** Each edge's door type by tile * 2 (+1 for the one below); null value: a wall. */
    private val read = HashMap<Int, DoorType?>()
    private var ticks = 0

    private val RED_TERRACOTTA by lazy { Blocks.DYED_TERRACOTTA.pick(DyeColor.RED) }

    init {
        on<LevelEvent.Load> { read.clear() }
        on<RoomEnterEvent> { reveal() }
        on<MapUpdateEvent> { reveal() }
        on<TickEvent.End> {
            if (!DungeonUtils.inDungeons || DungeonUtils.inBoss || ++ticks % 10 != 0) return@on
            if (scan(mc.level ?: return@on)) reveal()
        }
    }

    private fun scan(level: Level): Boolean {
        var found = false
        for (t in 0 until W * W) for (right in booleanArrayOf(true, false)) {
            val x = t % W; val z = t / W
            if (right && x + 1 >= W || !right && z + 1 >= W) continue
            val key = t * 2 + if (right) 0 else 1
            if (read.containsKey(key)) continue
            val a = DungeonScan.tiles[t].room
            val b = DungeonScan.tiles[if (right) t + 1 else t + W].room
            if (a == null && b == null || a != null && a === b) continue
            val wx = x * 32 - 185 + if (right) 16 else 0
            val wz = z * 32 - 185 + if (right) 0 else 16
            val chunk = level.chunkSource.getChunk(wx shr 4, wz shr 4, ChunkStatus.FULL, false) ?: continue
            val height = topY(chunk, wx, wz) ?: continue
            read[key] = when {
                height == 73 || height == 81 -> when (chunk.getBlockState(BlockPos(wx, 69, wz)).block) {
                    Blocks.COAL_BLOCK -> if (a?.type == RoomType.FAIRY || b?.type == RoomType.FAIRY) DoorType.Fairy else DoorType.Wither
                    RED_TERRACOTTA -> DoorType.Blood
                    else -> if (a?.type == RoomType.FAIRY || b?.type == RoomType.FAIRY) DoorType.Fairy else DoorType.Normal
                }
                // the entrance's door sometimes has blocks above it
                height > 73 && (a?.type == RoomType.ENTRANCE || b?.type == RoomType.ENTRANCE) -> DoorType.Normal
                else -> null
            }
            found = found || read[key] != null
        }
        return found
    }

    /** Puts on the map the doors of every room it shows. */
    private fun reveal() {
        if (!DungeonUtils.inDungeons || read.isEmpty()) return
        var added = false
        for ((key, type) in read) {
            type ?: continue
            val t = key / 2
            val rotation = if (key % 2 == 0) DoorRotation.Horizontal else DoorRotation.Vertical
            val n = t + rotation.offset.x + rotation.offset.z * W
            if (!shown(DungeonScan.tiles[t].room) && !shown(DungeonScan.tiles[n].room)) continue
            val position = IVec2(t % W, t / W)
            val id = IVec2(-12 + 2 * position.x + rotation.offset.x, -12 + 2 * position.z + rotation.offset.z)
            if (DungeonScan.doors.containsKey(id)) continue
            DungeonScan.doors[id] = DungeonDoor(position, rotation, type, Colors.WHITE)
            added = true
        }
        if (added) DungeonScan.updateViewableDoors()
    }

    private fun shown(room: DungeonRoom?) = room?.isViewable == true

    /** The highest non-air block from y 160 down; null in a section not loaded yet. */
    private fun topY(chunk: ChunkAccess, x: Int, z: Int): Int? {
        val pos = BlockPos.MutableBlockPos()
        for (y in 160 downTo 11) {
            val block = chunk.getBlockState(pos.set(x, y, z)).block
            if (block == Blocks.VOID_AIR) return null
            if (block != Blocks.AIR) return y
        }
        return 0
    }
}
