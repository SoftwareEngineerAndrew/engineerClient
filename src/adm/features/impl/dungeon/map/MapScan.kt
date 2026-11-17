package com.adm.features.impl.dungeon.map

import com.adm.events.CheckmarkUpdateEvent
import com.adm.events.LevelEvent
import com.adm.events.MapUpdateEvent
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.impl.dungeon.map.tile.DoorRotation
import com.adm.features.impl.dungeon.map.tile.DoorType
import com.adm.features.impl.dungeon.map.tile.DungeonDoor
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.MapCheckmark
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.Colors
import com.adm.utils.IVec2
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.world.level.saveddata.maps.MapDecoration
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes

object MapScan {

    private const val SIDE = 128
    private const val BLANK: Byte = 0
    private const val ENTRANCE: Byte = 30

    @Volatile
    private var located = false

    init {
        on<LevelEvent.Load> { located = false }

        onReceive<ClientboundMapItemDataPacket> {
            if (!DungeonUtils.inClear || mapId().id and 1000 != 0) return@onReceive
            val marks = decorations()
            if (marks.isPresent) placeTeammates(marks.get())
            val patch = colorPatch()
            if (!patch.isPresent) return@onReceive
            val pixels = patch.get().mapColors()
            if (pixels.size < SIDE * SIDE || pixels[0] != BLANK) return@onReceive
            if (!located && !locate(pixels)) return@onReceive
            if (DungeonScan.roomSize == -1) return@onReceive
            read(pixels)
            MapUpdateEvent.postAndCatch()
        }
    }

    private fun locate(pixels: ByteArray): Boolean {
        var i = 0
        while (i < pixels.size) {
            if (pixels[i] != ENTRANCE) { i++; continue }
            var end = i
            while (end < pixels.size && pixels[end] == ENTRANCE) end++
            val run = end - i
            // any start inside the run counts, so a long run is read from the start that leaves 18 (or 16) after it
            val size = if (run >= 18) 18 else if (run >= 16) 16 else 0
            if (size != 0) {
                val start = end - size
                DungeonScan.roomSize = size
                val gap = DungeonScan.roomGap
                DungeonScan.startX = ((start % SIDE) % gap).let { if (it == 0) 22 else it }
                DungeonScan.startY = ((start / SIDE) % gap).let { if (it == 0) 22 else it }
                located = true
                if (DungeonScan.startX == 5 && DungeonUtils.isFloor(4, 5, 6)) SpecialColumn.column = 5
                return true
            }
            i = end
        }
        return false
    }

    private fun placeTeammates(marks: List<MapDecoration>) {
        val mates = DungeonUtils.dungeonTeammatesNoSelf.iterator()
        val frame = MapDecorationTypes.FRAME.value()
        for (mark in marks) {
            if (mark.type.value() == frame) continue
            while (mates.hasNext()) {
                val mate = mates.next()
                if (mate.isDead) continue
                mate.mapPos = IVec2(mark.x.toInt(), mark.y.toInt())
                mate.yaw = mark.rot() * 360 / 16f
                break
            }
        }
    }

    private fun read(pixels: ByteArray) {
        val gap = DungeonScan.roomGap
        val half = DungeonScan.roomSize / 2
        val link = DungeonScan.connectionGap
        val ox = DungeonScan.startX
        val oz = DungeonScan.startY

        val types = arrayOfNulls<RoomType>(36)
        val corners = ByteArray(36)
        val centres = ByteArray(36)

        for (t in 0 until 36) {
            val col = t % 6
            val row = t / 6
            val x = ox + col * gap
            val z = oz + row * gap

            val corner = pixel(pixels, x, z)
            if (corner != BLANK) {
                val type = RoomType.fromMapColor(corner)
                if (type != null && type != RoomType.UNKNOWN && type != RoomType.UNDISCOVERED) {
                    types[t] = type
                    corners[t] = corner
                    centres[t] = pixel(pixels, x + half, z + half)
                }
            }

            if (col < 5) {
                val door = pixel(pixels, x + link, z + half)
                if (door != BLANK && pixel(pixels, x + link, z + half - 4) == BLANK) markDoor(col, row, DoorRotation.Horizontal, DoorType.fromColor(door))
            }
            if (row < 5) {
                val door = pixel(pixels, x + half, z + link)
                if (door != BLANK && pixel(pixels, x + half - 4, z + link) == BLANK) markDoor(col, row, DoorRotation.Vertical, DoorType.fromColor(door))
            }
        }

        val seen = BooleanArray(36)
        val group = IntArray(36)
        for (t in 0 until 36) {
            val type = types[t] ?: continue
            if (seen[t]) continue
            seen[t] = true
            group[0] = t
            var head = 0
            var size = 1
            while (head < size) {
                val at = group[head++]
                val col = at % 6
                val row = at / 6
                if (col < 5) size = join(pixels, types, seen, group, size, type, at, at + 1, col, row, true)
                if (col > 0) size = join(pixels, types, seen, group, size, type, at, at - 1, col - 1, row, true)
                if (row < 5) size = join(pixels, types, seen, group, size, type, at, at + 6, col, row, false)
                if (row > 0) size = join(pixels, types, seen, group, size, type, at, at - 6, col, row - 1, false)
            }
            settle(group, size, type, corners, centres)
        }
    }

    /** Queues [next] when it's the same type as [at] and the gap after tile ([gapCol], [gapRow]) is filled in. */
    private fun join(
        pixels: ByteArray, types: Array<RoomType?>, seen: BooleanArray, group: IntArray, size: Int,
        type: RoomType, at: Int, next: Int, gapCol: Int, gapRow: Int, across: Boolean,
    ): Int {
        if (seen[next] || types[next] != type) return size
        val x = DungeonScan.startX + gapCol * DungeonScan.roomGap + if (across) DungeonScan.connectionGap else 0
        val z = DungeonScan.startY + gapRow * DungeonScan.roomGap + if (across) 0 else DungeonScan.connectionGap
        if (pixel(pixels, x, z) == BLANK) return size
        seen[next] = true
        group[size] = next
        return size + 1
    }

    private fun settle(group: IntArray, size: Int, type: RoomType, corners: ByteArray, centres: ByteArray) {
        val grid = DungeonScan.tiles
        var room: DungeonRoom? = null
        for (i in 0 until size) {
            room = grid[group[i]].room
            if (room != null) break
        }

        if (room != null) {
            for (i in 0 until size) {
                val tile = grid[group[i]]
                if (tile.room === room) continue
                tile.room = room
                room.addSegment(tile)
            }
            if (room.highestBlock == null) room.inferLayoutFromMap()
        } else {
            var minCol = 5
            var minRow = 5
            for (i in 0 until size) {
                minCol = minOf(minCol, group[i] % 6)
                minRow = minOf(minRow, group[i] / 6)
            }
            room = DungeonRoom(type, IVec2(minCol, minRow))
            DungeonScan.rooms.add(room)
            for (i in 0 until size) {
                val tile = grid[group[i]]
                tile.room = room
                room.addSegment(tile)
            }
            room.inferLayoutFromMap()
        }

        room.mapSeen = true

        for (i in 0 until size) {
            val centre = centres[group[i]]
            val now = room.checkmark
            val plain = centre == corners[group[i]]
            val mark = when {
                plain && (now == MapCheckmark.UNDISCOVERED || now == MapCheckmark.QUESTION_MARK) -> MapCheckmark.NONE
                plain && now == MapCheckmark.RED && room.type == RoomType.PUZZLE -> MapCheckmark.NONE
                else -> MapCheckmark.fromMapColor(centre)
            }
            if (mark == null || mark == now) continue
            room.checkmark = mark
            if (mark == MapCheckmark.GREEN) room.foundSecrets = room.data?.maxSecrets ?: room.foundSecrets
            CheckmarkUpdateEvent(room, mark).postAndCatch()
        }
    }

    private fun markDoor(col: Int, row: Int, rotation: DoorRotation, type: DoorType) {
        val step = rotation.offset
        val key = IVec2(2 * col - 12 + step.x, 2 * row - 12 + step.z)
        val door = DungeonScan.doors[key]
        if (door != null) door.type = type
        else DungeonScan.doors.putIfAbsent(key, DungeonDoor(IVec2(col, row), rotation, type, Colors.WHITE))?.let { it.type = type }
    }

    private fun pixel(pixels: ByteArray, x: Int, z: Int): Byte =
        if (x < 0 || z < 0 || x >= SIDE || z >= SIDE) BLANK else pixels[z * SIDE + x]
}
