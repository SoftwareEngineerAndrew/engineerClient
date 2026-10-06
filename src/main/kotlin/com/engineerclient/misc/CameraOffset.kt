package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module

/**
 * The first-person camera moved along where you look: + forward, - back, in blocks
 * (CameraOffsetMixin, after the camera is put at your eyes). Third person is left alone.
 */
object CameraOffset : Module(
    name = "Camera Offset",
    category = Category.custom("Engineer Client", 1030, 10),
    description = "Moves the first-person camera forward (+) or back (-) along where you look, up to a block.",
) {
    private val offset by NumberSetting("Offset", 0.0, -1.0..1.0, 0.05, desc = "Blocks: + forward, - back.")

    /** The offset now (0 while off). */
    @JvmStatic fun blocks(): Float = if (enabled) offset.toFloat() else 0f
}
