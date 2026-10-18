package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.pack.rooms.RoomPack
import com.engineerclient.p3sim.run.PlacedRoom

/**
 * The floors of a dungeon's rooms, read from the rooms pack's blocks ([RoomGrids]) the first time a room is asked for and kept for the run. A room
 * with no block data (the pack is missing, or the room is a made-up one) or with next to no floor to walk on has none: the bots wander over its tiles.
 * Pure (no Minecraft); not thread safe, the planner and the module use it on the server thread.
 */
class PackRoomAreas(private val pack: RoomPack?) : RoomAreas {
    /** By the placed room itself (not its index): one instance serves several dungeons in the headless check. */
    private val cache = java.util.IdentityHashMap<PlacedRoom, WalkArea?>()

    override fun of(room: PlacedRoom): WalkArea? {
        if (cache.containsKey(room)) return cache[room]
        val area = try { build(room) } catch (e: Exception) { null }
        cache[room] = area
        return area
    }

    private fun build(room: PlacedRoom): WalkArea? {
        val design = room.design ?: return null
        val placement = room.placement ?: return null
        val volume = pack?.volumeFor(design, room.variantId) ?: return null
        val grid = RoomGrids.fromVolume(volume, placement)
        return if (grid.openCount >= MIN_OPEN_COLUMNS) GridArea(grid) else null
    }

    /** The grid of a room, for the body's wall check and tests; null when it has none. */
    fun gridOf(room: PlacedRoom): RoomGrid? = (of(room) as? GridArea)?.grid

    companion object {
        /** A floor smaller than this many columns is not worth wandering on (a 1x1 room has 400 to 900). */
        const val MIN_OPEN_COLUMNS = 100
    }
}
