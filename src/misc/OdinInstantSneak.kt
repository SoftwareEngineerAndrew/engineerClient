package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.impl.render.Camera

object OdinInstantSneak {

    private val instantSneak = BooleanSetting("Instant Sneak", false, desc = "The camera snaps to sneak height instead of sliding. Added by ADM.")

    @JvmStatic
    fun active(): Boolean = Camera.enabled && instantSneak.value

    fun install() {
        Camera.registerSetting(instantSneak)
    }
}
