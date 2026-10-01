package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.odtheking.odin.features.impl.boss.termsim.TermSimGUI
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalTypes
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Locale

/**
 * /termsim infi: a numbers terminal that never ends and has no numbers. Odin's solver shows the
 * next three cells (its order colours, no text); click the first and a new third is picked from
 * the cells not highlighted, so the path snakes around the grid. Escape to stop: chat gets the
 * average time between clicks, and what a numbers would take at that pace.
 *
 * It's titled as a numbers terminal so Odin's solver takes it; the order comes from [queue]
 * (see NumbersHandlerMixin), not from stack sizes.
 */
object InfiNumbersSim : TermSimGUI(TerminalTypes.NUMBERS.termName, TerminalTypes.NUMBERS.windowSize) {
    /** The 14 cells: rows 1-2, columns 1-7, as in a numbers. */
    private val CELLS = (1..2).flatMap { r -> (1..7).map { c -> r * 9 + c } }
    private const val SHOWN = 3

    private val rng = java.util.Random()
    /** The highlighted cells, next first. */
    @JvmStatic val queue = ArrayList<Int>()
    private var openedMs = 0L
    private var lastMs = 0L
    private var firstMs = 0L
    private val gaps = ArrayList<Long>()

    /** This simulator is on screen (Odin's numbers solver orders it by [queue], no numbers). */
    @JvmStatic
    fun active(): Boolean = EngineerClient.mc.screen === this

    override fun create() {
        queue.clear(); gaps.clear()
        while (queue.size < SHOWN) queue += pick()
        setSlots { if (it.index in CELLS) pane(it.index in queue) else blackPane }
        openedMs = System.currentTimeMillis(); lastMs = 0L; firstMs = 0L
    }

    /** A cell that isn't highlighted. */
    private fun pick(): Int = CELLS.filter { it !in queue }.let { it[rng.nextInt(it.size)] }

    private fun pane(lit: Boolean) =
        ItemStack(if (lit) Items.RED_STAINED_GLASS_PANE else Items.LIME_STAINED_GLASS_PANE).apply { set(DataComponents.CUSTOM_NAME, Component.literal("")) }

    override fun slotClick(slot: Slot, button: Int) {
        if (slot.index != queue.firstOrNull()) return
        val now = System.currentTimeMillis()
        if (lastMs == 0L) firstMs = now - openedMs else gaps += now - lastMs
        lastMs = now
        // The queue first, so the solver reads the new order on the slot updates below.
        queue.removeAt(0)
        val added = pick()
        queue += added
        slot.setSlot(pane(false))
        guiInventorySlots[added].setSlot(pane(true))
        super.slotClick(slot, button)
    }

    override fun removed() {
        super.removed()
        if (gaps.isEmpty()) return
        val avg = gaps.average() / 1000.0
        val first = firstMs / 1000.0
        EngineerClient.msg("§7Infi numbers: §f${gaps.size + 1}§7 clicks §8· §f${fmt(avg)}s§7 between §8· §7a numbers ≈ §f${fmt(first + 13 * avg)}s §8(first click ${fmt(first)} + 13 × ${fmt(avg)})")
        gaps.clear()
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.3f", v).let { if (v >= 1) String.format(Locale.ROOT, "%.2f", v) else it }
}
