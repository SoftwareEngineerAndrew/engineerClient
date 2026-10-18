package com.engineerclient.p3sim.clear.blood

import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.PlacedRoom
import com.engineerclient.p3sim.run.plugin.BloodRoomSpec
import com.engineerclient.p3sim.run.plugin.HeadSlot

/** The blood room as the camp sees it ([BloodRoomSpec]), in world coordinates. */
class BloodRoom(
    override val roomIndex: Int,
    override val designId: String,
    override val buildKey: String?,
    override val doorX: Double, override val doorY: Double, override val doorZ: Double,
    /** The middle of the room at floor height: where the Watcher stands in the camp's coordinates (his body hangs [WatcherStats.RISE] higher, see [watcherHomeY]). */
    override val watcherX: Double, override val watcherY: Double, override val watcherZ: Double,
    override val heads: List<HeadSlot>,
    override val mobCells: List<DoubleArray> = emptyList(),
    /** The clockwise quarter turns the room is placed with (0 SOUTH, 1 WEST, 2 NORTH, 3 EAST). */
    val quarterTurns: Int = 0,
    /** The exit alcove (portal cells, strike point), or null when the room's blocks are not available. */
    val exit: BloodGeometry.ExitSite? = null,
) : BloodRoomSpec {
    /** Where the Watcher's feet are when the room opens. */
    val watcherHomeY: Double get() = watcherY + WatcherStats.RISE

    /** The yaw he faces: toward the blood door. */
    val watcherYaw: Float get() = WatcherStats.yawTowardDoor(quarterTurns)

    companion object {
        /** Standing height assumed when the room's blocks are not available (the floor of every recorded blood room). */
        const val DEFAULT_STAND_Y = 69.0

        /** Centre y of the blood door's 4-block body (y 69 to 72). */
        const val DOOR_CENTRE_Y = 71.0

        /**
         * Works the room out of the plan. The wall heads, the middle height and the exit alcove come from the room's blocks when the room
         * pack can give them ([volume] null: no heads, no portal, and the middle of the tile stands at [DEFAULT_STAND_Y]). Null when the
         * plan has no blood room.
         */
        fun of(plan: DungeonPlan, volume: com.engineerclient.p3sim.pack.rooms.RoomVolume?): BloodRoom? {
            val room = plan.blood ?: return null
            return of(plan, room, volume)
        }

        fun of(plan: DungeonPlan, room: PlacedRoom, volume: com.engineerclient.p3sim.pack.rooms.RoomVolume?): BloodRoom {
            val door = plan.doors.firstOrNull { it.kind == DoorKind.BLOOD }
            val (dx, dz) = door?.spot ?: (tileCentre(room, true) to tileCentre(room, false))
            val place = room.placement
            var cx = tileCentre(room, true).toDouble() + 0.5
            var cz = tileCentre(room, false).toDouble() + 0.5
            var cy = DEFAULT_STAND_Y
            var heads: List<HeadSlot> = emptyList()
            var exit: BloodGeometry.ExitSite? = null
            if (volume != null && place != null) {
                BloodGeometry.worldCentre(volume, place)?.let { (x, y, z) -> cx = x; cy = y; cz = z }
                heads = BloodGeometry.worldSlots(volume, place)
                BloodGeometry.standY(volume)?.let { exit = BloodGeometry.exitSite(place, it) }
            }
            val q = place?.quarterTurns ?: room.rotation.q
            return BloodRoom(room.index, room.designId, room.design?.buildKey, dx + 0.5, DOOR_CENTRE_Y, dz + 0.5, cx, cy, cz, heads, quarterTurns = q, exit = exit)
        }

        /** World x (or z) of the middle column of the room's first tile, the fallback when there are no blocks. */
        private fun tileCentre(room: PlacedRoom, x: Boolean): Int {
            val t = room.tiles.first()
            return DungeonFrame.tileCentre(if (x) t.x else t.z)
        }
    }
}
