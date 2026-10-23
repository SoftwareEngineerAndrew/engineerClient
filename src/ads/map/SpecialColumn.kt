// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.ads.map

import com.engineerclient.ads.dungeon.AdsDungeon

/**
 * Odin's special column: on F4-F6 the column right of the main grid holds only 1x1 puzzles, the trap and the yellow
 * room. From what's open, each unopened 1x1 gets the room types it can still be ([guessFor]), which the minimap draws
 * as split tiles.
 */
object SpecialColumn {
    private val openedSpecialRooms = mutableSetOf<DungeonRoom>()
    private var discoveredFullSpecialColumn = 0
    private var columnRoomCount = 0
    private var discovered1x1s = 0
    var column = -1

    fun reset() {
        column = -1
        discoveredFullSpecialColumn = 0
        discovered1x1s = 0
        openedSpecialRooms.clear()
        columnRoomCount = 0
    }

    fun update() {
        val size = if (DungeonScan.startX == 5) 6 else 5

        for ((position, room) in DungeonScan.tiles) {
            if (room == null || room.isKnown1x1 || room.isViewable) continue
            if (room.data?.shape.let { it != null && it != RoomShape.OneByOne }) continue
            if (!touchesViewableRoom(position.x, position.z, room)) continue

            if (position.x == column) {
                room.isKnown1x1 = true
                discovered1x1s++
                columnRoomCount++
            } else if (isVisibleFromAllSides(position.x, position.z, size, room)) {
                room.isKnown1x1 = true
                discovered1x1s++
            }
        }
        DungeonScan.rooms.filter { (it.type == RoomType.CHAMPION || it.type == RoomType.TRAP || it.type == RoomType.PUZZLE) && it.isViewable }
            .forEach { openedSpecialRooms.add(it) }

        discoveredFullSpecialColumn =
            if (discovered1x1s == AdsDungeon.puzzleCount + 2) size
            else if (column != -1) countDiscoveredInColumn(size) else 0
    }

    private fun touchesViewableRoom(x: Int, z: Int, self: DungeonRoom): Boolean = DungeonScan.directions.any { (dx, dz) ->
        val nx = x + dx
        val nz = z + dz
        nx in 0..5 && nz in 0..5 && DungeonScan.tiles[nx + nz * 6].room?.let { it !== self && it.isViewable } == true
    }

    private fun isPathHint(x: Int, z: Int) = DungeonScan.pathHints.any { it.position.x == x && it.position.z == z }

    private fun checkSide(nx: Int, nz: Int, gridSize: Int, self: DungeonRoom, selfIsHint: Boolean): Boolean {
        if (nx !in 0 until gridSize || nz !in 0 until 6) return true
        val room = DungeonScan.tiles.getOrNull(nx + nz * 6)?.room ?: return false
        return room !== self && (room.isViewable || (selfIsHint && isPathHint(nx, nz)))
    }

    private fun isVisibleFromAllSides(x: Int, z: Int, gridSize: Int, self: DungeonRoom): Boolean {
        val selfIsHint = isPathHint(x, z)
        return checkSide(x + 1, z, gridSize, self, selfIsHint) && checkSide(x - 1, z, gridSize, self, selfIsHint) &&
            checkSide(x, z + 1, gridSize, self, selfIsHint) && checkSide(x, z - 1, gridSize, self, selfIsHint)
    }

    private fun countDiscoveredInColumn(zSize: Int): Int {
        var discovered = zSize
        for (z in 0 until zSize) {
            val room = DungeonScan.tiles[(column - 1) + z * 6].room
            if (room == null || room.isViewable) discovered--
        }
        return discovered
    }

    /** What the unopened 1x1 in column [tileX] can still be: one to three of puzzle, trap and yellow. */
    fun guessFor(tileX: Int): List<RoomType> {
        val specialColumnRoomCount = if (column != -1 && columnRoomCount == 0) 1 else columnRoomCount
        val z = if (DungeonScan.startX == 5) 6 else 5
        return if (tileX == column) guessSpecialTile(specialColumnRoomCount, z) else guessNonSpecialTile(specialColumnRoomCount)
    }

    private val championPossible get() = openedSpecialRooms.none { it.type == RoomType.CHAMPION }
    private val trapPossible get() = openedSpecialRooms.none { it.type == RoomType.TRAP }
    private val puzzlePossible get() = openedSpecialRooms.count { it.type == RoomType.PUZZLE } < AdsDungeon.puzzleCount

    private fun possible(vararg guesses: RoomType): List<RoomType> {
        val allowed = { t: RoomType ->
            when (t) {
                RoomType.CHAMPION -> championPossible
                RoomType.TRAP -> trapPossible
                else -> puzzlePossible
            }
        }
        return guesses.filter(allowed).ifEmpty { listOf(RoomType.CHAMPION, RoomType.TRAP, RoomType.PUZZLE).filter(allowed) }
            .ifEmpty { listOf(RoomType.PUZZLE) }
    }

    private fun guessSpecialTile(specialColumnRoomCount: Int, mapSizeZ: Int): List<RoomType> {
        val specialSize = mapSizeZ - discoveredFullSpecialColumn + specialColumnRoomCount
        val puzzles = AdsDungeon.puzzleCount
        return when {
            specialSize <= puzzles || discovered1x1s - specialColumnRoomCount >= 2 -> possible(RoomType.PUZZLE)
            openedSpecialRooms.any { it.type == RoomType.TRAP && it.topLeft.x != column } -> possible(RoomType.PUZZLE)
            specialSize == puzzles + 1 || discovered1x1s - specialColumnRoomCount == 1 -> possible(RoomType.TRAP, RoomType.PUZZLE)
            else -> possible(RoomType.CHAMPION, RoomType.TRAP, RoomType.PUZZLE)
        }
    }

    private fun guessNonSpecialTile(specialColumnRoomCount: Int): List<RoomType> {
        val nonSpecialPuzzles = openedSpecialRooms.count { it.type == RoomType.PUZZLE && it.topLeft.x != column }
        return when {
            specialColumnRoomCount + nonSpecialPuzzles == AdsDungeon.puzzleCount -> possible(RoomType.CHAMPION, RoomType.TRAP)
            specialColumnRoomCount == AdsDungeon.puzzleCount + 1 -> possible(RoomType.CHAMPION)
            else -> possible(RoomType.PUZZLE, RoomType.TRAP, RoomType.CHAMPION)
        }
    }
}
