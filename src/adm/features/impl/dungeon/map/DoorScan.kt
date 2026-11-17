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
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus

/** Reads doors from the world before the map shows them, so the rooms behind a seen room's doors show as unknown early. */
object DoorScan {
    // edge e = tile * 2 (+1 for the edge below the tile, else the one to its right)
    private const val EDGES = 72
    private const val UNLOADED = -1
    private val decided = BooleanArray(EDGES)
    private val found = arrayOfNulls<DoorType>(EDGES)
    @Volatile private var anyDoor = false
    private var ticks = 0
    private val probe = BlockPos.MutableBlockPos()
    private val bloodClay: Block by lazy { Blocks.DYED_TERRACOTTA.pick(DyeColor.RED) }

    init {
        on<LevelEvent.Load> {
            decided.fill(false)
            found.fill(null)
            anyDoor = false
        }
        on<RoomEnterEvent> { reveal() }
        on<MapUpdateEvent> { reveal() }
        on<TickEvent.End> {
            if (!DungeonUtils.inDungeons || DungeonUtils.inBoss || ++ticks % 10 != 0) return@on
            val level = mc.level ?: return@on
            if (read(level)) reveal()
        }
    }

    private fun read(level: Level): Boolean {
        var any = false
        for (edge in 0 until EDGES) {
            if (decided[edge]) continue
            val t = edge shr 1
            val below = edge and 1 == 1
            val col = t % 6
            val row = t / 6
            if (if (below) row == 5 else col == 5) continue
            val a = DungeonScan.tiles[t].room
            val b = DungeonScan.tiles[if (below) t + 6 else t + 1].room
            if (a === b) continue

            val x = col * 32 - 185 + if (below) 0 else 16
            val z = row * 32 - 185 + if (below) 16 else 0
            val chunk = level.chunkSource.getChunk(x shr 4, z shr 4, ChunkStatus.FULL, false) ?: continue
            val top = surface(chunk, x, z)
            if (top == UNLOADED) continue

            val fairy = a?.type == RoomType.FAIRY || b?.type == RoomType.FAIRY
            val type = when {
                top == 73 || top == 81 -> {
                    val gate = chunk.getBlockState(probe.set(x, 69, z)).block
                    when {
                        gate === bloodClay -> DoorType.Blood
                        fairy -> DoorType.Fairy
                        gate === Blocks.COAL_BLOCK -> DoorType.Wither
                        else -> DoorType.Normal
                    }
                }
                // blocks sometimes sit above the entrance's door
                top > 73 && (a?.type == RoomType.ENTRANCE || b?.type == RoomType.ENTRANCE) -> DoorType.Normal
                else -> null
            }
            found[edge] = type
            decided[edge] = true
            if (type != null) {
                anyDoor = true
                any = true
            }
        }
        return any
    }

    private fun reveal() {
        if (!DungeonUtils.inDungeons || !anyDoor) return
        var added = false
        for (edge in 0 until EDGES) {
            val type = found[edge] ?: continue
            val t = edge shr 1
            val rotation = if (edge and 1 == 0) DoorRotation.Horizontal else DoorRotation.Vertical
            val step = rotation.offset
            if (!visible(DungeonScan.tiles[t].room) && !visible(DungeonScan.tiles[t + step.x + step.z * 6].room)) continue
            val col = t % 6
            val row = t / 6
            val key = IVec2(2 * col - 12 + step.x, 2 * row - 12 + step.z)
            if (DungeonScan.doors.containsKey(key)) continue
            DungeonScan.doors[key] = DungeonDoor(IVec2(col, row), rotation, type, Colors.WHITE)
            added = true
        }
        if (added) DungeonScan.updateViewableDoors()
    }

    private fun visible(room: DungeonRoom?) = room != null && room.isViewable

    private fun surface(chunk: ChunkAccess, x: Int, z: Int): Int {
        for (y in 160 downTo 11) {
            val block = chunk.getBlockState(probe.set(x, y, z)).block
            if (block === Blocks.VOID_AIR) return UNLOADED
            if (block !== Blocks.AIR) return y
        }
        return 0
    }
}
