package com.engineerclient.practice

import com.engineerclient.mixin.ContainerScreenAccessor
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.GuiEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.boss.TerminalSolver
import com.odtheking.odin.features.impl.boss.termsim.TermSimGUI
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalTypes
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils

/**
 * Extras for Odin's terminal simulator (/termsim, /termsim inf, the Infinileap loop). Only ever in
 * the simulator: real terminals are never touched.
 *
 * Hover Terms: the slot under the mouse is clicked as soon as it's one to click (a number resting
 * under the mouse goes in when its turn comes). Through Odin's own click, as a mouse click would
 * go: its solver, prediction and first click protection all apply. Not melody (its timing is the
 * point).
 */
object TermsimExtras : Module(
    name = "Termsim",
    category = Category.custom("Engineer Client"),
    description = "Extras for Odin's terminal simulator. Only in the simulator, never real terminals.",
) {
    private val hoverTerms by BooleanSetting("Hover Terms", false, desc = "Hovering the slot to click clicks it (numbers: the next number). Termsim only.")

    init {
        on<GuiEvent.Render> {
            if (!hoverTerms) return@on
            val screen = screen as? TermSimGUI ?: return@on
            val term = TerminalUtils.currentTerm ?: return@on
            if (term.type == TerminalTypes.MELODY) return@on
            // The slot under the mouse (the screen's own hoveredSlot is protected).
            val acc = screen as ContainerScreenAccessor
            val mx = mouseX - acc.betterpfLeftPos(); val my = mouseY - acc.betterpfTopPos()
            val slot = screen.menu.slots.firstOrNull { mx >= it.x - 1 && mx < it.x + 17 && my >= it.y - 1 && my < it.y + 17 }?.index ?: return@on
            if (slot in term.solution && term.canClick(slot, 0)) term.click(slot, 0, TerminalSolver.clickPrediction)
        }
    }
}
