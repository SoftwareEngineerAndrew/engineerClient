package com.engineerclient.terminals.sim

import com.engineerclient.terminals.TerminalTypes
import com.engineerclient.terminals.TerminalUtils
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import kotlin.random.Random

object RubixSim : TermSimGUI(TerminalTypes.RUBIX.termName, TerminalTypes.RUBIX.windowSize) {
    // A left click moves a pane one color along this cycle, a right click one back.
    private val cycle: Array<Item> = Items.STAINED_GLASS_PANE.let { arrayOf(it.orange(), it.yellow(), it.green(), it.blue(), it.red()) }
    private val grid = intArrayOf(12, 13, 14, 21, 22, 23, 30, 31, 32)

    override fun create() {
        setSlots { if (it.index in grid) unnamed(cycle[Random.nextInt(cycle.size)]) else blackPane }
    }

    override fun slotClick(slot: Slot, button: Int) {
        val at = cycle.indexOfFirst { slot.item.`is`(it) }
        if (at < 0) return
        val step = if (button == 1) cycle.size - 1 else 1
        slot.setSlot(unnamed(cycle[(at + step) % cycle.size]))
        val first = menu.slots[grid[0]].item.item
        if (grid.all { menu.slots[it].item.item === first }) TerminalUtils.lastTermOpened?.onComplete()
        super.slotClick(slot, button)
    }
}
