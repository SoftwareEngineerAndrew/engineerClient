package com.engineerclient.terminals

import com.engineerclient.terminals.TerminalHandler.Companion.RIGHT
import com.mojang.blaze3d.platform.InputConstants
import com.adm.events.ScreenEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.on
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/** E as Right Click: in a terminal, E right clicks the slot under the mouse and does nothing else. */
object ERightClick {
    fun install() {
        on<ScreenEvent.KeyPress>(priority = Int.MAX_VALUE) {
            if (input.key() != InputConstants.KEY_E) return@on
            val t = TerminalUtils.currentTerm ?: return@on
            val screen = screen as? AbstractContainerScreen<*> ?: return@on
            val slot = TermSolver.slotUnderMouse(screen, t)
            if (slot >= 0) t.click(slot, RIGHT)
            cancel()
        }

        SubscribeWhile.add(this) { Terminals.enabled && Terminals.eRightClick }
    }
}
