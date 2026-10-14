package com.coffeeclient.misc

import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket

/**
 * Vanilla's Entity Distance turned down everywhere but F7/M7 phase 2 (Storm): from Storm's first
 * line to Goldor's it's back at 100%. Entities were the render thread's biggest cost in a
 * profile of a run (~15% of it: Hypixel's armor stands and name tags), and fewer drawn far away
 * is the cheapest cut; Storm is where you need to see across the arena.
 */
object EntityDistance : Module(
    name = "Entity Distance",
    key = null,
    category = Category.custom("Coffee Client", 860, 10),
    description = "Turns Entity Distance down for fps, except in Storm's phase (F7/M7 P2), where it's back to 100%.",
) {
    // Vanilla keeps this option in quarter steps (50%, 75%, 100%...), so the setting does too.
    private val distance by NumberSetting("Entity Distance", 50.0, 50.0..100.0, 25.0, desc = "Entity Distance (%) outside Storm's phase. Vanilla's own slider goes no lower than 50, in steps of 25.")

    private val CONTROL_CODES = Regex("§.")
    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"

    private var inStorm = false

    init {
        on<LevelEvent.Load> { inStorm = false }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            when (content.string.replace(CONTROL_CODES, "")) {
                STORM_START -> inStorm = true
                GOLDOR_START -> inStorm = false
            }
        }

        on<TickEvent.End> { apply(if (inStorm) 1.0 else distance / 100.0) }
    }

    /**
     * Sets vanilla's option, only when it differs (it's the same value the video settings show).
     * Rounded to the option's quarter steps first: it stores nothing between them, so an off-step
     * value (a config saved before the setting moved in 25s) would land a step low and differ forever.
     */
    private fun apply(scale: Double) {
        val opt = mc.options.entityDistanceScaling()
        val stepped = Math.round(scale * 4) / 4.0
        if (opt.get() != stepped) opt.set(stepped)
    }

    override fun onDisable() {
        super.onDisable()
        apply(1.0)
    }
}
