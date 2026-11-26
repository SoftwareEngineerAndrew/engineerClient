package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.engineerclient.terminals.TerminalHandler.Companion.LEFT
import com.engineerclient.terminals.TerminalHandler.Companion.RIGHT
import com.mojang.blaze3d.platform.InputConstants
import com.adm.events.GuiEvent
import com.adm.events.ScreenEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.on
import com.adm.utils.Color
import com.adm.utils.render.roundedRect
import com.adm.utils.ui.widget.CustomGUIImpl
import net.minecraft.client.Options
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.player.Inventory

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

    /** Ctrl and the drop key held: a vanilla-style slot click that is Ctrl + Q. */
    private fun ctrlDrop(): Boolean {
        val drop = InputConstants.getKey(mc.options.keyDrop.saveString())
        return drop.type == InputConstants.Type.KEYBOARD && InputConstants.isKeyDown(drop.value) &&
            (InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL))
    }

    private fun customActive() = Terminals.style == Terminals.Style.CUSTOM && term != null && mc.gui.screen() is AbstractContainerScreen<*>

    /** The terminal slot under the mouse, in the custom grid or the window; -1 for none. */
    fun slotUnderMouse(screen: AbstractContainerScreen<*>, t: TerminalHandler): Int =
        if (customActive()) hovered else screen.hoveredSlot?.takeIf { it.container !is Inventory }?.index?.takeIf { it < t.area } ?: -1

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
                // Ctrl + drop is a right click, as Ctrl + Q throws the whole stack.
                term?.takeIf { hovered >= 0 }?.click(hovered, if (mc.options.keyDrop.matches(input) && input.hasControlDownWithQuirk()) RIGHT else LEFT)
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
            t.click(slotIndex, if (ctrlDrop()) RIGHT else mouseButton(button))
            cancel()
        }

        on<GuiEvent.DrawTooltip> { if (term != null) cancel() }

        on<TerminalEvent.Open> { if (Terminals.style == Terminals.Style.VANILLA) mc.resizeGui() }
        on<TerminalEvent.Close> { if (Terminals.style == Terminals.Style.VANILLA) mc.resizeGui() }

        on<GuiEvent.Render> { hoverClick(screen, mouseX, mouseY) }

        SubscribeWhile.add(this) { Terminals.solverOn || Terminals.hoverTerms }
    }

    private fun drawCustom(g: GuiGraphicsExtractor, grid: Grid, t: TerminalHandler, mouseX: Int, mouseY: Int) {
        hovered = grid.slotAt(mouseX.toDouble(), mouseY.toDouble())
        val radius = Terminals.roundness
        g.pose().pushMatrix()
        g.pose().translate(grid.x, grid.y)
        g.pose().scale(grid.scale)
        g.roundedRect(-2, -2, grid.w + 2, grid.h + 2, Terminals.backgroundColor.rgba, radius)
        // Melody: the lanes and the buttons as two plain blocks, with only the column marks, the moving pane and a lined up button on them.
        if (t.type == TerminalTypes.MELODY) {
            val lanes = (if ((t as? MelodyHandler)?.firstClick() == true) Terminals.melodyFirstBackground else Terminals.melodyBackground).rgba
            g.roundedRect(0, grid.step, 5 * grid.step - Terminals.gap, 4 * grid.step - Terminals.gap, lanes, radius)
            g.roundedRect(6 * grid.step, grid.step, 6 * grid.step + CELL, 4 * grid.step - Terminals.gap, lanes, radius)
        }
        if (t.ready || t.early) for (r in 0 until grid.rows) for (c in 0 until grid.cols) {
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

    private val CHEST = net.minecraft.resources.Identifier.withDefaultNamespace("textures/gui/container/generic_54.png")

    /** Open GUI Instantly's screen before Hypixel's window is in: [type]'s empty grid, or its empty chest. */
    fun drawBlank(g: GuiGraphicsExtractor, width: Int, height: Int, type: TerminalTypes) {
        if (Terminals.solverOn && Terminals.style == Terminals.Style.CUSTOM) {
            val melody = type == TerminalTypes.MELODY
            val rows = if (melody) 5 else type.layout[2]
            val cols = if (melody) 7 else type.layout[3]
            val w = cols * CELL + (cols - 1) * Terminals.gap
            val h = rows * CELL + (rows - 1) * Terminals.gap
            val scale = if (melody) Terminals.melodySize else Terminals.customSize
            g.pose().pushMatrix()
            g.pose().translate((width - w * scale) / 2f, (height - h * scale) / 2f)
            g.pose().scale(scale)
            g.roundedRect(-2, -2, w + 2, h + 2, Terminals.backgroundColor.rgba, Terminals.roundness)
            g.pose().popMatrix()
            return
        }
        val top = type.windowSize / 9 * 18 + 17
        val x = (width - 176) / 2
        val y = (height - top - 96) / 2
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, CHEST, x, y, 0f, 0f, 176, top, 256, 256)
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, CHEST, x, y + top, 0f, 126f, 176, 96, 256, 256)
    }

}
