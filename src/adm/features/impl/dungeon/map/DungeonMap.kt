package com.adm.features.impl.dungeon.map

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.*
import com.adm.features.Module
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.utils.Color
import com.adm.utils.Color.Companion.darker
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.Colors
import com.adm.utils.IVec2
import com.adm.utils.network.WebUtils.gson
import com.adm.utils.network.webSocket
import com.adm.utils.render.hollowFill
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.engineerclient.lcm.LcmStyle
import com.engineerclient.mapicons.MapSizes
import net.minecraft.client.gui.GuiGraphicsExtractor

object DungeonMap : Module(
    name = "Dungeon Map",
    description = "Displays the dungeon map."
) {
    private val disableBoss by BooleanSetting("Disable in Boss", true, desc = "Disables the map during boss fights.")

    val backgroundOutline by ColorSetting("Background Outline", Colors.BLACK, true, desc = "The color of the background border.")
    val backgroundColor by ColorSetting("Background Color", Colors.BLACK.withAlpha(0.1f), true, desc = "Background color of the map.")
    val roomText by SelectorSetting("Room Text", RoomTextOption.BOTH, desc = "What opened rooms show: their checkmark, name, secrets or both.")

    enum class RoomTextOption(private val label: String) {
        CHECKMARKS("Checkmarks"), ROOM_NAME("Name"), ROOM_SECRETS("Secrets"), BOTH("Name & Secrets");
        override fun toString() = label
    }
    val textScaling by NumberSetting("Text Scaling", 0.45f, 0.1..1.0, 0.05f, desc = "Scale of room name text.")

    private val playerDropdown by DropdownSetting("Players", desc = "Player names and heads on the map.")
    val playerNamesScaling by NumberSetting("Player Names Scaling", 0.75f, 0.1..2.0, 0.05f, desc = "Scale of player name labels.").withDependency { playerDropdown }
    val playerNameColor by ColorSetting("Player Name Color", Color(70, 70, 70), true, desc = "Color of player name labels.").withDependency { playerDropdown }
    val playerHead by BooleanSetting("Own Player Head", desc = "Shows the player head on the map.").withDependency { playerDropdown }
    val playerHeadScale by NumberSetting("Player Head Scale",1f,0.5..2.0,0.1, desc = "Scale of rendered player heads").withDependency { playerDropdown && playerHead }
    val playerHeadBackgroundScale by NumberSetting("Player Class Color Size",1,0..2, desc = "Scale of rendered player head background").withDependency { playerDropdown && playerHead }
    val playerPointerScale by NumberSetting("Player Pointer Scale",1f,0.5..2.0,0.1, desc = "Scale of your map pointer.").withDependency { playerDropdown && !playerHead }
    val pointerStyle by SelectorSetting("Pointer Style", PointerStyle.ADM, desc = "How your map pointer looks.").withDependency { playerDropdown && !playerHead }
    val pointerOutline by ColorSetting("Pointer Outline", Colors.BLACK, true, desc = "Outline colour of the ADM pointer.").withDependency { playerDropdown && !playerHead && pointerStyle == PointerStyle.ADM }
    val lcmPointerColor by ColorSetting("LCM Pointer Color", Colors.WHITE, true, desc = "Colour of the LCM pointer.").withDependency { playerDropdown && !playerHead && pointerStyle == PointerStyle.LCM }

    enum class PointerStyle(private val label: String) {
        ADM("ADM"), VANILLA("Vanilla"), LEGACY("1.8 Vanilla"), LCM("LCM");
        override fun toString() = label
    }

    private val roomDropdown by DropdownSetting("Room Colors", desc = "The color of each type of room.")
    val normalRoomColor by ColorSetting("Normal Room", Color(107, 58, 17), true, desc = "Color of normal rooms.").withDependency { roomDropdown }
    val puzzleRoomColor by ColorSetting("Puzzle Room", Color(117, 0, 133), true, desc = "Color of puzzle rooms.").withDependency { roomDropdown }
    val trapRoomColor by ColorSetting("Trap Room", Color(216, 127, 51), true, desc = "Color of trap rooms.").withDependency { roomDropdown }
    val bloodRoomColor by ColorSetting("Blood Room", Color(255, 0, 0), true, desc = "Color of blood rooms.").withDependency { roomDropdown }
    val entranceRoomColor by ColorSetting("Entrance Room", Color(20, 133, 0), true, desc = "Color of entrance rooms.").withDependency { roomDropdown }
    val fairyRoomColor by ColorSetting("Fairy Room", Color(224, 0, 255), true, desc = "Color of fairy rooms.").withDependency { roomDropdown }
    val championRoomColor by ColorSetting("Champion Room", Color(254, 223, 0), true, desc = "Color of champion rooms.").withDependency { roomDropdown }
    val rareRoomColor by ColorSetting("Rare Room", Color(255, 203, 89), true, desc = "Color of rare rooms.").withDependency { roomDropdown }
    val pendingRoomColor by ColorSetting("Pending Room", Color(55, 65, 90), true, desc = "Color of rooms you have entered that the map scan has not reported yet (no doors or map info).").withDependency { roomDropdown }
    val unknownRoomColor by ColorSetting("Unknown Room", Color(40, 40, 40), true, desc = "Color of unknown rooms hinted by a door with no discovered room on the other side.").withDependency { roomDropdown }

    private val doorDropdown by DropdownSetting("Door Colors", desc = "The color of each type of door.")
    val normalDoorColor by ColorSetting("Normal Door", Color(107, 58, 17).darker(), true, desc = "Color of normal doors.").withDependency { doorDropdown }
    val witherDoorColor by ColorSetting("Wither Door", Colors.BLACK, true, desc = "Color of wither doors.").withDependency { doorDropdown }
    val bloodDoorColor by ColorSetting("Blood Door", Color(255, 0, 0), true, desc = "Color of blood room doors.").withDependency { doorDropdown }
    val fairyDoorColor by ColorSetting("Fairy Door", Color(224, 0, 255).darker(), true, desc = "Color of fairy room doors.").withDependency { doorDropdown }
    val unknownDoorColor by ColorSetting("Unknown Door", Color(40, 40, 40).darker(), true, desc = "Color of doors with no discovered room on the other side.").withDependency { doorDropdown }

    val disablePred by BooleanSetting("Disable Prediction", false, desc = "Disables special-column room type prediction.")
    private val allowWebsocket by BooleanSetting("Parse map websocket", true, desc = "Parses the websocket data from your teammates update dungeon secrets in real time.")

    private val exampleRooms by lazy { buildExampleRooms() }
    private val exampleDoors by lazy { buildExampleDoors() }
    private const val SIDE = 128
    private val fullSize = SIDE to SIDE
    private val noSize = 0 to 0

    private val mapHud by HUD("Dungeon Map", "Displays the dungeon map.", false) { example -> drawHud(this, example) }

    private fun drawHud(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        val styled = if (LcmStyle.active()) LcmStyle.render(g, example) else MapSizes.renderHud(g, example)
        if (styled != null) return styled
        if (!example && (!DungeonUtils.inDungeons || (disableBoss && DungeonUtils.inBoss))) return noSize

        g.fill(0, 0, SIDE, SIDE, backgroundColor.rgba)
        g.hollowFill(0, 0, SIDE, SIDE, 1, backgroundOutline)
        val pose = g.pose()
        pose.pushMatrix()
        if (example) {
            pose.translate(5f, 5f)
            g.renderMap(exampleRooms, exampleDoors, emptyList())
        } else {
            pose.translate(DungeonScan.startX.toFloat(), DungeonScan.startY.toFloat())
            pose.scale(DungeonScan.roomSize / 16f)
            g.renderMap(DungeonScan.rooms, DungeonScan.doors.values, DungeonScan.pathHints)
            if (!DungeonUtils.inBoss) g.renderPlayers()
        }
        pose.popMatrix()
        return fullSize
    }

    private class Shared(val playerName: String, val roomName: String, val tiles: ArrayList<IVec2>, val foundSecrets: Int?)

    val syncSocket = webSocket {
        onMessage { text -> if (allowWebsocket && DungeonUtils.inDungeons) applyShared(text) }
    }

    private fun applyShared(text: String) {
        val shared = try { gson.fromJson(text, Shared::class.java) } catch (_: Exception) { return } ?: return
        if (DungeonUtils.dungeonTeammatesNoSelf.none { it.name == shared.playerName }) return
        val room = DungeonScan.rooms.firstOrNull { it.name == shared.roomName && it.tiles.size == shared.tiles.size && it.tiles.containsAll(shared.tiles) } ?: return
        val theirs = shared.foundSecrets
        if (theirs != null && (room.foundSecrets ?: -1) < theirs) room.foundSecrets = theirs
        room.playerWalkedInto = true
    }

    fun sendSync(room: DungeonRoom) {
        val roomName = room.name ?: return
        syncSocket.send(gson.toJson(Shared(mc.user.name, roomName, room.tiles, room.foundSecrets)))
    }
}
