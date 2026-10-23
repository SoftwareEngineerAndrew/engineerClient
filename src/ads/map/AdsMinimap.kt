package com.engineerclient.ads.map

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.ads.dungeon.AdsDungeon
import com.engineerclient.ads.hud.AdsHud
import com.engineerclient.ads.hud.AdsHuds
import com.engineerclient.ads.settings.AdsSettingsRegistry
import com.engineerclient.ads.settings.BooleanSetting
import com.engineerclient.ads.settings.NumberSetting
import com.engineerclient.ads.settings.SelectorSetting
import com.engineerclient.ads.settings.Settings
import com.engineerclient.ads.settings.SettingsPanel
import com.engineerclient.ads.settings.withDependency
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * ADS's minimap: the dungeon map as a HUD, built from Odin's map code ([DungeonScan] and the scans) so it runs
 * without Odin, on Hypixel and in ADS's own worlds. It draws in LCM's look or Odin's ([MinimapRenderer]), and with
 * Odin installed puts LCM Map Info's score under the map. Its "Minimap" panel in `/ads` turns it on (off to begin
 * with, so it doesn't double up with Odin's map) and holds its settings (config/engineerclient/minimap.json).
 */
object AdsMinimap : Settings("Minimap", "minimap.json") {
    enum class Look { LCM_STYLE, ODIN }
    enum class RoomText { NAME_AND_SECRETS, NAME, SECRETS }

    private val enabledS = +BooleanSetting("Minimap", false, "Shows the dungeon map in a HUD during the clear: rooms, doors, room names, check marks and where everyone is. Move it with Edit HUDs.")
    private val lookS = +SelectorSetting("Look", Look.LCM_STYLE, "LCM Style draws it like LCM's map (dtMap), in LCM Colors with Odin installed; Odin draws it like Odin's Dungeon Map.")
        .withDependency { enabledS.value }
    private val scoreS = +BooleanSetting("LCM Map Info", true, "Puts LCM Map Info's score under the map (secrets found, missing and total, the score, and what's missing from the bonus). Needs Odin, which counts the score.")
        .withDependency { enabledS.value }
    private val disableBossS = +BooleanSetting("Hide in Boss", true, "Hides the map once the boss fight starts.")
        .withDependency { enabledS.value }
    private val roomTextS = +SelectorSetting("Room Text", RoomText.NAME_AND_SECRETS, "What Odin's look writes on a room you've been in.")
        .withDependency { enabledS.value && lookS.value == Look.ODIN }
    private val textScaleS = +NumberSetting("Text Size", 0.45, 0.1..1.0, 0.05, desc = "How big room names are.")
        .withDependency { enabledS.value }
    private val guessesS = +BooleanSetting("Special Column Guesses", true, "Unopened 1x1 rooms show what they can still be (puzzle, trap or yellow), worked out from what's open.")
        .withDependency { enabledS.value }

    val look: Look get() = lookS.value
    val roomText: RoomText get() = roomTextS.value
    val textScale: Float get() = textScaleS.value.toFloat()
    val guesses: Boolean get() = guessesS.value

    init {
        AdsSettingsRegistry.register { listOf(SettingsPanel(this, "Minimap", all)) }
    }

    fun init() {
        load()
        AdsDungeon.init()
        DungeonScan.init()
        AdsHuds.register(AdsHud("minimap", "Minimap", enabledS.description, 10, 10, 1f, enabledS) { draw(this, it) })
    }

    private fun draw(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        if (!example && (!AdsDungeon.inDungeon || (disableBossS.value && AdsDungeon.inBoss))) return 0 to 0
        val (w, h) = if (lookS.value == Look.ODIN) MinimapRenderer.Odin.draw(g, example) else MinimapRenderer.Lcm.draw(g, example)
        if (!scoreS.value) return w to h
        val lines = when {
            example -> listOf("§b10§7-§e49§7-§c55     §a300", "§7D: §c1  §7M: §c✖  §7P: §c✖  §7B: §c✖  §c0§7/§a5")
            EngineerClient.odin -> OdinMinimap.scoreLines()
            else -> emptyList()
        }
        if (lines.isEmpty()) return w to h
        val lineH = mc.font.lineHeight + 1
        val width = maxOf(w, lines.maxOf { mc.font.width(it) })
        for ((i, line) in lines.withIndex()) g.centeredText(mc.font, line, w / 2, h + 2 + i * lineH, -1)
        return width to h + 2 + lines.size * lineH
    }
}
