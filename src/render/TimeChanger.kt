package com.engineerclient.render

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import java.time.LocalTime

object TimeChanger : Module(
    name = "Time Changer",
    category = Category.RENDER,
    description = "Changes the time of day the sky shows.",
    key = null,
) {
    private enum class Time(val ticks: Long) { DAY(1000), NOON(6000), SUNSET(12000), NIGHT(13000), MIDNIGHT(18000), SUNRISE(23000), REAL_TIME(-1), CUSTOM(-1) }

    private val mode by SelectorSetting("Time", Time.NOON, desc = "Time of day to show.")
    private val customTime by NumberSetting("Custom Time", 6000, 0..23999, 100, desc = "World time in ticks.").withDependency { mode == Time.CUSTOM }

    @JvmField var extractingSky = false

    fun active() = enabled

    fun time(): Long = when (mode) {
        Time.REAL_TIME -> LocalTime.now().let { Math.floorMod(it.hour * 1000L + it.minute * 1000L / 60 - 6000, 24000L) }
        Time.CUSTOM -> customTime.toLong()
        else -> mode.ticks
    }
}
