package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.adm.utils.Color
import com.adm.utils.Color.Companion.withAlpha
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import kotlin.math.floor
import kotlin.math.min

/** The Noamm and Soshimee solver looks: both on the terminal's own grid (Numbers 2x5), drawn from each handler's looks. */
internal object TermStyles {
    private const val SLOT = 16f

    private fun name(type: TerminalTypes) = when (type) {
        TerminalTypes.PANES -> "Red Green"
        TerminalTypes.RUBIX -> "Rubix"
        TerminalTypes.NUMBERS -> "Numbers"
        TerminalTypes.STARTS_WITH -> "Starts With"
        TerminalTypes.SELECT -> "Colors"
        TerminalTypes.MELODY -> "Melody"
    }

    /** Top row, left column, rows, columns; melody is its three lanes and the button column. */
    private fun bounds(t: TerminalHandler) = if (t.type == TerminalTypes.MELODY) intArrayOf(1, 1, 3, 7) else intArrayOf(t.top, t.left, t.rows, t.cols)

    private class Grid(val x: Float, val y: Float, val scale: Float, val pitch: Float, val b: IntArray) {
        val w get() = b[3] * pitch - (pitch - SLOT)
        val h get() = b[2] * pitch - (pitch - SLOT)
        fun slotX(slot: Int) = (slot % 9 - b[1]) * pitch
        fun slotY(slot: Int) = (slot / 9 - b[0]) * pitch

        /** Noamm counts the gap after a slot as part of it. */
        fun slotAt(mx: Double, my: Double): Int {
            val bx = (mx - x) / scale
            val by = (my - y) / scale
            if (bx < 0 || by < 0) return -1
            val c = floor(bx / pitch).toInt()
            val r = floor(by / pitch).toInt()
            if (c >= b[3] || r >= b[2]) return -1
            return (b[0] + r) * 9 + b[1] + c
        }
    }

    private fun grid(screen: Screen, t: TerminalHandler): Grid {
        val b = bounds(t)
        return if (Terminals.style == Terminals.Style.NOAMM) {
            // Noamm lays out on a 960x540 reference screen at 3x.
            val scale = min(screen.width / 960f, screen.height / 540f) * 3f * Terminals.noammScale
            val pitch = SLOT + Terminals.noammGap
            val w = b[3] * pitch - Terminals.noammGap
            val h = b[2] * pitch - Terminals.noammGap
            Grid((screen.width - w * scale) / 2f, (screen.height - h * scale) / 2f, scale, pitch, b)
        } else {
            val scale = Terminals.soshimeeScale
            val w = b[3] * 18f - 2f
            val h = b[2] * 18f - 2f
            Grid((screen.width - w * scale) / 2f, (screen.height - h * scale) / 2f, scale, 18f, b)
        }
    }

    fun slotAt(screen: Screen, t: TerminalHandler, mx: Double, my: Double) = grid(screen, t).slotAt(mx, my)

    /** Draws [t] and returns the slot under the mouse. */
    fun draw(g: GuiGraphicsExtractor, screen: Screen, t: TerminalHandler, mouseX: Int, mouseY: Int): Int {
        val grid = grid(screen, t)
        val noamm = Terminals.style == Terminals.Style.NOAMM
        g.pose().pushMatrix()
        g.pose().translate(grid.x, grid.y)
        g.pose().scale(grid.scale)
        if (noamm) {
            val pad = Terminals.noammPadding
            if (Terminals.noammTitle.alpha > 0) centered(g, name(t.type), grid.w / 2f, -15f - pad, Terminals.noammTitle.rgba, 1.2f)
            rect(g, -pad, -pad, grid.w + pad * 2, grid.h + pad * 2, Terminals.backgroundColor.rgba)
            if (Terminals.noammBorder.alpha > 0) border(g, -pad, -pad, grid.w + pad * 2, grid.h + pad * 2, Terminals.noammBorder.rgba)
        } else {
            rect(g, -2f, -2f, grid.w + 4f, grid.h + 4f, Terminals.backgroundColor.rgba)
            if (Terminals.soshimeeTitle) g.text(mc.font, "§8[§bADM Terminal§8] §a${name(t.type)}", 0, -mc.font.lineHeight - 3, -1, true)
        }
        if (t.ready || t.early) {
            if (t is MelodyHandler) melody(g, grid, t, noamm)
            else for (r in 0 until grid.b[2]) for (c in 0 until grid.b[3]) {
                val slot = (grid.b[0] + r) * 9 + grid.b[1] + c
                val look = t.look(slot) ?: continue
                val x = grid.slotX(slot); val y = grid.slotY(slot)
                TermSolver.color(look.kind)?.let { slot(g, x, y, SLOT, SLOT, it, noamm) }
                look.text?.let { centered(g, it, x + 8f, y + 8f - mc.font.lineHeight / 2f + if (noamm) 0f else 0.5f, TermSolver.textColor(t).rgba) }
            }
        }
        g.pose().popMatrix()
        return grid.slotAt(mouseX.toDouble(), mouseY.toDouble())
    }

    /** The answer column down all three lanes, the moving pane, and every button: this lane's green, the rest wrong. */
    private fun melody(g: GuiGraphicsExtractor, grid: Grid, t: MelodyHandler, noamm: Boolean) {
        if (t.column >= 0) slot(g, grid.slotX(9 + t.column % 9), 0f, SLOT, grid.h, Terminals.melodyColumn, noamm)
        if (t.pointer >= 0) slot(g, grid.slotX(t.pointer), grid.slotY(t.pointer), SLOT, SLOT, Terminals.melodyPointer, noamm)
        val lane = t.pointer / 9
        for (b in MelodyHandler.BUTTONS) {
            val on = b == t.button || t.button < 0 && b / 9 == lane
            slot(g, grid.slotX(b), grid.slotY(b), SLOT, SLOT, if (on) Terminals.melodyPointer else Terminals.melodyWrong, noamm)
        }
    }

    private fun slot(g: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, color: Color, noamm: Boolean) {
        if (!noamm) return rect(g, x, y, w, h, color.rgba)
        when (Terminals.noammSlots) {
            Terminals.NoammSlot.RECT -> rect(g, x, y, w, h, color.rgba)
            Terminals.NoammSlot.BORDERED -> {
                rect(g, x, y, w, h, color.withAlpha(40 / 255f).rgba)
                border(g, x, y, w, h, color.rgba)
            }
            Terminals.NoammSlot.BUTTON -> floating(g, x.toInt(), y.toInt(), w.toInt(), h.toInt(), darker(color.rgba))
        }
    }

    private fun rect(g: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, rgba: Int) {
        g.pose().pushMatrix()
        g.pose().translate(x, y)
        g.pose().scale(w, h)
        g.fill(0, 0, 1, 1, rgba)
        g.pose().popMatrix()
    }

    private fun border(g: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, rgba: Int) {
        rect(g, x, y, w, 1f, rgba)
        rect(g, x, y + h - 1f, w, 1f, rgba)
        rect(g, x, y + 1f, 1f, h - 2f, rgba)
        rect(g, x + w - 1f, y + 1f, 1f, h - 2f, rgba)
    }

    private fun floating(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, base: Int) {
        val light = brighter(base); val dark = darker(base)
        g.fill(x, y, x + 1, y + h, light)
        g.fill(x + 1, y, x + w, y + 1, light)
        g.fill(x + w - 1, y + 1, x + w, y + h, dark)
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, dark)
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, base)
    }

    private fun centered(g: GuiGraphicsExtractor, s: String, x: Float, y: Float, rgba: Int, scale: Float = 1f) {
        g.pose().pushMatrix()
        g.pose().translate(x - mc.font.width(s) * scale / 2f, y)
        if (scale != 1f) g.pose().scale(scale)
        g.text(mc.font, s, 0, 0, rgba, true)
        g.pose().popMatrix()
    }

    // java.awt.Color's darker and brighter, alpha kept.
    private fun darker(c: Int): Int {
        fun ch(v: Int) = (v * 0.7).toInt()
        return (c and 0xFF000000.toInt()) or (ch(c shr 16 and 255) shl 16) or (ch(c shr 8 and 255) shl 8) or ch(c and 255)
    }

    private fun brighter(c: Int): Int {
        var r = c shr 16 and 255; var gr = c shr 8 and 255; var b = c and 255
        val i = 3
        if (r == 0 && gr == 0 && b == 0) return (c and 0xFF000000.toInt()) or (i shl 16) or (i shl 8) or i
        if (r in 1 until i) r = i; if (gr in 1 until i) gr = i; if (b in 1 until i) b = i
        fun ch(v: Int) = min((v / 0.7).toInt(), 255)
        return (c and 0xFF000000.toInt()) or (ch(r) shl 16) or (ch(gr) shl 8) or ch(b)
    }
}
