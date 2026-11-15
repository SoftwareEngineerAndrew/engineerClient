package com.engineerclient.dungeon

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.events.CheckmarkUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DoorType
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.MapCheckmark
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import java.util.Collections
import java.util.IdentityHashMap

/** Clear Info's Room Alerts: a title when the room you are in is cleared or has all its secrets. */
object RoomAlerts {
    enum class Rush(private val label: String) {
        ANY("Always"), ONLY("Only Key Rooms"), NOT("Not Key Rooms");
        override fun toString() = label
    }

    private val marks: MutableMap<DungeonRoom, MapCheckmark> = Collections.synchronizedMap(IdentityHashMap())

    fun install() {
        on<CheckmarkUpdateEvent> {
            val room = room; val mark = checkmark
            val before = marks.put(room, mark)
            mc.execute { onMark(room, before, mark) }
        }
        on<LevelEvent.Load> { marks.clear() }
        EventBus.subscribe(this)
    }

    private fun onMark(room: DungeonRoom, before: MapCheckmark?, mark: MapCheckmark) {
        if (!ClearInfo.roomAlertsOn || !DungeonUtils.inClear || DungeonUtils.currentRoom !== room) return
        if (room.type == RoomType.FAIRY || room.type == RoomType.ENTRANCE) return
        if (ClearInfo.noBlood && room.type == RoomType.BLOOD) return
        val key = keyRoom(room)
        if ((ClearInfo.keyRooms == Rush.ONLY && !key) || (ClearInfo.keyRooms == Rush.NOT && key)) return
        val wasCleared = before == MapCheckmark.WHITE || before == MapCheckmark.GREEN
        val text = when {
            mark == MapCheckmark.WHITE && !wasCleared && ClearInfo.cleared -> "&fRoom Cleared!"
            mark == MapCheckmark.GREEN && before == MapCheckmark.WHITE && ClearInfo.secretsDone && (room.data?.maxSecrets ?: 0) > 0 -> "&bSecrets Done!"
            mark == MapCheckmark.GREEN && !wasCleared && ClearInfo.cleared -> "&aRoom Cleared!"
            else -> return
        }
        showTitle(text, ClearInfo.displayTime)
        ClearInfo.roomSound.play()
    }

    private fun keyRoom(room: DungeonRoom) = DungeonScan.doors.values.any { door ->
        (door.type == DoorType.Wither || door.type == DoorType.Blood) &&
            (DungeonScan.tiles.getOrNull(door.originTileIndex)?.room === room || DungeonScan.tiles.getOrNull(door.destinationTileIndex)?.room === room)
    }
}
