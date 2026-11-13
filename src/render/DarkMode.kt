package com.engineerclient.render

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.client.gui.GuiGraphicsExtractor

object DarkMode : Module(
    name = "Dark Mode",
    category = Category.RENDER,
    description = "Darkens the screen.",
    key = null,
) {
    private val opacity by NumberSetting("Opacity", 25, 1..80, 1, desc = "Strength of the dark tint.", unit = "%")
    private val tintHud by BooleanSetting("Tint HUD", false, desc = "The tint covers the HUD too.")

    fun draw(graphics: GuiGraphicsExtractor, afterHud: Boolean) {
        if (!enabled || tintHud != afterHud) return
        val alpha = (opacity * 255 / 100) shl 24
        graphics.fill(0, 0, mc.window.guiScaledWidth, mc.window.guiScaledHeight, alpha)
    }
}
