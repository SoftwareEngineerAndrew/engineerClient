package com.engineerclient.visual

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
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
import com.adm.utils.render.text
import com.adm.utils.FormatCache
import com.adm.utils.toFixed
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    private val colorPing by BooleanSetting("Color Ping", false, desc = "Colors the ping green, yellow or red by how high it is.")

    private val seconds by BooleanSetting("Show Seconds", false, desc = "Adds seconds to the clock.")
    private val twelveHour by BooleanSetting("12 Hour Clock", false, desc = "Shows the clock as 12-hour time with AM/PM.")
    private val clockColor by ColorSetting("Clock Color", Color(255, 134, 0, 1f), false, desc = "Colour of the clock.")
    private val cpsLabelPosition by SelectorSetting("CPS Label Position", LabelPosition.RIGHT, desc = "Where the CPS label goes.")
    private val cpsNumberColor by ColorSetting("CPS Number Color", Color(255, 255, 255, 1f), false, desc = "Colour of the click counts.")
    private val cpsSeparatorColor by ColorSetting("CPS Separator Color", Color(170, 170, 170, 1f), false, desc = "Colour of the separator.")
    private val cpsLabelColor by ColorSetting("CPS Label Color", Color(85, 255, 255, 1f), false, desc = "Colour of the CPS label.")

    private var record by BooleanSetting("Record Performance", false, desc = "Logs frame times and hitches to logs/perf.")
    private val sampleStacks by BooleanSetting("Sample Stacks", false, desc = "Also records where the game thread is, 100 times a second.").withDependency { record }
    private var switchedOn by BooleanSetting("Switched On", false, desc = "").hide()

    val recording get() = enabled && record
    val sampling get() = recording && sampleStacks

    fun enableMonitorByDefault() {
        if (switchedOn) return
        switchedOn = true
        record = true
        if (!enabled) toggle()
        ModuleManager.saveConfigurations()
    }

    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val clockFormatSeconds = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val clockFormat12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val clockFormat12Seconds = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH)

    private val leftClicks = ClickWindow()
    private val rightClicks = ClickWindow()

    private val hud by HUD("Performance HUD", "Shows performance information on the screen.", true) {
        if (!showFPS && !showTPS && !showPing) return@HUD 0 to 0
        val vertical = direction == Direction.VERTICAL
        val lineHeight = mc.font.lineHeight
        var x = 1
        var y = 1
        var widest = 1
        for (i in 0..2) {
            val value = when (i) {
                0 -> if (showTPS) tpsText(ServerUtils.averageTps.toDouble()) else continue
                1 -> if (showFPS) fpsText(mc.fps.toDouble()) else continue
                else -> if (showPing) pingText(ServerUtils.averagePing.toDouble()) else continue
            }
            val w = metric(metricLabels[i], value, x, y, if (i == 2 && colorPing) pingColor(ServerUtils.averagePing) else valueColor)
            if (vertical) {
                if (w > widest) widest = w
                y += lineHeight
            } else x += w
        }
        hudSize(if (vertical) widest else x, if (vertical) y else lineHeight)
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

    private val metricLabels = arrayOf("TPS: ", "FPS: ", "Ping: ")

    private fun pingColor(ping: Int): Color = when {
        ping < 100 -> Colors.MINECRAFT_GREEN
        ping < 200 -> Colors.MINECRAFT_YELLOW
        else -> Colors.MINECRAFT_RED
    }

    private fun GuiGraphicsExtractor.metric(name: String, value: String, x: Int, y: Int, color: Color): Int {
        val font = mc.font
        text(name, x, y, nameColor, true)
        val nameWidth = font.width(name)
        text(value, x + nameWidth, y, color, true)
        return nameWidth + font.width(value)
    }

    private val tpsText = FormatCache(1) { it.toFloat().toFixed(1) + " " }
    private val fpsText = FormatCache(1) { "${it.toInt()} " }
    private val pingText = FormatCache(1) { "${it.toInt()}ms " }

    private var lastHudSize = 0 to 0

    private fun hudSize(width: Int, height: Int): Pair<Int, Int> {
        val size = lastHudSize
        if (size.first != width || size.second != height) lastHudSize = width to height
        return lastHudSize
    }

    private var clockSecond = Long.MIN_VALUE
    private var clockWithSeconds = false
    private var clockTwelveHour = false
    private var clockLine = ""
    private var clockSize = 0 to 9

    private fun refreshClock() {
        val second = Math.floorDiv(System.currentTimeMillis(), 1000L)
        val withSeconds = seconds
        val twelve = twelveHour
        if (second == clockSecond && withSeconds == clockWithSeconds && twelve == clockTwelveHour) return
        clockSecond = second
        clockWithSeconds = withSeconds
        clockTwelveHour = twelve
        val format = if (twelve) (if (withSeconds) clockFormat12Seconds else clockFormat12) else if (withSeconds) clockFormatSeconds else clockFormat
        val line = LocalTime.now().format(format)
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
