package com.engineerclient.skyblock

import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.network.chat.Component

object ArrowSwapTitle : Module(
    name = "Arrow Swap Title",
    category = Category.SKYBLOCK,
    description = "Shows your new arrow type when you swap arrows.",
    key = null,
    toggled = true,
) {
    private val swapped = Regex("^You set your selected arrow type to (.+)!$")
    private var arrow: Component = Component.empty()
    private var ticks = 0

    private val hud by HUD("Arrow Swap Title", "The arrow type you swapped to.", false, 400, 110, 2f) { example ->
        val text = if (example) Component.literal("§aToxic Arrow Poison") else if (ticks > 0) arrow else return@HUD 0 to 0
        text(mc.font, text, 0, 0, -1)
        mc.font.width(text) to 9
    }

    init {
        on<MessageEvent.Chat> {
            if (!swapped.matches(message)) return@on
            arrow = component.siblings.getOrNull(1) ?: Component.literal(swapped.find(message)!!.groupValues[1])
            ticks = 20
        }

        on<TickEvent.End> {
            if (ticks > 0) ticks--
        }
    }
}
