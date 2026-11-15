package com.engineerclient.render

import net.minecraft.client.gui.GuiGraphicsExtractor

/** The screen's edges glowing in a colour, fading toward the middle. */
object Vignette {
    fun draw(gfx: GuiGraphicsExtractor, rgb: Int, maxAlpha: Int) {
        val w = gfx.guiWidth(); val h = gfx.guiHeight()
        val band = minOf(w, h) / 5
        val steps = 16
        for (i in 0 until steps) {
            val alpha = (maxAlpha * (steps - i) / steps).coerceIn(0, 255)
            val c = (alpha shl 24) or (rgb and 0xFFFFFF)
            val a = band * i / steps; val b = band * (i + 1) / steps
            gfx.fill(a, b, b, h - b, c)
            gfx.fill(w - b, b, w - a, h - b, c)
            gfx.fill(a, a, w - a, b, c)
            gfx.fill(a, h - b, w - a, h - a, c)
        }
    }
}
