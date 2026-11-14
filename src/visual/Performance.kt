package com.engineerclient.visual

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.ServerUtils
import com.odtheking.odin.utils.render.textDim
import com.odtheking.odin.utils.toFixed
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object Performance : Module(
    name = "Performance",
    category = Category.RENDER,
    description = "FPS, TPS, ping, clock and CPS HUDs, and a performance recorder.",
    key = null,
) {
    enum class Direction { HORIZONTAL, VERTICAL }

    private enum class LabelPosition(private val label: String) {
        RIGHT("Right"), LEFT("Left"), HIDDEN("Hidden");
        override fun toString() = label
    }

    private val nameColor by ColorSetting("Name Color", Color(50, 150, 220), desc = "The color of the stat information.")
    private val valueColor by ColorSetting("Value Color", Colors.WHITE, desc = "The color of the stat values.")
    private val direction by SelectorSetting("Direction", Direction.HORIZONTAL, "Direction the information is displayed.")
    private val showFPS by BooleanSetting("Show FPS", true, desc = "Shows the FPS in the HUD.")
    private val showTPS by BooleanSetting("Show TPS", true, desc = "Shows the TPS in the HUD.")
    private val showPing by BooleanSetting("Show Ping", true, desc = "Shows the ping in the HUD.")

    private val seconds by BooleanSetting("Show Seconds", false, desc = "Adds seconds to the clock.")
    private val clockColor by ColorSetting("Clock Color", Color(255, 134, 0, 1f), false, desc = "Colour of the clock.")
    private val cpsLabelPosition by SelectorSetting("CPS Label Position", LabelPosition.RIGHT, desc = "Where the CPS label goes.")
    private val cpsNumberColor by ColorSetting("CPS Number Color", Color(255, 255, 255, 1f), false, desc = "Colour of the click counts.")
    private val cpsSeparatorColor by ColorSetting("CPS Separator Color", Color(170, 170, 170, 1f), false, desc = "Colour of the separator.")
    private val cpsLabelColor by ColorSetting("CPS Label Color", Color(85, 255, 255, 1f), false, desc = "Colour of the CPS label.")

    private var record by BooleanSetting("Record Performance", false, desc = "Logs frame times and hitches to logs/perf.")
    private var switchedOn by BooleanSetting("Switched On", false, desc = "").hide()

    val recording get() = enabled && record

    fun enableMonitorByDefault() {
        if (switchedOn) return
        switchedOn = true
        record = true
        if (!enabled) toggle()
        ModuleManager.saveConfigurations()
    }

    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val clockFormatSeconds = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val leftClicks = ArrayDeque<Long>()
    private val rightClicks = ArrayDeque<Long>()

    private val hud by HUD("Performance HUD", "Shows performance information on the screen.", true) {
        if (!showFPS && !showTPS && !showPing) return@HUD 0 to 0

        var width = 1
        var height = 1
        val lineHeight = mc.font.lineHeight

        fun renderMetric(label: String, value: String) {
            val w = drawText(label, value, if (direction == Direction.HORIZONTAL) width else 1, height)
            if (direction == Direction.HORIZONTAL) width += w
            else {
                width = maxOf(width, w)
                height += lineHeight
            }
        }

        if (showTPS) renderMetric("TPS: ", "${ServerUtils.averageTps.toFixed(1)} ")
        if (showFPS) renderMetric("FPS: ", "${mc.fps} ")
        if (showPing) renderMetric("Ping: ", "${ServerUtils.averagePing}ms ")

        width to if (direction == Direction.HORIZONTAL) lineHeight else height
    }

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
            PerfMonitor.mark("world load")
        }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ -> PerfMonitor.mark("screen " + screen.javaClass.simpleName) }
    }

    private fun GuiGraphicsExtractor.drawText(name: String, value: String, x: Int, y: Int): Int {
        var width = 0
        width += textDim(name, x, y, nameColor, true).first
        width += textDim(value, x + width, y, valueColor, true).first
        return width
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
