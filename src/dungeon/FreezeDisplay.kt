package com.engineerclient.dungeon

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

object FreezeDisplay : Module(
    name = "Freeze Display",
    category = Category.RENDER,
    description = "Shows how long the server has been frozen once it passes a threshold.",
    key = null,
) {
    private val color by ColorSetting("Color", Color(245, 73, 39), false, desc = "Text colour.")
    private val threshold by NumberSetting("Threshold", 500, 100..2000, 100, desc = "Shown after this long without a server tick.", unit = "ms")
    private val dungeonsOnly by BooleanSetting("Only in Dungeons", true, desc = "Only shows in dungeons.")

    @Volatile private var lastTick = System.currentTimeMillis()

    private val hud by HUD("Freeze Display", "Time since the last server tick.", true, 400, 200, 2f) { example ->
        val since = System.currentTimeMillis() - lastTick
        val shown = example || (since > threshold && !mc.isLocalServer && (!dungeonsOnly || DungeonUtils.inDungeons))
        if (!shown) return@HUD 0 to 0
        val label = if (example) "567ms" else "${since}ms"
        text(label, 0, 0, color, true)
        mc.font.width(label) to mc.font.lineHeight
    }

    init {
        on<TickEvent.Server> { lastTick = System.currentTimeMillis() }
        on<LevelEvent.Load> { lastTick = System.currentTimeMillis() }
    }
}
