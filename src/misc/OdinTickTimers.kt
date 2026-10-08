package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.features.impl.boss.TickTimers

/**
 * "Goldor Count Up" for Odin's Tick Timers: the Goldor Hud's Tick timer counts up from the last
 * Goldor tick (0.00 to 2.95, back to 0.00 on the tick) like a split, instead of down to the next.
 * The colour still follows the time left to the tick, green / yellow / red as Odin's does.
 * Registered into Odin's module like the Splits look (see OdinSplitsLook); `TickTimersGoldorMixin`
 * asks [tick] for the text where the Goldor Hud formats its timer.
 */
object OdinTickTimers {

    private const val TICK_PREFIX = "§7Tick:"

    private val countUp = BooleanSetting("Goldor Count Up", false, desc = "The Goldor Hud's Tick timer counts up from the last tick like a split, in the same green / yellow / red. Added by engineerClient.")
        .withDependency { (TickTimers.settings["Goldor Hud"] as? HUDSetting)?.value?.enabled ?: true }

    /**
     * The Goldor Hud's timer as Odin formats it ([format]: time, max, prefix, colour or null for
     * Odin's own), counting up instead for the Tick timer with Goldor Count Up on.
     */
    @JvmStatic
    fun tick(time: Int, max: Int, prefix: String, format: (Int, Int, String, String?) -> String): String {
        if (!countUp.value || prefix != TICK_PREFIX) return format(time, max, prefix, null)
        val colour = when {
            time >= max * 0.66f -> "§a"
            time >= max * 0.33f -> "§6"
            else -> "§c"
        }
        return format((max - time).mod(max), max, prefix, colour)
    }

    /** Adds the setting to Odin's module; before OdinSplitsLook.install, which re-reads Odin's config. */
    fun install() {
        TickTimers.registerSetting(countUp)
    }
}
