package com.engineerclient.mapicons

import com.odtheking.odin.clickgui.settings.RenderableSetting
import com.odtheking.odin.clickgui.settings.Setting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.features.impl.dungeon.map.DungeonMap

/** Dungeon Map's settings in sections: the switches first, then the map's look, colors, players and prediction. */
object MapLayout {
    private val look = DropdownSetting("Map Look", desc = "Background, outline and sizes.")

    private val TOP = listOf("Dungeon Map", "Disable in Boss", "LCM Style", "Room Text", "Text Scaling", "Show Checkmarks", "Show Question Marks", "Parse map websocket")
    private val LOOK = listOf("Background Color", "Background Outline", "Outline Width", "Room Size", "Door Width", "Custom Map Padding", "Map Padding")
    private val PLAYERS = listOf("Players", "Player Names Scaling", "Player Name Color", "Own Player Head", "Player Head Scale", "Player Class Color Size", "Player Pointer Scale", "Own Marker Color")

    fun install() {
        val settings = DungeonMap.settings
        DungeonMap.registerSetting(look)
        for (name in LOOK) inside(settings[name], look)
        (settings["Players"] as? DropdownSetting)?.let { inside(settings["Own Marker Color"], it) }

        val order = TOP + listOf("Map Look") + LOOK + settings.keys.filter { it.startsWith("Room Colors") } +
            listOf("Normal Room", "Puzzle Room", "Trap Room", "Blood Room", "Entrance Room", "Fairy Room", "Champion Room", "Rare Room", "Pending Room", "Unknown Room",
                "Door Colors", "Normal Door", "Wither Door", "Blood Door", "Fairy Door", "Unknown Door") + PLAYERS + listOf("Disable Prediction")
        val rebuilt = LinkedHashMap<String, Setting<*>>()
        for (name in order) settings[name]?.let { rebuilt[name] = it }
        for ((k, v) in settings) if (k !in rebuilt) rebuilt[k] = v
        settings.clear(); settings.putAll(rebuilt)
    }

    /** Shows [setting] only while [drop] is open, on top of what it already waited for. */
    private fun inside(setting: Setting<*>?, drop: DropdownSetting) {
        val s = setting as? RenderableSetting<*> ?: return
        val before = s.visibilityDependency
        s.visibilityDependency = { drop.value && before?.invoke() != false }
    }
}
