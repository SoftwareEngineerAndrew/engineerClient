// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.ads.hud

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.rustclear.mod.ui.RustclearScreen
import kotlin.math.sign

/**
 * The HUD editor (Odin's HUD Manager): every ADS HUD that's on, drawn as its example in a white box. Drag one to move
 * it, scroll over it to resize it; over one, + and - resize it and the arrow keys nudge it, a right click puts it
 * back where it started. Reset All puts every one back. Hovering one shows its name and what it does beside it. The
 * places are saved as it closes; it goes back to [parent] (the `/ads` screen, if it was opened from there).
 */
class AdsHudEditor(private val parent: Screen? = null) : Screen(Component.literal("ADS HUD editor")) {

    private var dragging: AdsHud? = null
    private var grabX = 0
    private var grabY = 0
    private val settled = HashSet<AdsHud>()

    private fun shown() = AdsHuds.all.filter { it.enabled.value }

    private fun hovered(mx: Double, my: Double): AdsHud? = shown().lastOrNull {
        mx >= it.x && mx < it.x + maxOf(it.scaledWidth, MIN_BOX) && my >= it.y && my < it.y + maxOf(it.scaledHeight, MIN_BOX)
    }

    private fun resetBox(): IntArray {
        val w = font.width(RESET) + 16
        return intArrayOf((width - w) / 2, height - 28, w, 18)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {
        g.fill(0, 0, width, height, 0x50000000)
        val hint = "Drag to move · scroll to resize · right click resets one · Esc to close"
        g.text(font, hint, (width - font.width(hint)) / 2, 8, MUTED, true)
        if (shown().isEmpty()) {
            val none = "No ADS HUDs are on: turn them on in /ads."
            g.text(font, none, (width - font.width(none)) / 2, height / 2 - 4, TEXT, true)
        }

        val over = if (dragging != null) dragging else hovered(mx.toDouble(), my.toDouble())
        for (h in shown()) {
            h.render(g, example = true)
            // As Odin's editor opens: each HUD pulled onto the screen (once its size is known), so none is out of reach.
            if (h !in settled) { settled += h; h.clampToScreen() }
            // Odin's outline round each HUD, thicker under the mouse.
            outline(g, h.x - 1, h.y - 1, maxOf(h.scaledWidth, MIN_BOX) + 2, maxOf(h.scaledHeight, MIN_BOX) + 2, if (h === over) TEXT else OUTLINE)
        }

        val (bx, by, bw) = resetBox()
        val bh = resetBox()[3]
        val onReset = mx in bx until bx + bw && my in by until by + bh
        RustclearScreen.round(g, bx, by, bw, bh, if (onReset) ACCENT else SURFACE)
        g.text(font, RESET, bx + 8, by + (bh - 8) / 2, TEXT, false)

        super.extractRenderState(g, mx, my, delta)

        // Its name and description to the right of it (Odin's label), or to the left where the screen runs out.
        if (over != null && dragging == null) {
            val lines = font.split(Component.literal(over.description), LABEL_WIDTH)
            var lx = over.x + over.scaledWidth + LABEL_GAP
            if (lx + LABEL_WIDTH > width) lx = (over.x - LABEL_GAP - LABEL_WIDTH).coerceAtLeast(0)
            g.text(font, over.name, lx, over.y, TEXT, true)
            lines.forEachIndexed { i, l -> g.text(font, l, lx, over.y + (i + 1) * (font.lineHeight + 1), MUTED, true) }
        }
    }

    private fun outline(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, c: Int) {
        g.fill(x, y, x + w, y + 1, c)
        g.fill(x, y + h - 1, x + w, y + h, c)
        g.fill(x, y, x + 1, y + h, c)
        g.fill(x + w - 1, y, x + w, y + h, c)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mx = event.x()
        val my = event.y()
        val b = resetBox()
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3]) {
            AdsHuds.all.forEach { it.reset() }
            return true
        }
        val h = hovered(mx, my) ?: return super.mouseClicked(event, doubleClick)
        when (event.button()) {
            InputConstants.MOUSE_BUTTON_LEFT -> {
                dragging = h
                grabX = (mx - h.x).toInt()
                grabY = (my - h.y).toInt()
                // The one picked up draws on top from now on.
                AdsHuds.all.remove(h)
                AdsHuds.all.add(h)
            }
            InputConstants.MOUSE_BUTTON_RIGHT -> h.reset()
        }
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val h = dragging ?: return super.mouseDragged(event, dx, dy)
        h.x = event.x().toInt() - grabX
        h.y = event.y().toInt() - grabY
        h.clampToScreen()
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (dragging == null) return super.mouseReleased(event)
        dragging = null
        return true
    }

    override fun mouseScrolled(mx: Double, my: Double, scrollX: Double, scrollY: Double): Boolean {
        val h = hovered(mx, my) ?: return false
        resize(h, scrollY.sign.toFloat() * STEP)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val mc = Minecraft.getInstance()
        val scale = mc.window.guiScale.coerceAtLeast(1).toDouble()
        val h = hovered(mc.mouseHandler.xpos() / scale, mc.mouseHandler.ypos() / scale) ?: return super.keyPressed(event)
        when (event.key()) {
            InputConstants.KEY_EQUALS -> resize(h, STEP)
            InputConstants.KEY_MINUS -> resize(h, -STEP)
            InputConstants.KEY_RIGHT -> h.x += NUDGE
            InputConstants.KEY_LEFT -> h.x -= NUDGE
            InputConstants.KEY_UP -> h.y -= NUDGE
            InputConstants.KEY_DOWN -> h.y += NUDGE
            else -> return super.keyPressed(event)
        }
        h.clampToScreen()
        return true
    }

    private fun resize(h: AdsHud, by: Float) {
        // Rounded to the step, so scrolling back and forth lands where it began.
        h.scale = (Math.round((h.scale + by) * 10) / 10f).coerceIn(AdsHud.MIN_SCALE, AdsHud.MAX_SCALE)
        h.clampToScreen()
    }

    override fun isPauseScreen(): Boolean = false

    override fun removed() = AdsHuds.save()

    override fun onClose() = Minecraft.getInstance().gui.setScreen(parent)

    companion object {
        private const val RESET = "Reset All"
        private const val LABEL_GAP = 10
        private const val LABEL_WIDTH = 150
        private const val STEP = 0.1f
        private const val NUDGE = 5
        /** A HUD that drew nothing yet still gets a box this big to grab. */
        private const val MIN_BOX = 8

        private const val TEXT = 0xFFFFFFFF.toInt()
        private const val MUTED = 0xFFAAAAAA.toInt()
        private const val OUTLINE = 0xA0FFFFFF.toInt()
        private const val SURFACE = 0xFF262626.toInt()
        private const val ACCENT = 0xFF3296DC.toInt()

        /** Opens the editor (from a command: after the chat screen has closed). */
        fun open(parent: Screen? = null) {
            val mc = Minecraft.getInstance()
            mc.execute { mc.gui.setScreen(AdsHudEditor(parent)) }
        }
    }
}
