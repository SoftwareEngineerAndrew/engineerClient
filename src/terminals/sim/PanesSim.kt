package com.engineerclient.terminals.sim

import com.engineerclient.terminals.TerminalTypes
import com.engineerclient.terminals.TerminalUtils
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Items

object PanesSim : TermSimGUI(TerminalTypes.PANES.termName, TerminalTypes.PANES.windowSize) {
    private val off = Items.STAINED_GLASS_PANE.red()
    private val on = Items.STAINED_GLASS_PANE.lime()

    private fun inGrid(i: Int) = i / 9 in 1..3 && i % 9 in 2..6

    override fun create() {
        setSlots { if (!inGrid(it.index)) blackPane else unnamed(if (Math.random() > 0.75) on else off) }
    }

    override fun slotClick(slot: Slot, button: Int) {
        slot.setSlot(unnamed(if (slot.item.`is`(off)) on else off))
        if (guiInventorySlots.none { it.item.`is`(off) }) TerminalUtils.lastTermOpened?.onComplete()
        super.slotClick(slot, button)
    }
}
