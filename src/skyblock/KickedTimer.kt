package com.engineerclient.skyblock

import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module

object KickedTimer : Module(
    name = "Kicked Timer",
    category = Category.SKYBLOCK,
    description = "Counts the minute after you are kicked from an instance.",
    key = null,
    toggled = true,
) {
    private var kickedAt = 0L

    private val hud by HUD("Kicked Timer", "Time since the kick, for a minute.", false, 10, 10, 1f) { example ->
        val elapsed = (System.currentTimeMillis() - kickedAt) / 1000.0
        if (!example && (kickedAt == 0L || elapsed > 60)) return@HUD 0 to 0
        val text = "§5Time kicked: §f${"%.2f".format(if (example) 12.34 else elapsed)}s"
        text(mc.font, text, 0, 0, -1)
        mc.font.width(text) to 9
    }

    init {
        on<MessageEvent.Chat> {
            if (message == "You were kicked while joining that server!" || message == "You are no longer allowed to access this instance!") {
                if (System.currentTimeMillis() - kickedAt > 60_000) kickedAt = System.currentTimeMillis()
            }
        }
    }
}
