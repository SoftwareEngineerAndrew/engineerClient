package com.engineerclient.dungeon

import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.render.textDim
import java.util.Locale

object WarpCooldown : Module(
    name = "Warp Cooldown",
    category = Category.DUNGEON,
    description = "Counts down until you can warp again after entering a dungeon or changing server.",
    key = null,
    toggled = true,
) {
    private const val INSTANCE_MS = 30_000L
    private const val WARP_MS = 4_000L

    private val instanceRegex = Regex("\\[[^]]+] \\w+ entered (?:MM )?\\w+ Catacombs, Floor \\w+!")
    private val warpRegex = Regex("^Sending to server .+")

    private var endTime = 0L

    private val hud by HUD("Warp Cooldown", "Time left until you can warp again.", true, 420, 70, 1f) { example ->
        val left = if (example) 21.4 else (endTime - System.currentTimeMillis()) / 1000.0
        if (left <= 0) return@HUD 0 to 0
        textDim("§bWarp Cooldown: §f${String.format(Locale.ROOT, "%.1f", left)}s", 0, 0)
    }

    init {
        on<MessageEvent.Chat> {
            if (endTime > System.currentTimeMillis()) return@on
            when {
                instanceRegex.containsMatchIn(message) -> endTime = System.currentTimeMillis() + INSTANCE_MS
                warpRegex.containsMatchIn(message) -> endTime = System.currentTimeMillis() + WARP_MS
            }
        }
    }
}
