package com.engineerclient.terminals.sim

import com.engineerclient.terminals.TerminalTypes
import com.engineerclient.terminals.TerminalUtils
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.random.Random

/** Rows 1-3 are lanes (columns 1-5) with a button in column 7; the column to hit is marked in rows 0 and 4. */
object MelodySim : TermSimGUI(TerminalTypes.MELODY.termName, TerminalTypes.MELODY.windowSize) {
    private const val BUTTON_COLUMN = 7
    private const val LANES = 3
    private const val TICKS_PER_STEP = 10

    private val marker = unnamed(Items.STAINED_GLASS_PANE.magenta())
    private val pointer = unnamed(Items.STAINED_GLASS_PANE.lime())
    private val activeLane = unnamed(Items.STAINED_GLASS_PANE.red())
    private val idleLane = unnamed(Items.STAINED_GLASS_PANE.white())
    private val activeButton = unnamed(Items.DYED_TERRACOTTA.lime())
    private val idleButton = unnamed(Items.DYED_TERRACOTTA.red())

    private var target = 1
    private var lane = 1
    private var at = 1
    private var heading = 1
    private var ticks = 0

    private fun newTarget() = Random.nextInt(1, 6)

    override fun create() {
        target = newTarget()
        lane = 1
        at = 1
        heading = 1
        setSlots { look(it.index) }
    }

    override fun containerTick() {
        super.containerTick()
        if (ticks++ % TICKS_PER_STEP != 0) return
        at += heading
        if (at == 1 || at == 5) heading = -heading
        redraw(lane)
    }

    override fun slotClick(slot: Slot, button: Int) {
        val i = slot.index
        if (i % 9 != BUTTON_COLUMN || i / 9 != lane || at != target) return
        target = newTarget()
        lane++
        redraw(0)
        redraw(LANES + 1)
        redraw(lane - 1)
        if (lane <= LANES) redraw(lane)
        else TerminalUtils.lastTermOpened?.onComplete()
        super.slotClick(slot, button)
    }

    private fun redraw(row: Int) {
        for (i in row * 9 until row * 9 + 9) menu.slots[i].setSlot(look(i))
    }

    private fun look(i: Int): ItemStack {
        val row = i / 9
        val col = i % 9
        val inLane = col in 1..5
        return when {
            row == 0 || row == LANES + 1 -> if (col == target) marker else blackPane
            row !in 1..LANES -> blackPane
            col == BUTTON_COLUMN -> if (row == lane) activeButton else idleButton
            !inLane -> blackPane
            row != lane -> idleLane
            col == at -> pointer
            else -> activeLane
        }
    }
}
