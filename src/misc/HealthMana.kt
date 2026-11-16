package com.engineerclient.misc

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.clickgui.settings.impl.HUDSetting
import com.adm.clickgui.settings.impl.HudElement
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.features.impl.skyblock.PlayerDisplay
import com.adm.utils.Color
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.Colors
import com.adm.utils.skyblock.ActionBarListener
import com.adm.utils.skyblock.LocationUtils
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Health and mana: bar versions of ADM's Health HUD and Mana HUD, in the colours set in ADM's
 * Player Display, and an option to hide ADM's text HUDs and these bars until the stat runs low.
 * Each bar is its own HUD so it can be placed and toggled on its own; unlike the text, a bar stays
 * up at 0 as an empty bar.
 */
object HealthMana {
    private val dropdown = DropdownSetting("Health & Mana Bars", desc = "Shows settings for the health and mana bars.")
    private val hideUnlessLowSetting = BooleanSetting("Hide Health/Mana Above %", false, desc = "Hides the health and mana HUDs and bars until the stat drops below the threshold.").withDependency { dropdown.value }
    private val thresholdSetting = NumberSetting("Threshold", 50, 1..100, 1, desc = "Shown once the stat drops below this percent of max.", unit = "%").withDependency { dropdown.value && hideUnlessLowSetting.value }
    private val hideUnlessLow get() = hideUnlessLowSetting.value
    private val threshold get() = thresholdSetting.value

    private val healthBar = PlayerDisplay.HUD("Health Bar HUD", "Your health as a filled bar, in ADM's Player Display Health Color.", true, 434, 501, 1.6f) { example ->
        val (current, max) = when {
            example -> 3000 to 4000
            !LocationUtils.isInSkyblock || ActionBarListener.maxHealth == 0 -> return@HUD 0 to 0
            aboveThreshold(ActionBarListener.currentHealth, ActionBarListener.maxHealth) -> return@HUD 0 to 0
            else -> ActionBarListener.currentHealth to ActionBarListener.maxHealth
        }
        statBar(current, max, playerDisplayColor("Health Color", Colors.MINECRAFT_RED), healthBarWidth.value, healthBarHeight.value)
    }.withDependency { dropdown.value }
    private val healthBarWidth = NumberSetting("Health Bar Width", 60, 20..200, 5, desc = "Width of the health bar.").withDependency { dropdown.value }
    private val healthBarHeight = NumberSetting("Health Bar Height", 8, 2..30, 1, desc = "Height of the health bar.").withDependency { dropdown.value }

    private val manaBar = PlayerDisplay.HUD("Mana Bar HUD", "Your mana as a filled bar, in ADM's Player Display Mana Color.", true, 434, 480, 1.6f) { example ->
        val (current, max) = when {
            example -> 2000 to 20000
            !LocationUtils.isInSkyblock || ActionBarListener.maxMana == 0 -> return@HUD 0 to 0
            aboveThreshold(ActionBarListener.currentMana, ActionBarListener.maxMana) -> return@HUD 0 to 0
            else -> ActionBarListener.currentMana to ActionBarListener.maxMana
        }
        statBar(current, max, playerDisplayColor("Mana Color", Colors.MINECRAFT_AQUA), manaBarWidth.value, manaBarHeight.value)
    }.withDependency { dropdown.value }
    private val manaBarWidth = NumberSetting("Mana Bar Width", 60, 20..200, 5, desc = "Width of the mana bar.").withDependency { dropdown.value }
    private val manaBarHeight = NumberSetting("Mana Bar Height", 8, 2..30, 1, desc = "Height of the mana bar.").withDependency { dropdown.value }

    /** Adds the bars and the low-stat hiding to ADM's Player Display. */
    fun install() {
        healthBar.value.enabled = false; manaBar.value.enabled = false
        for (s in listOf(dropdown, hideUnlessLowSetting, thresholdSetting, healthBar, healthBarWidth, healthBarHeight, manaBar, manaBarWidth, manaBarHeight)) PlayerDisplay.registerSetting(s)
    }

    /**
     * Whether ADM's own Health HUD or Mana HUD should be skipped this frame: Hide Health/Mana Above
     * % is on and the stat is above the threshold. Read by CoreHuds for every ADM HUD element; only
     * those two are ever skipped.
     */
    fun hidesCoreHud(hud: HudElement): Boolean {
        if (!PlayerDisplay.enabled || !hideUnlessLow) return false
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
