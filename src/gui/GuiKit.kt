package com.engineerclient.gui

import com.engineerclient.EngineerClient.mc
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.features.impl.render.ClickGUIModule
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.brighter
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.Corners
import com.odtheking.odin.utils.render.pushScissor
import com.odtheking.odin.utils.render.roundedRectOutlined
import com.odtheking.odin.utils.ui.animations.Easing
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.util.ARGB
import kotlin.math.max
import kotlin.math.min

object Theme {
    const val WIDTH = 160
    const val ROW = 21
    const val PAD = 6
    const val RADIUS = 5f
    const val CAP = 5
    const val INNER = WIDTH - PAD * 2

    val background: Color get() = Colors.gray26
    val surface: Color get() = Colors.gray38
    val accent: Color get() = ClickGUIModule.clickGUIColor
    val shadow = Color(0, 0, 0, 0.4f).rgba
    val muted = Colors.MINECRAFT_GRAY.rgba
    const val WHITE = -1
}

fun blend(from: Int, to: Int, t: Float): Int = if (t <= 0f) from else if (t >= 1f) to else ARGB.srgbLerp(t, from, to)
fun Color.hoverTint(t: Float, factor: Float = 1.3f): Int = blend(rgba, brighter(factor).rgba, t)

/** A 0..1 value that walks toward its target over [ms]; read once a frame. */
class Anim(private val ms: Float, start: Boolean = false, private val easing: Easing = Easing.EASE_IN_OUT) {
    private var raw = if (start) 1f else 0f
    private var last = System.nanoTime()

    fun update(target: Boolean): Float {
        val now = System.nanoTime()
        val step = (now - last) / 1_000_000f / ms
        last = now
        raw = if (target) min(1f, raw + step) else max(0f, raw - step)
        return easing.at(raw)
    }

    fun snap(target: Boolean) {
        raw = if (target) 1f else 0f
        last = System.nanoTime()
    }
}

/** Smoothly follows a number (scroll offsets, slider fills). */
class Follow(var value: Float) {
    private var last = System.nanoTime()
    fun update(target: Float, rate: Float = 18f): Float {
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
        last = now
        value += (target - value) * min(1f, dt * rate)
        if (kotlin.math.abs(target - value) < 0.05f) value = target
        return value
    }
}

class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    fun contains(x: Double, y: Double) = x >= x0 && x < x1 && y >= y0 && y < y1
    fun intersect(o: Rect) = Rect(max(x0, o.x0), max(y0, o.y0), min(x1, o.x1), min(y1, o.y1))
}

/** Something clickable drawn this frame; the screen checks the newest first. */
class Hit(val rect: Rect, val owner: Any?, val onPress: (button: Int, x: Double, y: Double) -> Boolean)

/** One frame's drawing: the graphics, where the mouse is and what can be clicked. */
class Ui(val g: GuiGraphicsExtractor, val mouseX: Double, val mouseY: Double, val hits: MutableList<Hit>) {
    private val clips = ArrayDeque<Rect>()
    var hoverEnabled = true
    val font get() = mc.font

    val clip: Rect? get() = clips.lastOrNull()

    fun pushClip(x0: Int, y0: Int, x1: Int, y1: Int) {
        val r = Rect(x0, y0, x1, y1).let { r -> clip?.intersect(r) ?: r }
        clips.addLast(r)
        g.pushScissor(r.x0, r.y0, max(r.x0, r.x1), max(r.y0, r.y1))
    }

    fun popClip() {
        clips.removeLast()
        g.disableScissor()
    }

    inline fun clipped(x0: Int, y0: Int, x1: Int, y1: Int, block: () -> Unit) {
        pushClip(x0, y0, x1, y1)
        try { block() } finally { popClip() }
    }

    fun hovered(x0: Int, y0: Int, x1: Int, y1: Int): Boolean =
        hoverEnabled && Rect(x0, y0, x1, y1).contains(mouseX, mouseY) && clip?.contains(mouseX, mouseY) != false

    fun hit(x0: Int, y0: Int, x1: Int, y1: Int, owner: Any? = null, onPress: (button: Int, x: Double, y: Double) -> Boolean) {
        val r = Rect(x0, y0, x1, y1).let { r -> clip?.intersect(r) ?: r }
        if (r.x1 > r.x0 && r.y1 > r.y0) hits += Hit(r, owner, onPress)
    }

    fun click(x0: Int, y0: Int, x1: Int, y1: Int, owner: Any? = null, right: (() -> Unit)? = null, left: (() -> Unit)? = null) =
        hit(x0, y0, x1, y1, owner) { button, _, _ ->
            when (button) {
                InputConstants.MOUSE_BUTTON_LEFT -> left?.invoke() != null
                InputConstants.MOUSE_BUTTON_RIGHT -> right?.invoke() != null
                else -> false
            }
        }

    fun text(s: String, x: Int, y: Int, color: Int = Theme.WHITE) = g.text(font, s, x, y, color, false)

    fun textCentered(s: String, x0: Int, y0: Int, x1: Int, y1: Int, color: Int = Theme.WHITE) =
        g.text(font, s, x0 + (x1 - x0 - font.width(s)) / 2, y0 + (y1 - y0 - 8) / 2, color, false)

    fun tooltip(x0: Int, y0: Int, x1: Int, y1: Int, text: String) {
        if (text.isNotBlank() && hovered(x0, y0, x1, y1)) AdmClickGui.offerTooltip(text, x1, y0)
    }
}

/** A one-line text box: typing, Backspace (Ctrl: all of it), Ctrl+V / Ctrl+C. */
class TextField(
    var maxLength: Int,
    private val allowed: (Char) -> Boolean = { true },
    private val onChange: (String) -> Unit = {},
) {
    var text = ""
        private set
    var onDone: (() -> Unit)? = null

    fun set(value: String) {
        text = value.filter(allowed).take(maxLength)
    }

    private fun edit(value: String) {
        val v = value.filter(allowed).take(maxLength)
        if (v == text) return
        text = v
        onChange(v)
    }

    fun char(event: CharacterEvent): Boolean {
        val s = event.codepointAsString()
        if (!event.isAllowedChatCharacter()) return false
        edit(text + s)
        return true
    }

    fun key(event: KeyEvent): Boolean {
        when {
            event.key() == InputConstants.KEY_BACKSPACE -> edit(if (event.hasControlDownWithQuirk()) "" else text.dropLast(1))
            event.isPaste -> edit(text + mc.keyboardHandler.clipboard.replace("\n", ""))
            event.isCopy -> mc.keyboardHandler.clipboard = text
            event.isSelectAll -> Unit
            event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_ESCAPE -> {
                AdmClickGui.unfocus()
            }
            else -> return false
        }
        return true
    }

    fun draw(ui: Ui, x0: Int, y0: Int, x1: Int, y1: Int, placeholder: String = "", centered: Boolean = false, radius: Float = Theme.RADIUS) {
        val focused = AdmClickGui.focus === this
        val hover = ui.hovered(x0, y0, x1, y1)
        ui.g.roundedRectOutlined(x0, y0, x1, y1, Theme.surface.hoverTint(if (hover) 0.4f else 0f), if (focused) Theme.accent.brighter(1.3f).rgba else Theme.accent.rgba, 1f, Corners(radius))
        val shown = if (text.isEmpty() && !focused) placeholder else text
        val color = if (text.isEmpty() && !focused) Theme.muted else Theme.WHITE
        val room = x1 - x0 - 8
        var visible = shown
        while (ui.font.width(visible) > room && visible.isNotEmpty()) visible = visible.drop(1)
        val tx = if (centered) x0 + (x1 - x0 - ui.font.width(visible)) / 2 else x0 + 4
        val ty = y0 + (y1 - y0 - 8) / 2
        ui.clipped(x0 + 2, y0, x1 - 2, y1) {
            ui.text(visible, tx, ty, color)
            if (focused && (System.currentTimeMillis() / 500) % 2 == 0L) {
                val cx = tx + ui.font.width(if (text.isEmpty()) "" else visible)
                ui.g.fill(cx, ty - 1, cx + 1, ty + 9, Theme.WHITE)
            }
        }
        ui.click(x0, y0, x1, y1, owner = this) { if (!focused) AdmClickGui.focus(this) }
    }
}
