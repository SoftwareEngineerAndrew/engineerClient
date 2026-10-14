package com.engineerclient.practice

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.boss.TerminalTimes
import java.util.Locale

/**
 * Two Decimals, in Odin's own Terminal Times module: its terminal times and section splits in chat as
 * 5.12s, not 5.123s. Only the chat line changes (OdinTermTimesMixin, PersonalBestSimMixin): the stored
 * PBs keep Odin's full precision.
 */
object OdinTermTimes {
    private val twoDecimals = BooleanSetting("Two Decimals", false, desc = "Times in chat as 5.12s, not 5.123s (the PBs are still kept exact). Added by ADM.")

    fun install() {
        TerminalTimes.registerSetting(twoDecimals)
        ModuleManager.loadConfigurations()
    }

    private val DECIMAL = Regex("\\d+\\.\\d+")

    /** Odin's terminal time [message] with every decimal at 2 places, while Two Decimals is on. */
    @JvmStatic
    fun format(message: Any?): Any? {
        if (!twoDecimals.value || message !is String) return message
        return DECIMAL.replace(message) { String.format(Locale.ROOT, "%.2f", it.value.toDouble()) }
    }
}
