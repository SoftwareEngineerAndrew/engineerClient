package com.engineerclient.terminals.sim

import com.engineerclient.terminals.TerminalTypes
import com.engineerclient.terminals.TerminalUtils
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object NumbersSim : TermSimGUI(TerminalTypes.NUMBERS.termName, TerminalTypes.NUMBERS.windowSize) {
    private val waiting = Items.STAINED_GLASS_PANE.red()
    private val done = Items.STAINED_GLASS_PANE.lime()
    private var next = 1

    override fun create() {
        val numbers = (1..10).shuffled().iterator()
        next = 1
        setSlots {
            if (it.index / 9 !in 1..2 || it.index % 9 !in 2..6) blackPane
            else {
                val n = numbers.next()
                ItemStack(waiting, n).apply { set(DataComponents.CUSTOM_NAME, Component.literal("§a$n")) }
            }
        }
    }

    override fun slotClick(slot: Slot, button: Int) {
        val stack = slot.item
        if (!stack.`is`(waiting) || stack.count != next) return
        slot.setSlot(unnamed(done, stack.count))
        if (++next > 10) TerminalUtils.lastTermOpened?.onComplete()
        super.slotClick(slot, button)
    }
}
