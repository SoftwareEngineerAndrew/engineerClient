package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.boss.TickTimers

/**
 * "Goldor Count Up" for Odin's Tick Timers: the Goldor Hud's Tick timer counts up from Goldor's
 * first line like a split (3.5, ... 12.2, ...) instead of down to the next death tick. The colour
 * still follows the time left to the next tick, green / yellow / red as Odin's does, so it goes
 * back to green after each tick. Registered into Odin's module like the Splits look (see
 * OdinSplitsLook); `TickTimersGoldorMixin` asks [tick] for the text where the Goldor Hud formats
 * its timer.
 */
object OdinTickTimers {

    private const val TICK_PREFIX = "§7Tick:"
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"

    private val countUp = BooleanSetting("Goldor Count Up", false, desc = "The Goldor Hud's Tick timer counts up from Goldor's first line like a split, past 3 s; the colour still goes green / yellow / red with each tick. Added by engineerClient.")
        .withDependency { (TickTimers.settings["Goldor Hud"] as? HUDSetting)?.value?.enabled ?: true }

    private var serverTicks = 0
    private var goldorStart: Int? = null

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
        // The HUD preview (no Goldor phase): 3.5 s.
        val elapsed = goldorStart?.let { serverTicks - it } ?: 70
        return format(elapsed, max, prefix, colour)
    }

    /** Adds the setting to Odin's module; before OdinSplitsLook.install, which re-reads Odin's config. */
    fun install() {
        TickTimers.registerSetting(countUp)
        on<TickEvent.Server> { serverTicks++ }
        on<MessageEvent.Chat> { if (message == GOLDOR_START) goldorStart = serverTicks }
        on<LevelEvent.Load> { goldorStart = null }
        EventBus.subscribe(this)
    }
}
