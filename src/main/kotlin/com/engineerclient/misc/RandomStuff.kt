package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.ChatMessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module

/**
 * Grab bag of small independent toggles that don't warrant their own module.
 * Each setting is self-contained; add more here rather than spinning up a
 * new module for a one-off QoL toggle.
 */
object RandomStuff : Module(
    name = "Random Stuff",
    category = Category.custom("Blood Rush"),
    description = "A collection of small unrelated QoL toggles."
) {
    private val hideChat by BooleanSetting("Hide Chat", false, desc = "Hides all chat messages.")
    private val hideDamage by BooleanSetting("Hide Damage Indicators", false, desc = "Suppresses the red hurt-flash overlay when you take damage.")

    init {
        on<ChatMessageEvent> {
            if (hideChat) cancel()
        }

        on<TickEvent.End> {
            if (hideDamage) mc.player?.hurtTime = 0
        }
    }
}
