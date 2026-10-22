// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.ads.splits

import com.engineerclient.EngineerClient.mc
import com.engineerclient.ads.hud.AdsHud
import com.engineerclient.ads.hud.AdsHuds
import com.engineerclient.ads.settings.AdsSettingsRegistry
import com.engineerclient.ads.settings.BooleanSetting
import com.engineerclient.ads.settings.SettingsPanel
import com.engineerclient.ads.settings.Settings
import com.engineerclient.ads.settings.withDependency
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.Locale

/**
 * ADS's Splits (Odin's Splits, for ADS's worlds): F7 and M7's splits from Mort to the end, timed from the chat lines
 * the Rustclear engine and the P3 Sim say ([AdsSplitsManager]), in two ADS HUDs (Splits, and the running split on
 * its own), with personal bests of its own ([AdsPersonalBests]). The "Splits" panel in `/ads` holds its settings
 * (config/engineerclient/splits-settings.json). It shows only in the sim and Rustclear worlds; with Odin installed,
 * Odin's own Splits steps aside there so the run is never timed twice.
 */
object AdsSplits : Settings("Splits", "splits-settings.json") {

    val displayHudS = +BooleanSetting("Splits HUD", true, "Shows the time of each split: in the P3 Sim and Rustclear worlds, from Mort's first line to the end.")
    val currentHudS = +BooleanSetting("Current Split HUD", false, "Shows only the current split and its tick time.")
    val fixedWidthS = +BooleanSetting("Fixed Width", true, "Always use a fixed HUD width, right-aligning the times.")
        .withDependency { displayHudS.value }
    val bossEntryS = +BooleanSetting("Boss Entry Split", true, "Split for boss entry: Blood Open, Blood Clear and Portal Entry together, once the boss starts.")
        .withDependency { displayHudS.value }
    val show0S = +BooleanSetting("Show 0 Splits", false, "Shows splits which have their time at 0.")
        .withDependency { displayHudS.value }
    val tickTimeS = +BooleanSetting("Show Tick Time", true, "Show tick-based time alongside real time.")
    val messagesS = +BooleanSetting("Split Messages", true, "Each split's time in chat as it ends (\"Maxor took 25.50s! (PB)\"), and the whole run at the end. PBs are ADS's own (config/engineerclient/splits.json), kept apart from Odin's; the sim's are kept on their own and only for splits played from their start.")

    val messages: Boolean get() = messagesS.value

    init {
        AdsSettingsRegistry.register { listOf(SettingsPanel(this, "Splits", all)) }
    }

    fun init() {
        load()
        AdsSplitsManager.init()
        AdsHuds.register(AdsHud("splits", "Splits", displayHudS.description, 10, 50, 1f, displayHudS) { drawDisplay(this, it) })
        AdsHuds.register(AdsHud("current_split", "Current Split", currentHudS.description, 240, 120, 1f, currentHudS) { drawCurrent(this, it) })
    }

    private fun width(s: String) = mc.font.width(s)
    private fun GuiGraphicsExtractor.line(s: String, x: Int, y: Int) = text(mc.font, s, x, y, -1, true)
    private fun fixed(n: Float) = String.format(Locale.US, "%.2f", n)

    private fun drawDisplay(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        val showTicks = tickTimeS.value
        val timeWidth = 75 + if (showTicks) 20 else -20
        val fixedWidth = fixedWidthS.value

        if (example) {
            val names = listOf(AdsSplitsManager.BLOOD_OPEN, AdsSplitsManager.BLOOD_CLEAR, AdsSplitsManager.PORTAL_ENTRY) + AdsSplitsManager.FLOOR7.map { it.name }
            val labelWidth = names.maxOf { width("$it:") }
            val timeExample = "59m 59s" + if (showTicks) " §8(§759.9§8)" else ""
            val totalWidth = labelWidth + 4 + timeWidth + 2
            names.forEachIndexed { i, name ->
                if (fixedWidth) {
                    g.line("$name:", 0, i * 9)
                    g.line(timeExample, totalWidth - width(timeExample) - 2, i * 9)
                } else g.line("$name: $timeExample", 0, i * 9)
            }
            return totalWidth to 9 * names.size
        }

        val rows = AdsSplitsManager.currentRows()
        if (rows.isEmpty()) return 0 to 0
        val segments = rows.dropLast(1)

        val labelWidth = segments.maxOfOrNull { width(it.name) } ?: 0
        val totalWidth = labelWidth + 4 + timeWidth + 2

        val displayRows = mutableListOf<AdsSplitRow>()
        segments.forEachIndexed { index, row ->
            displayRows.add(row)
            if (bossEntryS.value && index == 2 && rows.size > 3) {
                val bossTime = segments.take(3).sumOf { it.time }
                val bossTickTime = segments.take(3).sumOf { it.tickTime }
                displayRows.add(AdsSplitRow("§9Boss Entry", bossTime, bossTickTime, isCurrent = false))
            }
        }

        var y = 0
        displayRows.forEach { row ->
            if (row.time == 0L && !show0S.value) return@forEach
            val time = AdsSplitsManager.formatTime(row.time)
            g.line(row.name, 0, y)
            val displayText = if (showTicks) "$time §8(§7${fixed(row.tickTime / 20f)}§8)" else time
            val timeX = if (fixedWidth) labelWidth + 4 + timeWidth - width(displayText) else labelWidth + 4
            g.line(displayText, timeX, y)
            y += 9
        }
        return totalWidth to y
    }

    private fun drawCurrent(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        if (example) {
            val exampleText = "§70s"
            g.line(exampleText, 0, 0)
            return width(exampleText) to 9
        }
        val current = AdsSplitsManager.currentRows().find { it.isCurrent } ?: return 0 to 0
        val displayText = "§7${fixed(current.tickTime / 20f)}s"
        val w = width(displayText) + 2
        // Odin centres it on its place; drawn from the left here, so the editor's box is where it shows.
        g.line(displayText, 0, 0)
        return w to 9
    }
}
