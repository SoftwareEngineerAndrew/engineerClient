package com.coffeeclient.misc

import com.engineerclient.pov.PovCapture
import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module

/**
 * The first-person camera moved along where you look: + forward, - back, in blocks
 * (CameraOffsetMixin, after the camera is put at your eyes). Third person is left alone.
 */
object CameraOffset : Module(
    name = "Camera Offset",
    key = null,
    category = Category.custom("Coffee Client", 860, 10),
    description = "Moves the first-person camera forward (+) or back (-) along where you look, up to a block.",
) {
    private val offset by NumberSetting("Offset", 0.0, -1.0..1.0, 0.05, desc = "Blocks: + forward, - back.")

    /**
     * The offset now (0 while off, and during a POV Previews feed pass: a teammate's feed shows their own eyes).
     * Only while the camera is on them: the restore pass after the feeds puts it back on you and keeps your offset.
     */
    @JvmStatic fun blocks(): Float =
        if (enabled && !(PovCapture.capturing && mc.cameraEntity !== mc.player)) offset.toFloat() else 0f
}
