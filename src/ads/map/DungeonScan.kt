// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.ads.map

import com.engineerclient.ads.dungeon.AdsDungeon
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The minimap's picture of the dungeon (Odin's DungeonScan): the 36 tiles, the rooms on them and the doors between
 * them, filled in from the map item ([MapScan]) and the world's room cores ([WorldScan]). [roomSize], [startX] and
 * [startY] are where the map item draws the grid: 16 pixel rooms from (5, 5) on F4-F7, 18 pixel rooms further in
 * on the small floors.
 */
object DungeonScan {
    const val ROOM_SPACING = 4

    var roomSize = 16
    val roomGap: Int get() = roomSize + ROOM_SPACING
    val connectionGap: Int get() = roomSize + ROOM_SPACING / 2
    var startX = 5
    var startY = 5

    val tiles: Array<DungeonTile> = Array(36) { DungeonTile(GridPos(it % 6, it / 6)) }
    val rooms = CopyOnWriteArrayList<DungeonRoom>()
    val doors = ConcurrentHashMap<GridPos, DungeonDoor>()

    /** Tiles behind a door from an open room that nobody has opened yet. */
    val pathHints = CopyOnWriteArrayList<DungeonTile>()

    val directions = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        AdsDungeon.onWorldChange { reset() }
        AdsDungeon.onFloorEnter { initFloor(it) }
        MapScan.init()
        WorldScan.init()
    }

    internal fun reset() {
        repeat(36) { tiles[it] = DungeonTile(GridPos(it % 6, it / 6)) }
        rooms.clear()
        doors.clear()
        pathHints.clear()
        roomSize = 16
        startX = 5
        startY = 5
        MapScan.reset()
        WorldScan.reset()
        SpecialColumn.reset()
    }

    private fun initFloor(floor: Int) {
        roomSize = if (floor <= 3) 18 else 16
        startX = when {
            floor <= 1 -> 22
            floor <= 3 -> 11
            else -> 5
        }
        startY = when (floor) {
            0 -> 22
            4 -> 16
            in 1..3 -> 11
            else -> 5
        }
    }

    /** After anything new on the map or a room entered: the path hints and the special column again. */
    fun refresh() {
        updatePathHints()
        SpecialColumn.update()
    }

    private fun updatePathHints() {
        pathHints.clear()
        for (door in doors.values) {
            val origin = tiles[door.originTileIndex]
            val dest = tiles[door.destinationTileIndex]
            val originView = origin.room?.isViewable == true
            val destView = dest.room?.isViewable == true
            if (!originView && !destView) continue
            (if (!originView) origin else if (!destView) dest else null)?.let { pathHints.add(it) }
        }
    }
}
