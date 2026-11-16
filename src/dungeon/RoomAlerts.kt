package com.engineerclient.dungeon

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import com.adm.events.CheckmarkUpdateEvent
import com.adm.events.LevelEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.features.impl.dungeon.map.tile.DoorType
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.MapCheckmark
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.skyblock.dungeon.DungeonUtils
import java.util.Collections
import java.util.IdentityHashMap

/** Clear Info's Room Alerts: a title or a vignette when the room you are in is cleared or has all its secrets. */
object RoomAlerts {
    enum class Style(private val label: String) {
        TITLE("Title"), VIGNETTE("Vignette");
        override fun toString() = label
    }

    private var vignetteColor = 0
    private var vignetteStart = 0L
    private var vignetteMs = 0L

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
        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, Identifier.fromNamespaceAndPath("engineerclient", "room_alert_vignette")) { gfx, _ ->
            EngineerClient.safely("room alert vignette") { drawVignette(gfx) }
        }
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
        if (ClearInfo.alertStyle == Style.VIGNETTE) {
            vignetteColor = when (text[1]) { 'b' -> 0x55FFFF; 'a' -> 0x55FF55; else -> 0xFFFFFF }
            vignetteStart = System.currentTimeMillis()
            vignetteMs = (ClearInfo.displayTime * 1000).toLong()
        } else showTitle(text, ClearInfo.displayTime)
        ClearInfo.roomSound.play()
    }

    /** The screen's edges in the alert's color, fading out over the display time. */
    private fun drawVignette(gfx: GuiGraphicsExtractor) {
        val left = vignetteMs - (System.currentTimeMillis() - vignetteStart)
        if (left <= 0) return
        com.engineerclient.render.Vignette.draw(gfx, vignetteColor, (140f * left / vignetteMs).toInt())
    }

    private fun keyRoom(room: DungeonRoom) = DungeonScan.doors.values.any { door ->
        (door.type == DoorType.Wither || door.type == DoorType.Blood) &&
            (DungeonScan.tiles.getOrNull(door.originTileIndex)?.room === room || DungeonScan.tiles.getOrNull(door.destinationTileIndex)?.room === room)
    }
}
