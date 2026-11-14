package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.engineerclient.terminals.TerminalHandler.Companion.LEFT
import com.engineerclient.terminals.TerminalHandler.Companion.RIGHT
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.events.GuiEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.roundedRect
import com.odtheking.odin.utils.ui.widget.CustomGUIImpl
import net.minecraft.client.Options
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/** Draws the open terminal's solution and turns your clicks into the terminal's clicks. */
object TermSolver {
    private const val CELL = 24
    private var hovered = -1

    private val term get() = TerminalUtils.currentTerm?.takeIf { Terminals.solverOn && (it.type != TerminalTypes.MELODY || Terminals.melodySolver) }

    @JvmStatic val overridesGuiScale get() = Terminals.style == Terminals.Style.VANILLA && term != null
    @JvmStatic val termSize get() = if (Terminals.vanillaScale == 6) Options.AUTO_GUI_SCALE else Terminals.vanillaScale

    private fun color(kind: Int): Color? = when (kind) {
        TerminalHandler.PANE -> Terminals.panesColor
        TerminalHandler.SELECT -> Terminals.selectColor
        TerminalHandler.STARTS_WITH -> Terminals.startsWithColor
        TerminalHandler.RUBIX_1 -> Terminals.rubix1
        TerminalHandler.RUBIX_2 -> Terminals.rubix2
        TerminalHandler.RUBIX_M1 -> Terminals.rubixMinus1
        TerminalHandler.RUBIX_M2 -> Terminals.rubixMinus2
        TerminalHandler.NUMBER_1 -> Terminals.numbers1
        TerminalHandler.NUMBER_2 -> Terminals.numbers2
        TerminalHandler.NUMBER_3 -> Terminals.numbers3
        TerminalHandler.NUMBER_4 -> Terminals.numbers4
        TerminalHandler.MELODY_COLUMN -> Terminals.melodyColumn
        TerminalHandler.MELODY_POINTER -> Terminals.melodyPointer
        TerminalHandler.MELODY_LANE -> Terminals.melodyBackground
        else -> null
    }

    private fun textColor(t: TerminalHandler) = if (t.type == TerminalTypes.NUMBERS) Terminals.numbersText else Terminals.rubixText

    private class Grid(val x: Float, val y: Float, val scale: Float, val top: Int, val left: Int, val rows: Int, val cols: Int) {
        val step = CELL + Terminals.gap
        val w = cols * CELL + (cols - 1) * Terminals.gap
        val h = rows * CELL + (rows - 1) * Terminals.gap

        fun slotAt(mx: Double, my: Double): Int {
            val bx = (mx - x) / scale
            val by = (my - y) / scale
            if (bx < 0 || by < 0) return -1
            val c = (bx / step).toInt()
            val r = (by / step).toInt()
            if (c >= cols || r >= rows || bx - c * step >= CELL || by - r * step >= CELL) return -1
            return (top + r) * 9 + left + c
        }
    }

    private fun grid(screen: AbstractContainerScreen<*>, t: TerminalHandler): Grid {
        val melody = t.type == TerminalTypes.MELODY
        val scale = if (melody) Terminals.melodySize else Terminals.customSize
        val top = if (melody) 0 else t.top
        val left = if (melody) 1 else t.left
        val rows = if (melody) 5 else t.rows
        val cols = if (melody) 7 else t.cols
        val w = cols * CELL + (cols - 1) * Terminals.gap
        val h = rows * CELL + (rows - 1) * Terminals.gap
        return Grid((screen.width - w * scale) / 2f, (screen.height - h * scale) / 2f, scale, top, left, rows, cols)
    }

    private fun mouseButton(button: Int) = if (button == InputConstants.MOUSE_BUTTON_RIGHT) RIGHT else LEFT

    private fun customActive() = Terminals.style == Terminals.Style.CUSTOM && term != null && mc.gui.screen() is AbstractContainerScreen<*>

    fun install() {
        CustomGUIImpl.register(CustomGUIImpl.HandlerSet(::customActive,
            render = fun ScreenEvent.Render.(): Any {
                val t = term ?: return false
                val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return false
                drawCustom(guiGraphics, grid(screen, t), t, mouseX, mouseY)
                return true
            },
            click = fun ScreenEvent.MouseClick.(): Any {
                val t = term ?: return false
                val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return false
                val slot = grid(screen, t).slotAt(click.x(), click.y())
                if (slot >= 0) t.click(slot, mouseButton(click.button()))
                return true
            },
            key = fun ScreenEvent.KeyPress.(): Any {
                if (!mc.options.keyDrop.matches(input) && mc.options.keyHotbarSlots.none { it.matches(input) }) return false
                term?.takeIf { hovered >= 0 }?.click(hovered, LEFT)
                return true
            },
        ))

        on<GuiEvent.Render> {
            val t = term ?: return@on
            if (Terminals.style != Terminals.Style.VANILLA || !Terminals.hideItems) return@on
            val screen = screen as? AbstractContainerScreen<*> ?: return@on
            guiGraphics.fill(screen.leftPos + 7, screen.topPos + 16, screen.leftPos + screen.imageWidth - 7, screen.topPos + screen.imageHeight - 96, Terminals.backgroundColor.rgba)
        }

        on<GuiEvent.RenderSlot> {
            val t = term ?: return@on
            if (Terminals.style != Terminals.Style.VANILLA || slot.index >= t.area) return@on
            val look = t.look(slot.index)
            val c = look?.let { color(it.kind) }
            if (c != null) guiGraphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, c.rgba)
            look?.text?.let { guiGraphics.centeredText(screen.font, it, slot.x + 8, slot.y + 4, textColor(t).rgba) }
            if (Terminals.hideItems || look != null) cancel()
        }

        on<GuiEvent.SlotClick> {
            val t = term ?: return@on
            if (Terminals.style != Terminals.Style.VANILLA) return@on
            t.click(slotIndex, mouseButton(button))
            cancel()
        }

        on<GuiEvent.DrawTooltip> { if (term != null) cancel() }

        on<TerminalEvent.Open> { if (Terminals.style == Terminals.Style.VANILLA) mc.resizeGui() }
        on<TerminalEvent.Close> { if (Terminals.style == Terminals.Style.VANILLA) mc.resizeGui() }

        on<GuiEvent.Render> { hoverClick(screen, mouseX, mouseY) }

        EventBus.subscribe(this)
    }

    private fun drawCustom(g: GuiGraphicsExtractor, grid: Grid, t: TerminalHandler, mouseX: Int, mouseY: Int) {
        hovered = grid.slotAt(mouseX.toDouble(), mouseY.toDouble())
        val radius = Terminals.roundness
        g.pose().pushMatrix()
        g.pose().translate(grid.x, grid.y)
        g.pose().scale(grid.scale)
        g.roundedRect(-2, -2, grid.w + 2, grid.h + 2, Terminals.backgroundColor.rgba, radius)
        if (t.ready) for (r in 0 until grid.rows) for (c in 0 until grid.cols) {
            val slot = (grid.top + r) * 9 + grid.left + c
            val look = t.look(slot) ?: continue
            val x = c * grid.step
            val y = r * grid.step
            color(look.kind)?.let { g.roundedRect(x, y, x + CELL, y + CELL, it.rgba, radius) }
            look.text?.let { g.centeredText(mc.font, it, x + CELL / 2, y + (CELL - mc.font.lineHeight) / 2 + 1, textColor(t).rgba) }
        }
        g.pose().popMatrix()
        hoverClick(mc.gui.screen(), mouseX, mouseY)
    }

    /** Simulator's Hover Terms: the slot under the mouse is clicked as soon as it's the one to click. */
    private fun hoverClick(screen: net.minecraft.client.gui.screens.Screen?, mouseX: Int, mouseY: Int) {
        if (!Terminals.hoverTerms || screen !is com.engineerclient.terminals.sim.TermSimGUI) return
        val t = TerminalUtils.currentTerm ?: return
        if (t.type == TerminalTypes.MELODY) return
        val slot = if (customActive()) hovered else {
            val acc = screen as com.engineerclient.mixin.ContainerScreenAccessor
            val mx = mouseX - acc.adrLeftPos(); val my = mouseY - acc.adrTopPos()
            screen.menu.slots.firstOrNull { mx >= it.x - 1 && mx < it.x + 17 && my >= it.y - 1 && my < it.y + 17 }?.index ?: -1
        }
        if (slot >= 0 && t.canClick(slot, LEFT)) t.click(slot, LEFT)
    }
}
