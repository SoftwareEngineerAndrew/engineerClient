package com.engineerclient.mapicons

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.features.impl.dungeon.map.DungeonMap

/**
 * Toggles for the icons on ADM's Dungeon Map: the room check marks (green, white, red cross) and the "?" on
 * guess tiles. ADM's map asks these before drawing either; LCM Style's check marks and Room
 * Prediction's "?" are drawn here and ask the same settings.
 */
object MapIcons {
    private val checkmarks = BooleanSetting("Show Checkmarks", true,
        desc = "The check marks on rooms (green, white, the red cross for a failed puzzle), in ADM's look and LCM Style. Added by engineerClient.")
    private val checkStyle = SelectorSetting("Checkmark Style", CheckStyle.LCM,
        desc = "LCM's check icons, or Hypixel's own marks copied from the map.").withDependency { checkmarks.value }
    private val questionMarks = BooleanSetting("Show Question Marks", true,
        desc = "The \"?\" on guess tiles (ADM's path hints and Room Prediction's). Added by engineerClient.")

    val showCheckmarks: Boolean get() = checkmarks.value
    val showQuestionMarks: Boolean get() = questionMarks.value
    val lcmChecks: Boolean get() = checkStyle.value == CheckStyle.LCM

    enum class CheckStyle(private val label: String) {
        LCM("LCM"), HYPIXEL("Hypixel");
        override fun toString() = label
    }

    fun install() {
        DungeonMap.registerSetting(checkmarks)
        DungeonMap.registerSetting(checkStyle)
        DungeonMap.registerSetting(questionMarks)
    }
}
