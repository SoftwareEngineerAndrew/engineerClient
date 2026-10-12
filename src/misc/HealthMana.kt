package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.clickgui.settings.impl.HudElement
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.skyblock.PlayerDisplay
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.skyblock.ActionBarListener
import com.odtheking.odin.utils.skyblock.LocationUtils
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Health and mana: bar versions of Odin's Health HUD and Mana HUD, in the colours set in Odin's
 * Player Display, and an option to hide Odin's text HUDs and these bars until the stat runs low.
 * Each bar is its own HUD so it can be placed and toggled on its own; unlike the text, a bar stays
 * up at 0 as an empty bar.
 */
object HealthMana : Module(
    name = "Health & Mana",
    category = Category.custom("Engineer Client"),
    description = "Health and mana bars in Odin's Player Display colours, and hiding Odin's Health/Mana HUDs until the stat runs low.",
    key = null,
) {
    private val hideUnlessLow by BooleanSetting("Hide Health/Mana Above %", false, desc = "Hides Odin's Health HUD and Mana HUD, and the Health/Mana Bar HUDs below, unless the stat drops below the threshold below.")
    private val threshold by NumberSetting("Threshold", 50, 1, 100, 1, desc = "Only show the Health/Mana HUDs once the stat drops below this percent of max.", unit = "%").withDependency { hideUnlessLow }

    private val healthBarHud by HUD("Health Bar HUD", "Your health as a filled bar, in Odin's Player Display Health Color.", true, 434, 501, 1.6f) { example ->
        val (current, max) = when {
            example -> 3000 to 4000
            !LocationUtils.isInSkyblock || ActionBarListener.maxHealth == 0 -> return@HUD 0 to 0
            aboveThreshold(ActionBarListener.currentHealth, ActionBarListener.maxHealth) -> return@HUD 0 to 0
            else -> ActionBarListener.currentHealth to ActionBarListener.maxHealth
        }
        statBar(current, max, playerDisplayColor("Health Color", Colors.MINECRAFT_RED), healthBarWidth, healthBarHeight)
    }
    private val healthBarWidth by NumberSetting("Health Bar Width", 60, 20, 200, 5, desc = "Width of the health bar.")
    private val healthBarHeight by NumberSetting("Health Bar Height", 8, 2, 30, 1, desc = "Height of the health bar.")

    private val manaBarHud by HUD("Mana Bar HUD", "Your mana as a filled bar, in Odin's Player Display Mana Color.", true, 434, 480, 1.6f) { example ->
        val (current, max) = when {
            example -> 2000 to 20000
            !LocationUtils.isInSkyblock || ActionBarListener.maxMana == 0 -> return@HUD 0 to 0
            aboveThreshold(ActionBarListener.currentMana, ActionBarListener.maxMana) -> return@HUD 0 to 0
            else -> ActionBarListener.currentMana to ActionBarListener.maxMana
        }
        statBar(current, max, playerDisplayColor("Mana Color", Colors.MINECRAFT_AQUA), manaBarWidth, manaBarHeight)
    }
    private val manaBarWidth by NumberSetting("Mana Bar Width", 60, 20, 200, 5, desc = "Width of the mana bar.")
    private val manaBarHeight by NumberSetting("Mana Bar Height", 8, 2, 30, 1, desc = "Height of the mana bar.")

    /**
     * Whether Odin's own Health HUD or Mana HUD should be skipped this frame: Hide Health/Mana Above
     * % is on and the stat is above the threshold. Read by HudElementMixin for every Odin HUD element; only
     * those two are ever skipped.
     */
    fun hidesOdinHud(hud: HudElement): Boolean {
        if (!enabled || !hideUnlessLow) return false
        return when {
            hud === (PlayerDisplay.settings["Health HUD"] as? HUDSetting)?.value -> aboveThreshold(ActionBarListener.currentHealth, ActionBarListener.maxHealth)
            hud === (PlayerDisplay.settings["Mana HUD"] as? HUDSetting)?.value -> aboveThreshold(ActionBarListener.currentMana, ActionBarListener.maxMana)
            else -> false
        }
    }

    private fun aboveThreshold(current: Int, max: Int): Boolean =
        hideUnlessLow && max > 0 && current.toFloat() / max >= threshold / 100f

    private fun playerDisplayColor(name: String, fallback: Color): Color =
        (PlayerDisplay.settings[name] as? ColorSetting)?.value ?: fallback

    /** A bar filled to [current]/[max] over a dark background. */
    private fun GuiGraphicsExtractor.statBar(current: Int, max: Int, color: Color, width: Int, height: Int): Pair<Int, Int> {
        val pct = if (max <= 0) 0f else (current.toFloat() / max).coerceIn(0f, 1f)
        fill(0, 0, width, height, Colors.BLACK.withAlpha(0.6f).rgba)
        val filled = (width * pct).toInt().let { if (pct > 0f) it.coerceAtLeast(1) else it }
        fill(0, 0, filled, height, color.rgba)
        return width to height
    }
}
