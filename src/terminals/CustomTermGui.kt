// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.terminals

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Odin's Custom GUI render type: the terminal's puzzle slots alone, as coloured squares in the middle of the screen,
 * drawn instead of the vanilla window (TerminalSolverCustomGuiMixin) and clicked through the solver
 * (TerminalSolverScreenMixin). The drop key and the hotbar keys click the slot under the mouse too (Ctrl: a right
 * click). Odin's termGUI (TermGui, simpleTermGui, MelodyGui), with its rounded corners drawn as plain fills.
 */
object CustomTermGui {
    private val mc: Minecraft get() = Minecraft.getInstance()
    private val settings = TerminalSolverSettings

    private class SlotBox(val slotIndex: Int, val bx: Int, val by: Int, val size: Int, val look: () -> SlotLook?) {
        fun contains(x: Float, y: Float) = x >= bx && y >= by && x < bx + size && y < by + size
    }

    private class Grid(val originX: Float, val originY: Float, val w: Int, val h: Int, val scale: Float, val slots: List<SlotBox>) {
        fun toBase(screenX: Double, screenY: Double) = Pair((screenX.toFloat() - originX) / scale, (screenY.toFloat() - originY) / scale)
    }

    /** Where each terminal's puzzle sits in its window: rows, columns, first row, first column. */
    private data class Layout(val rows: Int, val cols: Int, val startRow: Int, val startCol: Int)

    private fun layout(type: TerminalType) = when (type) {
        TerminalType.PANES -> Layout(3, 5, 1, 2)
        TerminalType.RUBIX -> Layout(3, 3, 1, 3)
        TerminalType.NUMBERS -> Layout(2, 5, 1, 2)
        TerminalType.STARTS_WITH -> Layout(3, 7, 1, 1)
        TerminalType.SELECT -> Layout(4, 7, 1, 1)
        TerminalType.MELODY -> Layout(5, 7, 0, 1)
    }

    private var grid: Grid? = null
    private var hoveredSlotIndex: Int? = null

    private fun build(screen: AbstractContainerScreen<*>, term: TerminalHandler): Grid {
        val melody = term.type == TerminalType.MELODY
        val (rows, cols, startRow, startCol) = layout(term.type)
        val slotSize = 24
        val gap = settings.gap.value
        val scale = (if (melody) settings.melodyTermSize.value else settings.customTermSize.value).toFloat()
        val w = cols * slotSize + (cols - 1) * gap
        val h = rows * slotSize + (rows - 1) * gap
        val slots = buildList {
            for (row in 0 until rows) for (col in 0 until cols) {
                val index = (startRow + row) * 9 + (startCol + col)
                val look: () -> SlotLook? = if (!melody) {
                    { AdsTerminalSolver.currentTerm?.getSlotRendering(index) }
                } else {
                    // Melody: the lanes' empty slots in the background colour, the border rows and last column bare.
                    val r = index / 9
                    val isBorder = r == 0 || r == 4 || index % 9 == 6
                    { AdsTerminalSolver.currentTerm?.getSlotRendering(index) ?: if (!isBorder) SlotLook(settings.melodyBackgroundColor.value.argb, null) else null }
                }
                add(SlotBox(index, col * (slotSize + gap), row * (slotSize + gap), slotSize, look))
            }
        }
        return Grid((screen.width - w * scale) / 2f, (screen.height - h * scale) / 2f, w, h, scale, slots)
    }

    /** Draws the open terminal in place of [screen]. */
    @JvmStatic
    fun render(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val term = AdsTerminalSolver.currentTerm ?: return
        val grid = build(screen, term).also { grid = it }
        val padding = 2
        val radius = settings.roundness.value
        val (baseMX, baseMY) = grid.toBase(mouseX.toDouble(), mouseY.toDouble())
        hoveredSlotIndex = null

        g.pose().pushMatrix()
        g.pose().translate(grid.originX, grid.originY)
        g.pose().scale(grid.scale)
        roundedRect(g, -padding, -padding, grid.w + padding, grid.h + padding, settings.background, radius)
        val textColor = if (term.type == TerminalType.NUMBERS) settings.numbersText.value.argb else settings.rubixText.value.argb
        for (slot in grid.slots) {
            val look = slot.look() ?: continue
            if (slot.contains(baseMX, baseMY)) hoveredSlotIndex = slot.slotIndex
            roundedRect(g, slot.bx, slot.by, slot.bx + slot.size, slot.by + slot.size, look.color, radius)
            look.text?.let { g.text(mc.font, it, slot.bx + (slot.size - mc.font.width(it)) / 2, slot.by + (slot.size - mc.font.lineHeight) / 2 + 1, textColor) }
        }
        g.pose().popMatrix()
    }

    /** A mouse click on the custom GUI; always taken (true), so the window under it never gets it. */
    @JvmStatic
    fun mouseClicked(event: MouseButtonEvent): Boolean {
        val g = grid ?: return true
        val (bx, by) = g.toBase(event.x(), event.y())
        hoveredSlotIndex = g.slots.firstOrNull { it.contains(bx, by) }?.slotIndex
        hoveredSlotIndex?.let { AdsTerminalSolver.currentTerm?.clickMouse(it, event.button(), settings.clickPrediction.value) }
        return true
    }

    /** The drop key or a hotbar key clicks the slot under the mouse (true: taken); any other key goes on as usual. */
    @JvmStatic
    fun keyPressed(event: KeyEvent): Boolean {
        if (!mc.options.keyDrop.matches(event) && mc.options.keyHotbarSlots.none { it.matches(event) }) return false
        hoveredSlotIndex?.let {
            AdsTerminalSolver.currentTerm?.click(it, if (!event.hasControlDown()) TerminalHandler.LEFT_CLICK else TerminalHandler.RIGHT_CLICK, settings.clickPrediction.value)
        }
        return true
    }

    /** A filled rectangle with corners of radius [r] (Odin's are drawn by a shader; here row by row). */
    private fun roundedRect(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, r: Int) {
        val radius = min(r, min((x1 - x0) / 2, (y1 - y0) / 2))
        if (radius <= 0) return g.fill(x0, y0, x1, y1, color)
        for (i in 0 until radius) {
            val dy = radius - i - 0.5
            val inset = (radius - sqrt(radius * radius - dy * dy)).toInt()
            g.fill(x0 + inset, y0 + i, x1 - inset, y0 + i + 1, color)
            g.fill(x0 + inset, y1 - i - 1, x1 - inset, y1 - i, color)
        }
        g.fill(x0, y0 + radius, x1, y1 - radius, color)
    }
}
