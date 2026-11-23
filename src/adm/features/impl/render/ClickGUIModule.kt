package com.adm.features.impl.render

import com.google.gson.annotations.SerializedName
import com.mojang.blaze3d.platform.InputConstants
import com.adm.AdmMod
import com.adm.clickgui.ClickGUI
import com.adm.clickgui.HudManager
import com.adm.clickgui.settings.AlwaysActive
import com.adm.clickgui.settings.impl.*
import com.adm.events.*
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.adm.features.ModuleManager
import com.adm.features.impl.dungeon.map.DungeonMap
import com.adm.utils.*
import com.adm.utils.network.WebUtils.fetchJson
import com.adm.utils.network.WebUtils.postData
import com.adm.utils.skyblock.LocationUtils
import kotlinx.coroutines.launch
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.net.URI

@AlwaysActive
object ClickGUIModule : Module(
    name = "Click GUI",
    description = "Allows you to customize the UI.",
    key = InputConstants.KEY_RSHIFT
) {
    val hudSnap by NumberSetting("HUD Snap Distance", 5, 0..20, 1, desc = "How close a dragged HUD has to be to another to snap to it; 0 is off.", unit = "px")
    val timerPrefixes by BooleanSetting("Timer Prefixes", true, desc = "Labels like \"Rag:\" in front of timers; off hides them all, on leaves each timer's own toggle.")
    val clickGuiScale by NumberSetting("Click GUI Size", 2f, 1.0..4.0, 0.25, desc = "GUI scale the Click GUI is drawn at, whatever the video setting says.")
    val enableNotification by BooleanSetting("Chat notifications", true, desc = "Sends a message when you toggle a module with a keybind")
    val clickGUIColor by ColorSetting("Color", Color(50, 150, 220), desc = "The color of the Click GUI.")
    private val action by ActionSetting("Open HUD Editor", desc = "Opens the HUD editor when clicked.") { mc.setScreenAndShow(HudManager) }

    val hypixelApiUrl by StringSetting("API url", "https://hypixel.odtheking.com/", 128, "The Hypixel API server to connect to.", placeholder = "https://hypixel.odtheking.com/").hide()
    val webSocketUrl by StringSetting("Socket URL", "wss://ws.odtheking.com/", 128, "The Websocket server to connect to.", placeholder = "wss://ws.odtheking.com/").hide()

    val devMessage by BooleanSetting("Developer Message", false, desc = "Sends development related messages to the chat.")

    init {
        on<SecretsUpdateEvent> { DungeonMap.sendSync(room) }

        on<FloorEnterEvent> {
            LocationUtils.lobbyId?.let { DungeonMap.syncSocket.connect("${webSocketUrl}$it") } ?: devMessage("Failed to connect to dungeon websocket, lobbyId is null.")
        }

        on<RoomEnterEvent> {
            if (room == null) DungeonMap.syncSocket.shutdown()
            else DungeonMap.sendSync(room)
        }

        on<LevelEvent.Load> {
            DungeonMap.syncSocket.shutdown()
        }
    }

    val dungeonCoresLogging by BooleanSetting("Core loggings", false, desc = "")

    val favoriteColors by MapSetting("Favorite Colors", mutableMapOf<Int, Color>())

    override fun onKeybind() {
        mc.setScreenAndShow(ClickGUI)
    }

    override fun onEnable() {
        mc.setScreenAndShow(ClickGUI)
        super.onEnable()
        toggle()
    }

    val panelSetting by MapSetting("Panel Data", mutableMapOf<String, PanelData>())
    data class PanelData(var x: Int, var y: Int, var extended: Boolean = true)

    fun resetPositions() {
        Category.categories.forEach { (categoryName, category) ->
            panelSetting.getOrPut(categoryName) { PanelData(0, 0) }.apply {
                x = category.x
                y = category.y
                extended = true
            }
        }
    }
}
