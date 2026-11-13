package com.engineerclient.visual

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import net.minecraft.network.chat.Component
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object InfoDisplay : Module(
    name = "Info Display",
    category = Category.RENDER,
    description = "Shows the system time and your clicks per second on screen.",
    key = null,
) {
    private enum class LabelPosition(private val label: String) {
        RIGHT("Right"), LEFT("Left"), HIDDEN("Hidden");
        override fun toString() = label
    }

    private val seconds by BooleanSetting("Show Seconds", false, desc = "Adds seconds to the clock.")
    private val clockColor by ColorSetting("Clock Color", Color(255, 134, 0, 1f), false, desc = "Colour of the clock.")
    private val cpsLabelPosition by SelectorSetting("CPS Label Position", LabelPosition.RIGHT, desc = "Where the CPS label goes.")
    private val cpsNumberColor by ColorSetting("CPS Number Color", Color(255, 255, 255, 1f), false, desc = "Colour of the click counts.")
    private val cpsSeparatorColor by ColorSetting("CPS Separator Color", Color(170, 170, 170, 1f), false, desc = "Colour of the separator.")
    private val cpsLabelColor by ColorSetting("CPS Label Color", Color(85, 255, 255, 1f), false, desc = "Colour of the CPS label.")

    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val clockFormatSeconds = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val leftClicks = ArrayDeque<Long>()
    private val rightClicks = ArrayDeque<Long>()

    private val clockHud by HUD("Clock HUD", "The system time.", true, 10, 10, 1f) {
        val text = LocalTime.now().format(if (seconds) clockFormatSeconds else clockFormat)
        text(mc.font, text, 0, 0, clockColor.rgba)
        mc.font.width(text) to 9
    }

    private val cpsHud by HUD("CPS HUD", "Left and right clicks in the last second.", true, 10, 22, 1f) {
        val comp = Component.empty().apply {
            if (cpsLabelPosition == LabelPosition.LEFT) append(Component.literal("CPS ").withColor(cpsLabelColor.rgba))
            append(Component.literal(cps(leftClicks).toString()).withColor(cpsNumberColor.rgba))
            append(Component.literal(" | ").withColor(cpsSeparatorColor.rgba))
            append(Component.literal(cps(rightClicks).toString()).withColor(cpsNumberColor.rgba))
            if (cpsLabelPosition == LabelPosition.RIGHT) append(Component.literal(" CPS").withColor(cpsLabelColor.rgba))
        }
        text(mc.font, comp, 0, 0, -1)
        mc.font.width(comp) to 9
    }

    init {
        on<LevelEvent.Load> {
            leftClicks.clear()
            rightClicks.clear()
        }
    }

    private fun cps(clicks: ArrayDeque<Long>): Int {
        val now = System.currentTimeMillis()
        while (clicks.isNotEmpty() && now - clicks.first() > 1000) clicks.removeFirst()
        return clicks.size
    }

    @JvmStatic
    fun addLeftClick() {
        if (enabled) leftClicks.addLast(System.currentTimeMillis())
    }

    @JvmStatic
    fun addRightClick() {
        if (enabled) rightClicks.addLast(System.currentTimeMillis())
    }
}
