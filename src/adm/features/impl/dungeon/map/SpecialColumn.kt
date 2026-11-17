package com.adm.features.impl.dungeon.map

import com.adm.events.LevelEvent
import com.adm.events.MapUpdateEvent
import com.adm.events.RoomEnterEvent
import com.adm.events.core.on
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.RoomShape
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.Color
import com.adm.utils.skyblock.dungeon.DungeonUtils

object SpecialColumn {
    val openedSpecialRooms = mutableSetOf<DungeonRoom>()
    var discoveredFullSpecialColumn = 0
    var columnRoomCount = 0
    var discovered1x1s = 0
    var column = -1

    internal const val CHAMPION = 0
    internal const val TRAP = 1
    internal const val PUZZLE = 2

    init {
        on<LevelEvent.Load> { unload() }
        on<RoomEnterEvent> { if (room != null) update() }
        on<MapUpdateEvent> { update() }
    }

    fun unload() {
        column = -1
        discoveredFullSpecialColumn = 0
        discovered1x1s = 0
        openedSpecialRooms.clear()
        columnRoomCount = 0
    }

    private val gridWidth: Int get() = if (DungeonScan.startX == 5) 6 else 5

    fun update() {
        val width = gridWidth
        val grid = DungeonScan.tiles
        for (i in grid.indices) {
            val room = grid[i].room ?: continue
            if (room.isKnown1x1 || room.isViewable) continue
            val shape = room.data?.shape
            if (shape != null && shape != RoomShape.OneByOne) continue
            val x = i % 6
            val z = i / 6
            if (!nextToSeen(x, z, room)) continue

            if (x == column) {
                if (!doorToSeen(i, room)) continue
                room.isKnown1x1 = true
                discovered1x1s++
                columnRoomCount++
            } else if (enclosed(x, z, width, room)) {
                room.isKnown1x1 = true
                discovered1x1s++
            }
        }

        for (room in DungeonScan.rooms) {
            val t = room.type
            if ((t == RoomType.CHAMPION || t == RoomType.TRAP || t == RoomType.PUZZLE) && room.isViewable) openedSpecialRooms.add(room)
        }

        discoveredFullSpecialColumn = when {
            discovered1x1s == DungeonUtils.puzzleCount + 2 -> width
            column != -1 -> unseenInColumn(width)
            else -> 0
        }
    }

    private fun seenOther(index: Int, self: DungeonRoom): Boolean {
        val room = DungeonScan.tiles[index].room ?: return false
        return room !== self && room.isViewable
    }

    private fun nextToSeen(x: Int, z: Int, self: DungeonRoom): Boolean {
        val i = z * 6 + x
        return (x < 5 && seenOther(i + 1, self)) || (x > 0 && seenOther(i - 1, self)) ||
            (z < 5 && seenOther(i + 6, self)) || (z > 0 && seenOther(i - 6, self))
    }

    private fun doorToSeen(index: Int, self: DungeonRoom): Boolean {
        for (door in DungeonScan.doors.values) {
            val other = when (index) {
                door.originTileIndex -> door.destinationTileIndex
                door.destinationTileIndex -> door.originTileIndex
                else -> continue
            }
            if (other in 0 until 36 && seenOther(other, self)) return true
        }
        return false
    }

    private fun isHint(x: Int, z: Int): Boolean {
        for (tile in DungeonScan.pathHints) if (tile.position.x == x && tile.position.z == z) return true
        return false
    }

    private fun closedSide(x: Int, z: Int, width: Int, self: DungeonRoom, selfHint: Boolean): Boolean {
        if (x < 0 || x >= width || z < 0 || z >= 6) return true
        val room = DungeonScan.tiles[z * 6 + x].room ?: return false
        return room !== self && (room.isViewable || (selfHint && isHint(x, z)))
    }

    private fun enclosed(x: Int, z: Int, width: Int, self: DungeonRoom): Boolean {
        val selfHint = isHint(x, z)
        return closedSide(x + 1, z, width, self, selfHint) && closedSide(x - 1, z, width, self, selfHint) &&
            closedSide(x, z + 1, width, self, selfHint) && closedSide(x, z - 1, width, self, selfHint)
    }

    private fun unseenInColumn(height: Int): Int {
        var left = height
        for (z in 0 until height) {
            val room = DungeonScan.tiles[column - 1 + z * 6].room
            if (room == null || room.isViewable) left--
        }
        return left
    }

    fun colorGuessForUnknown(tileX: Int): Array<Color> {
        val code = guessCode(tileX == column)
        return Array(guessCount(code)) { kindColor(guessKind(code, it)) }
    }

    internal fun guessCount(code: Int): Int = code ushr 6

    internal fun guessKind(code: Int, i: Int): Int = (code ushr (i * 2)) and 3

    internal fun kindColor(kind: Int): Color = when (kind) {
        CHAMPION -> DungeonMap.championRoomColor
        TRAP -> DungeonMap.trapRoomColor
        else -> DungeonMap.puzzleRoomColor
    }

    // the types an unknown 1x1 may be, in order, two bits each, with the count from bit 6
    internal fun guessCode(inColumn: Boolean): Int {
        var champions = 0
        var traps = 0
        var puzzles = 0
        var outsidePuzzles = 0
        var outsideTrap = false
        for (room in openedSpecialRooms) {
            when (room.type) {
                RoomType.CHAMPION -> champions++
                RoomType.TRAP -> {
                    traps++
                    if (room.topLeft.x != column) outsideTrap = true
                }
                RoomType.PUZZLE -> {
                    puzzles++
                    if (room.topLeft.x != column) outsidePuzzles++
                }
                else -> {}
            }
        }
        val puzzleTotal = DungeonUtils.puzzleCount
        val allowed = (if (champions == 0) 1 shl CHAMPION else 0) or
            (if (traps == 0) 1 shl TRAP else 0) or
            (if (puzzles < puzzleTotal) 1 shl PUZZLE else 0)

        val inSpecial = if (column != -1 && columnRoomCount == 0) 1 else columnRoomCount

        if (inColumn) {
            val specialSize = gridWidth - discoveredFullSpecialColumn + inSpecial
            val elsewhere = discovered1x1s - inSpecial
            return when {
                specialSize <= puzzleTotal || elsewhere >= 2 || outsideTrap -> narrow(allowed, PUZZLE)
                specialSize == puzzleTotal + 1 || elsewhere == 1 -> narrow(allowed, TRAP, PUZZLE)
                else -> narrow(allowed, CHAMPION, TRAP, PUZZLE)
            }
        }
        return when {
            inSpecial + outsidePuzzles == puzzleTotal -> narrow(allowed, CHAMPION, TRAP)
            inSpecial == puzzleTotal + 1 -> narrow(allowed, CHAMPION)
            else -> narrow(allowed, PUZZLE, TRAP, CHAMPION)
        }
    }

    private fun narrow(allowed: Int, a: Int, b: Int = -1, c: Int = -1): Int {
        val wanted = pack(allowed, a, b, c)
        if (wanted ushr 6 != 0) return wanted
        val any = pack(allowed, CHAMPION, TRAP, PUZZLE)
        return if (any ushr 6 != 0) any else (1 shl 6) or PUZZLE
    }

    private fun pack(allowed: Int, a: Int, b: Int, c: Int): Int = append(allowed, append(allowed, append(allowed, 0, a), b), c)

    private fun append(allowed: Int, code: Int, kind: Int): Int {
        if (kind < 0 || allowed and (1 shl kind) == 0) return code
        val n = code ushr 6
        return (code and 63) or (kind shl (n * 2)) or ((n + 1) shl 6)
    }
}
