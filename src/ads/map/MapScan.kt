// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.ads.map

import com.engineerclient.ads.dungeon.AdsDungeon
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.world.level.saveddata.maps.MapDecoration
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import kotlin.jvm.optionals.getOrNull

/**
 * The dungeon map item read into [DungeonScan] (Odin's MapScan): each tile's room type from its corner pixel, its
 * check mark from its middle pixel, doors from the gaps, and a room's tiles joined where the map joins them; the
 * markers move the party members the game can't see. `AdsMinimapPacketMixin` hands over every map packet, on the
 * game thread, once the game has applied it.
 */
object MapScan {
    private const val MAP_SIZE = 128
    private const val EMPTY: Byte = 0

    private var isInit = false

    fun init() {}

    fun reset() { isInit = false }

    @JvmStatic
    fun onMapPacket(packet: ClientboundMapItemDataPacket) {
        if (!AdsDungeon.inClear || packet.mapId().id and 1000 != 0) return

        packet.decorations().getOrNull()?.let { updatePlayers(it) }
        packet.colorPatch().getOrNull()?.mapColors()?.let { read(it) }
    }

    /** One whole map picture (128 x 128 colour bytes) into [DungeonScan]. */
    internal fun read(colors: ByteArray) {
        if (colors.size < MAP_SIZE * MAP_SIZE || colors[0] != EMPTY) return
        if (!isInit && !initLayout(colors)) return
        updateAll(colors)
    }

    private fun initLayout(colors: ByteArray): Boolean {
        for ((index, color) in colors.withIndex()) {
            if (color != RoomType.ENTRANCE.mapColor) continue
            var end = index
            while (end < colors.size && colors[end] == color) end++
            val length = end - index
            if (length != 16 && length != 18) continue

            DungeonScan.roomSize = length
            DungeonScan.startX = (index % MAP_SIZE) % DungeonScan.roomGap
            DungeonScan.startY = (index / MAP_SIZE) % DungeonScan.roomGap
            if (DungeonScan.startX == 0) DungeonScan.startX = 22 // F1
            if (DungeonScan.startY == 0) DungeonScan.startY = 22 // the entrance
            isInit = true

            val floor = AdsDungeon.floorNumber
            if ((floor in 4..6) && DungeonScan.startX == 5) SpecialColumn.column = 5
            return true
        }
        return false
    }

    /** The map's markers, one per living member in the tab list's order (yours is the frame). */
    private fun updatePlayers(decorations: List<MapDecoration>) {
        val iterator = AdsDungeon.teammatesNoSelf.iterator()
        for (decoration in decorations) {
            if (decoration.type.value() == MapDecorationTypes.FRAME.value()) continue
            iterator.asSequence().firstOrNull { !it.isDead }?.apply {
                mapX = decoration.x.toInt()
                mapZ = decoration.y.toInt()
                yaw = decoration.rot() * 360 / 16f
            }
        }
    }

    private fun updateAll(colors: ByteArray) {
        val roomTypes = arrayOfNulls<RoomType>(36)
        val roomColors = ByteArray(36)
        val centerColors = ByteArray(36)
        mapTiles(colors, roomTypes, roomColors, centerColors)
        processRooms(colors, roomTypes, roomColors, centerColors)
        DungeonScan.refresh()
    }

    private fun mapTiles(colors: ByteArray, roomTypes: Array<RoomType?>, roomColors: ByteArray, centerColors: ByteArray) {
        val halfRoom = DungeonScan.roomSize / 2
        val connectionGap = DungeonScan.connectionGap
        val sideCheckOffset = 4

        for (index in 0 until 36) {
            val tileX = index % 6
            val tileZ = index / 6
            val originX = DungeonScan.startX + tileX * DungeonScan.roomGap
            val originZ = DungeonScan.startY + tileZ * DungeonScan.roomGap

            val cornerColor = px(colors, originX, originZ)
            if (cornerColor != EMPTY) {
                val type = RoomType.fromMapColor(cornerColor)
                if (type != null && type != RoomType.UNKNOWN && type != RoomType.UNDISCOVERED) {
                    roomTypes[index] = type
                    roomColors[index] = cornerColor
                    centerColors[index] = px(colors, originX + halfRoom, originZ + halfRoom)
                }
            }

            if (tileX < 5) {
                val door = px(colors, originX + connectionGap, originZ + halfRoom)
                val side = px(colors, originX + connectionGap, originZ + halfRoom - sideCheckOffset)
                if (side == EMPTY && door != EMPTY) addOrFixDoor(GridPos(tileX, tileZ), DoorRotation.Horizontal, DoorType.fromColor(door))
            }
            if (tileZ < 5) {
                val door = px(colors, originX + halfRoom, originZ + connectionGap)
                val side = px(colors, originX + halfRoom - sideCheckOffset, originZ + connectionGap)
                if (side == EMPTY && door != EMPTY) addOrFixDoor(GridPos(tileX, tileZ), DoorRotation.Vertical, DoorType.fromColor(door))
            }
        }
    }

    private fun processRooms(colors: ByteArray, roomTypes: Array<RoomType?>, roomColors: ByteArray, centerColors: ByteArray) {
        val visited = BooleanArray(36)
        val gap = DungeonScan.connectionGap
        val sx = DungeonScan.startX; val sy = DungeonScan.startY; val rg = DungeonScan.roomGap

        for (start in 0 until 36) {
            val roomType = roomTypes[start] ?: continue
            if (visited[start]) continue

            val components = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            visited[start] = true

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                components.add(current)
                val cx = current % 6
                val cz = current / 6
                for ((dx, dz) in DungeonScan.directions) {
                    val nx = cx + dx
                    val nz = cz + dz
                    if (nx !in 0..5 || nz !in 0..5) continue
                    val next = nx + nz * 6
                    if (visited[next] || roomTypes[next] != roomType) continue

                    val connected = when {
                        dx == 1 -> px(colors, sx + cx * rg + gap, sy + cz * rg) != EMPTY
                        dx == -1 -> px(colors, sx + nx * rg + gap, sy + nz * rg) != EMPTY
                        dz == 1 -> px(colors, sx + cx * rg, sy + cz * rg + gap) != EMPTY
                        else -> px(colors, sx + nx * rg, sy + nz * rg + gap) != EMPTY
                    }
                    if (!connected) continue
                    visited[next] = true
                    queue.add(next)
                }
            }
            buildRoom(components, roomType, roomColors, centerColors)
        }
    }

    private fun buildRoom(segments: List<Int>, roomType: RoomType, roomColors: ByteArray, centerColors: ByteArray) {
        val existing = segments.firstNotNullOfOrNull { DungeonScan.tiles[it].room }
        val room = if (existing != null) {
            for (index in segments) {
                if (DungeonScan.tiles[index].room != existing) {
                    DungeonScan.tiles[index].room = existing
                    existing.addSegment(DungeonScan.tiles[index])
                }
            }
            if (existing.highestBlock == null) existing.inferLayoutFromMap()
            existing
        } else {
            DungeonRoom(roomType, GridPos(segments.minOf { it % 6 }, segments.minOf { it / 6 })).also { room ->
                DungeonScan.rooms.add(room)
                for (index in segments) {
                    DungeonScan.tiles[index].room = room
                    room.addSegment(DungeonScan.tiles[index])
                }
                room.inferLayoutFromMap()
            }
        }
        room.mapSeen = true

        for (index in segments) {
            val roomColor = roomColors[index]
            val centerColor = centerColors[index]
            val checkmark = if (centerColor == roomColor && (room.checkmark == MapCheckmark.UNDISCOVERED || room.checkmark == MapCheckmark.QUESTION_MARK)) MapCheckmark.NONE
                else MapCheckmark.fromMapColor(centerColor)
            if (checkmark != null && checkmark != room.checkmark) {
                room.checkmark = checkmark
                if (checkmark == MapCheckmark.GREEN) room.foundSecrets = room.data?.maxSecrets ?: room.foundSecrets
            }
        }
    }

    private fun addOrFixDoor(position: GridPos, rotation: DoorRotation, type: DoorType) {
        DungeonScan.doors.getOrPut(GridPos(-12 + 2 * position.x + rotation.offset.x, -12 + 2 * position.z + rotation.offset.z)) {
            DungeonDoor(position, rotation, type)
        }.type = type
    }

    private fun px(colors: ByteArray, x: Int, z: Int): Byte = if (x in 0 until MAP_SIZE && z in 0 until MAP_SIZE) colors[z * MAP_SIZE + x] else EMPTY
}
