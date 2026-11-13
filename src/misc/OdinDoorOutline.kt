package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.impl.dungeon.DoorHighlight
import com.odtheking.odin.utils.render.BoxStyle

object OdinDoorOutline {

    private val outlineOnly = BooleanSetting("Outline Only", false, desc = "Doors are drawn as an outline with no fill. Added by ADM.")

    @JvmStatic
    fun style(original: BoxStyle): BoxStyle = if (outlineOnly.value) BoxStyle.OUTLINE else original

    fun install() {
        DoorHighlight.registerSetting(outlineOnly)
    }
}
