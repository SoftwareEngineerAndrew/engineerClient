package com.engineerclient.dungeon

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.CheckmarkUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DoorType
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.MapCheckmark
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import java.util.Collections
import java.util.IdentityHashMap

object RoomAlerts : Module(
    name = "Room Alerts",
    category = Category.DUNGEON,
    description = "A title when the room you are in is cleared or has all its secrets.",
    key = null,
) {
    enum class Rush(private val label: String) {
        ANY("Always"), ONLY("Only Key Rooms"), NOT("Not Key Rooms");
        override fun toString() = label
    }

    private val cleared by BooleanSetting("Cleared", true, desc = "When your room gets its checkmark.")
    private val secretsDone by BooleanSetting("Secrets Done", true, desc = "When your cleared room turns green.")
    private val displayTime by NumberSetting("Display Time", 0.5, 0.1..3.0, 0.1, desc = "How long the title stays.", unit = "s")
    private val rush by SelectorSetting("Key Rooms", Rush.NOT, desc = "Key rooms are the ones next to a wither or blood door.")
    private val noBlood by BooleanSetting("No Blood Room", false, desc = "No alert for the blood room.")
    private val sound = SoundOption(this, "block.note_block.pling")

    private val marks: MutableMap<DungeonRoom, MapCheckmark> = Collections.synchronizedMap(IdentityHashMap())

    init {
        on<CheckmarkUpdateEvent> {
            val room = room; val mark = checkmark
            val before = marks.put(room, mark)
            mc.execute { onMark(room, before, mark) }
        }
        on<LevelEvent.Load> { marks.clear() }
    }

    private fun onMark(room: DungeonRoom, before: MapCheckmark?, mark: MapCheckmark) {
        if (!DungeonUtils.inClear || DungeonUtils.currentRoom !== room) return
        if (room.type == RoomType.FAIRY || room.type == RoomType.ENTRANCE) return
        if (noBlood && room.type == RoomType.BLOOD) return
        val key = keyRoom(room)
        if ((rush == Rush.ONLY && !key) || (rush == Rush.NOT && key)) return
        val wasCleared = before == MapCheckmark.WHITE || before == MapCheckmark.GREEN
        val text = when {
            mark == MapCheckmark.WHITE && !wasCleared && cleared -> "&fRoom Cleared!"
            mark == MapCheckmark.GREEN && before == MapCheckmark.WHITE && secretsDone && (room.data?.maxSecrets ?: 0) > 0 -> "&bSecrets Done!"
            mark == MapCheckmark.GREEN && !wasCleared && cleared -> "&aRoom Cleared!"
            else -> return
        }
        showTitle(text, displayTime)
        sound.play()
    }

    private fun keyRoom(room: DungeonRoom) = DungeonScan.doors.values.any { door ->
        (door.type == DoorType.Wither || door.type == DoorType.Blood) &&
            (DungeonScan.tiles.getOrNull(door.originTileIndex)?.room === room || DungeonScan.tiles.getOrNull(door.destinationTileIndex)?.room === room)
    }
}
