package com.engineerclient.visual

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.events.LevelEvent
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.adm.features.ModuleManager
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.ServerUtils
import com.adm.utils.render.textDim
import com.adm.utils.FormatCache
import com.adm.utils.toFixed
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

    private val leftClicks = ClickWindow()
    private val rightClicks = ClickWindow()

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

        if (showTPS) renderMetric("TPS: ", "${tpsText(ServerUtils.averageTps.toDouble())} ")
        if (showFPS) renderMetric("FPS: ", "${mc.fps} ")
        if (showPing) renderMetric("Ping: ", "${ServerUtils.averagePing}ms ")

        width to if (direction == Direction.HORIZONTAL) lineHeight else height
    }

    private val clockHud by HUD("Clock HUD", "The system time.", true, 10, 10, 1f) {
        refreshClock()
        text(mc.font, clockLine, 0, 0, clockColor.rgba)
        clockSize
    }

    private val cpsHud by HUD("CPS HUD", "Left and right clicks in the last second.", true, 10, 22, 1f) {
        refreshCps()
        text(mc.font, cpsLine, 0, 0, -1)
        cpsSize
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

    private val tpsText = FormatCache(1) { it.toFloat().toFixed(1) }

    private var clockSecond = Long.MIN_VALUE
    private var clockWithSeconds = false
    private var clockLine = ""
    private var clockSize = 0 to 9

    private fun refreshClock() {
        val second = Math.floorDiv(System.currentTimeMillis(), 1000L)
        val withSeconds = seconds
        if (second == clockSecond && withSeconds == clockWithSeconds) return
        clockSecond = second
        clockWithSeconds = withSeconds
        val line = LocalTime.now().format(if (withSeconds) clockFormatSeconds else clockFormat)
        if (line == clockLine) return
        clockLine = line
        clockSize = mc.font.width(line) to 9
    }

    private var cpsKey = Long.MIN_VALUE
    private val cpsColors = IntArray(3)
    private var cpsLine: Component = Component.empty()
    private var cpsSize = 0 to 9

    private fun refreshCps() {
        val now = System.currentTimeMillis()
        val left = leftClicks.count(now)
        val right = rightClicks.count(now)
        val label = cpsLabelColor.rgba
        val number = cpsNumberColor.rgba
        val separator = cpsSeparatorColor.rgba
        val position = cpsLabelPosition
        val key = left.toLong() shl 32 or (right.toLong() shl 2) or position.ordinal.toLong()
        val colors = cpsColors
        if (key == cpsKey && colors[0] == label && colors[1] == number && colors[2] == separator) return
        cpsKey = key
        colors[0] = label
        colors[1] = number
        colors[2] = separator
        val line = Component.empty()
        if (position == LabelPosition.LEFT) line.append(Component.literal("CPS ").withColor(label))
        line.append(Component.literal(left.toString()).withColor(number))
        line.append(Component.literal(" | ").withColor(separator))
        line.append(Component.literal(right.toString()).withColor(number))
        if (position == LabelPosition.RIGHT) line.append(Component.literal(" CPS").withColor(label))
        cpsLine = line
        cpsSize = mc.font.width(line) to 9
    }

    // Click times of the last second, oldest first; trimmed on every add so it stays small with the HUD off.
    private class ClickWindow {
        private var times = LongArray(16)
        private var head = 0
        private var size = 0

        fun add(now: Long) {
            drop(now)
            if (size == times.size) {
                val grown = LongArray(size * 2)
                for (i in 0 until size) grown[i] = times[(head + i) % times.size]
                times = grown
                head = 0
            }
            times[(head + size) % times.size] = now
            size++
        }

        fun count(now: Long): Int {
            drop(now)
            return size
        }

        fun clear() {
            head = 0
            size = 0
        }

        private fun drop(now: Long) {
            while (size > 0 && now - times[head] > 1000) {
                head = (head + 1) % times.size
                size--
            }
        }
    }

    @JvmStatic
    fun addLeftClick() {
        if (enabled) leftClicks.add(System.currentTimeMillis())
    }

    @JvmStatic
    fun addRightClick() {
        if (enabled) rightClicks.add(System.currentTimeMillis())
    }
}
