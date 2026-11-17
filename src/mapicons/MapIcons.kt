package com.engineerclient.mapicons

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.features.impl.dungeon.map.DungeonMap

/**
 * Toggles for the icons on ADM's Dungeon Map: the room check marks (green, white, red cross) and the "?" on
 * guess tiles. ADM's map asks these before drawing either; LCM Style's check marks and Room
 * Prediction's "?" are drawn here and ask the same settings.
 */
object MapIcons {
    private val checkmarks = BooleanSetting("Show Checkmarks", true,
        desc = "The check marks on rooms (green, white, the red cross for a failed puzzle), in ADM's look and LCM Style. Added by engineerClient.")
    private val questionMarks = BooleanSetting("Show Question Marks", true,
        desc = "The \"?\" on guess tiles (ADM's path hints and Room Prediction's). Added by engineerClient.")

    val showCheckmarks: Boolean get() = checkmarks.value
    val showQuestionMarks: Boolean get() = questionMarks.value

    fun install() {
        DungeonMap.registerSetting(checkmarks)
        DungeonMap.registerSetting(questionMarks)
    }
}
