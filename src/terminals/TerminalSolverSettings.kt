// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.terminals

import com.engineerclient.ads.settings.AdsSettingsRegistry
import com.engineerclient.ads.settings.BooleanSetting
import com.engineerclient.ads.settings.NumberSetting
import com.engineerclient.ads.settings.SelectorSetting
import com.engineerclient.ads.settings.Settings
import com.engineerclient.ads.settings.SettingsPanel
import com.engineerclient.ads.settings.withDependency

/**
 * The terminal solver's settings (`/ads`, config/engineerclient/terminals.json): Odin's Terminal Solver options,
 * names and defaults kept. ADS's settings have no colour picker, so each colour is picked from [TermColor], Odin's
 * defaults among them; the background's see-through is its own slider.
 */
object TerminalSolverSettings : Settings("Terminal Solver", "terminals.json") {

    enum class RenderType { ODIN, NORMAL, CUSTOM_GUI }
    enum class RubixMode { FEWEST_CLICKS, LEFT_CLICKS_ONLY }

    /** Odin's default colours (its Minecraft greens and reds, the darker ones at half brightness) and a few more. */
    enum class TermColor(val rgb: Int) {
        GREEN(0x55FF55), GREEN_HALF(0x2A802A), GREEN_QUARTER(0x154015), GREEN_EIGHTH(0x0B200B),
        DARK_GREEN(0x00AA00), AQUA(0x55FFFF), BLUE(0x5555FF), DARK_PURPLE(0xAA00AA), LIGHT_PURPLE(0xFF55FF),
        RED(0xFF5555), DARK_RED(0xAA0000), DARK_RED_HALF(0x550000), GOLD(0xFFAA00), YELLOW(0xFFFF55),
        WHITE(0xFFFFFF), GRAY(0xAAAAAA), DARK_GRAY(0x555555), GRAY_38(0x262626), GRAY_26(0x1A1A1A), BLACK(0x000000);

        val argb: Int get() = rgb or (0xFF shl 24)
    }

    val enabled = +BooleanSetting("Enabled", true, desc = "Solves terminals in ADS's worlds (the P3 Sim and Rustclear's). On Hypixel Odin's own solver, if installed, is untouched.")
    val renderType = +SelectorSetting("Render type", RenderType.ODIN, desc = "How the solver draws: Odin (only the slots to click), Normal (highlights over the vanilla window) or Custom GUI (its own window).")
    val normalTermSize = +NumberSetting("Normal Term Size", 3, 1..6, desc = "The GUI scale while a terminal is open (Odin and Normal); 6 is auto.").withDependency { renderType.value != RenderType.CUSTOM_GUI }
    val customTermSize = +NumberSetting("Term Size", 2.0, 1.0..3.0, 0.1, desc = "The size of the custom terminal GUI.").withDependency { renderType.value == RenderType.CUSTOM_GUI }
    val roundness = +NumberSetting("Roundness", 5, 0..15, desc = "The roundness of the custom terminal GUI.").withDependency { renderType.value == RenderType.CUSTOM_GUI }
    val gap = +NumberSetting("Slot gap", 2, 0..8, desc = "The gap between the slots in the custom terminal GUI.").withDependency { renderType.value == RenderType.CUSTOM_GUI }

    val clickPrediction = +BooleanSetting("Client Prediction", true, desc = "Hides a clicked slot at once, before the server's update comes.")
    val terminalReloadThreshold = +NumberSetting("Resolve timeout", 600, 300..1200, 10, unit = "ms", desc = "How long a predicted click waits for the server before the terminal is solved again from what it shows.").withDependency { clickPrediction.value }
    val showNumbers = +BooleanSetting("Show Numbers", true, desc = "Click in order: the 1-10 on the slots.")
    val cancelMelodySolver = +BooleanSetting("Stop Melody Solver", false, desc = "Stops drawing the melody solver.")
    val melodyTermSize = +NumberSetting("Melody Size", 1.5, 1.0..3.0, 0.1, desc = "The size of the melody terminal GUI.").withDependency { !cancelMelodySolver.value && renderType.value == RenderType.CUSTOM_GUI }
    val rubixMode = +SelectorSetting("Rubix Mode", RubixMode.FEWEST_CLICKS, desc = "Mix in right clicks for the fewest clicks overall, or stick to left clicks only.")
    val firstClickProt = +NumberSetting("First Click Prot", 200, 0..600, 10, unit = "ms", desc = "Clicks this soon after a terminal opens are blocked (Hypixel bans the fast ones; recommended 500 minus your ping).")

    val backgroundColor = +SelectorSetting("Background", TermColor.GRAY_26, desc = "Behind the terminal (Odin render type and custom GUI).")
    val backgroundOpacity = +NumberSetting("Background Opacity", 50, 0..100, 5, unit = "%", desc = "How solid the background is.")
    val panesColor = +SelectorSetting("Panes", TermColor.GREEN, desc = "Correct all the panes.")
    val rubixText = +SelectorSetting("Rubix Text", TermColor.WHITE, desc = "The click counts on Change all to same color.")
    val rubixColor1 = +SelectorSetting("Rubix 1", TermColor.GREEN, desc = "A pane one left click away.")
    val rubixColor2 = +SelectorSetting("Rubix 2", TermColor.GREEN_HALF, desc = "A pane two left clicks away.")
    val oppositeRubixColor1 = +SelectorSetting("Rubix -1", TermColor.DARK_RED, desc = "A pane one right click away.")
    val oppositeRubixColor2 = +SelectorSetting("Rubix -2", TermColor.DARK_RED_HALF, desc = "A pane two right clicks away.")
    val numbersText = +SelectorSetting("Numbers Text", TermColor.WHITE, desc = "The numbers on Click in order.")
    val numbers1Color = +SelectorSetting("Numbers 1", TermColor.GREEN, desc = "Click in order: the next pane.")
    val numbers2Color = +SelectorSetting("Numbers 2", TermColor.GREEN_HALF, desc = "Click in order: the one after.")
    val numbers3Color = +SelectorSetting("Numbers 3", TermColor.GREEN_QUARTER, desc = "Click in order: the third.")
    val numbers4Color = +SelectorSetting("Numbers 4", TermColor.GREEN_EIGHTH, desc = "Click in order: the fourth.")
    val startsWithColor = +SelectorSetting("Starts With", TermColor.GREEN, desc = "What starts with.")
    val selectColor = +SelectorSetting("Select", TermColor.GREEN, desc = "Select all the items.")
    val melodyColumColor = +SelectorSetting("Melody Column", TermColor.DARK_PURPLE, desc = "Melody: the column to hit.").withDependency { !cancelMelodySolver.value }
    val melodyPointerColor = +SelectorSetting("Melody Pointer", TermColor.GREEN, desc = "Melody: where to press.").withDependency { !cancelMelodySolver.value }
    val melodyBackgroundColor = +SelectorSetting("Melody Background", TermColor.GRAY_38, desc = "Melody: the empty slots (custom GUI).").withDependency { !cancelMelodySolver.value && renderType.value == RenderType.CUSTOM_GUI }

    /** The background colour with its opacity. */
    val background: Int get() = backgroundColor.value.rgb or ((backgroundOpacity.value * 255 / 100) shl 24)

    fun init() {
        load()
        AdsSettingsRegistry.register {
            listOf(
                SettingsPanel(this, "Terminals", listOf(
                    enabled, renderType, normalTermSize, customTermSize, roundness, gap, clickPrediction, terminalReloadThreshold,
                    showNumbers, cancelMelodySolver, melodyTermSize, rubixMode, firstClickProt,
                )),
                SettingsPanel(this, "Terminal Colors", listOf(
                    backgroundColor, backgroundOpacity, panesColor, rubixText, rubixColor1, rubixColor2, oppositeRubixColor1,
                    oppositeRubixColor2, numbersText, numbers1Color, numbers2Color, numbers3Color, numbers4Color, startsWithColor,
                    selectColor, melodyColumColor, melodyPointerColor, melodyBackgroundColor,
                )),
            )
        }
    }
}
