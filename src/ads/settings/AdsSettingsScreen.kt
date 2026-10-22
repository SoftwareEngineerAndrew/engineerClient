package com.engineerclient.ads.settings

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.rustclear.mod.ui.RustclearScreen
import java.util.Locale

/**
 * `/ads`: ADS's settings on their own, looking like Odin's Click GUI (its panel size, row height, greys and blue
 * accent) without needing Odin. One draggable panel per group, a header with the group's name, a row per setting:
 * a box for on/off, a slider for numbers, the current choice for selectors (left click next, right click back), a
 * key box for keybinds (click, then press a key; Esc clears it). Hovering a row shows what it does.
 */
class AdsSettingsScreen(private val sections: List<SettingsPanel>) : Screen(Component.literal("ADS settings")) {

    private class Panel(val title: String, val settings: List<Setting<*>>, var x: Int, var y: Int)

    private val panels = mutableListOf<Panel>()
    private var dragging: Panel? = null
    private var dragDx = 0
    private var dragDy = 0
    /** The number setting whose slider is held, and that slider's left edge. */
    private var sliding: NumberSetting<*>? = null
    private var slideX = 0
    /** The keybind waiting for a key. */
    private var listening: KeybindSetting? = null
    private var scroll = 0

    override fun init() {
        if (panels.isNotEmpty()) return
        // As many columns as fit, each panel under the shortest column so far: the panels pack together instead
        // of each row waiting for its tallest, and more of them fit on screen.
        val saved = LAYOUT[TITLE]
        val columns = maxOf(1, (width - 10) / (WIDTH + 10))
        val heights = IntArray(columns) { 30 }
        for ((_, title, settings) in sections) {
            val c = heights.indices.minBy { heights[it] }
            val pos = saved?.get(title)
            panels += Panel(title, settings, pos?.first ?: (10 + c * (WIDTH + 10)), pos?.second ?: heights[c])
            heights[c] += panelHeight(settings) + 10
        }
    }

    private fun rowHeight(s: Setting<*>) = if (s is NumberSetting<*>) SLIDER_ROW else ROW
    private fun panelHeight(settings: List<Setting<*>>) = HEADER + settings.filter { it.shown() }.sumOf { rowHeight(it) } + 4

    override fun extractRenderState(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {
        g.fill(0, 0, width, height, 0x90000000.toInt())
        RustclearScreen.logo(g, 8, 6, 14)
        g.text(font, TITLE, 26, 9, TEXT, true)
        g.text(font, "Drag a panel by its name · scroll to move them all · hover for help", 26 + font.width(TITLE) + 8, 9, MUTED, false)

        var tooltip: Setting<*>? = null
        for (p in panels) {
            val px = p.x
            val py = p.y - scroll
            val shown = p.settings.filter { it.shown() }
            val h = panelHeight(p.settings)
            // Odin's panel: a dark body, a slightly lighter header with the name centred, the accent under it.
            RustclearScreen.round(g, px, py, WIDTH, h, BACKGROUND)
            RustclearScreen.round(g, px, py, WIDTH, HEADER, SURFACE)
            g.text(font, p.title, px + (WIDTH - font.width(p.title)) / 2, py + (HEADER - 8) / 2, TEXT, true)
            g.fill(px, py + HEADER - 1, px + WIDTH, py + HEADER, ACCENT)
            var ry = py + HEADER
            for (s in shown) {
                val rh = rowHeight(s)
                val hover = mx in px until px + WIDTH && my in ry until ry + rh
                if (hover) {
                    g.fill(px, ry, px + WIDTH, ry + rh, HOVER)
                    tooltip = s
                }
                drawRow(g, s, px, ry, mx)
                ry += rh
            }
        }
        super.extractRenderState(g, mx, my, delta)
        val t = tooltip
        if (t != null && t.description.isNotEmpty() && dragging == null && sliding == null) {
            val lines = font.split(Component.literal(t.description), 220)
            g.setTooltipForNextFrame(font, lines, mx, my)
        }
    }

    private fun drawRow(g: GuiGraphicsExtractor, s: Setting<*>, x: Int, y: Int, mx: Int) {
        val left = x + PAD
        val right = x + WIDTH - PAD
        val textY = y + (ROW - 8) / 2
        when (s) {
            is BooleanSetting -> {
                g.text(font, fit(s.name, WIDTH - 2 * PAD - 16), left, textY, TEXT, false)
                val bx = right - 11
                val by = y + (ROW - 11) / 2
                RustclearScreen.round(g, bx, by, 11, 11, if (s.value) ACCENT else SURFACE)
                if (s.value) {
                    // A tick.
                    g.fill(bx + 2, by + 5, bx + 4, by + 7, TEXT)
                    g.fill(bx + 4, by + 6, bx + 5, by + 8, TEXT)
                    g.fill(bx + 5, by + 3, bx + 9, by + 6, TEXT)
                    g.fill(bx + 5, by + 5, bx + 7, by + 7, TEXT)
                }
            }
            is NumberSetting<*> -> {
                val v = valueText(s)
                g.text(font, fit(s.name, WIDTH - 2 * PAD - font.width(v) - 6), left, textY, TEXT, false)
                g.text(font, v, right - font.width(v), textY, MUTED, false)
                val track = right - left
                val frac = ((s.value.toDouble() - s.min) / (s.max - s.min)).coerceIn(0.0, 1.0)
                val ty = y + ROW + 1
                RustclearScreen.round(g, left, ty, track, 4, SURFACE)
                RustclearScreen.round(g, left, ty, maxOf(4, (track * frac).toInt()), 4, ACCENT)
                if (sliding === s) setFrom(s, mx, left, track)
            }
            is SelectorSetting<*> -> {
                val v = pretty(s.value.name)
                g.text(font, fit(s.name, WIDTH - 2 * PAD - font.width(v) - 6), left, textY, TEXT, false)
                g.text(font, v, right - font.width(v), textY, ACCENT, false)
            }
            is KeybindSetting -> {
                val v = if (listening === s) "..." else if (s.value == InputConstants.UNKNOWN) "None" else s.value.displayName.string
                g.text(font, fit(s.name, WIDTH - 2 * PAD - font.width(v) - 14), left, textY, TEXT, false)
                val bw = font.width(v) + 8
                RustclearScreen.round(g, right - bw, y + 4, bw, ROW - 8, SURFACE)
                g.text(font, v, right - bw + 4, textY, if (listening === s) ACCENT else TEXT, false)
            }
            else -> g.text(font, s.name, left, textY, TEXT, false)
        }
    }

    private fun valueText(s: NumberSetting<*>): String {
        val n = s.value.toDouble()
        val shown = if (s.isInt || n == Math.floor(n)) n.toLong().toString() else String.format(Locale.ROOT, "%.1f", n)
        return shown + s.unit
    }

    private fun setFrom(s: NumberSetting<*>, mx: Int, left: Int, track: Int) {
        val frac = ((mx - left).toDouble() / track).coerceIn(0.0, 1.0)
        s.set(s.min + frac * (s.max - s.min))
    }

    /** The setting row under (mx, my) and its top, or null. */
    private fun rowAt(mx: Double, my: Double): Triple<Panel, Setting<*>, Int>? {
        for (p in panels.asReversed()) {
            val py = p.y - scroll
            if (mx < p.x || mx >= p.x + WIDTH) continue
            var ry = py + HEADER
            for (s in p.settings.filter { it.shown() }) {
                val rh = rowHeight(s)
                if (my >= ry && my < ry + rh) return Triple(p, s, ry)
                ry += rh
            }
        }
        return null
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mx = event.x()
        val my = event.y()
        val button = event.button()
        listening?.let {
            // Clicking while a keybind listens binds that mouse button (any but a plain left click elsewhere).
            if (button != InputConstants.MOUSE_BUTTON_LEFT) {
                it.value = InputConstants.Type.MOUSE.getOrCreate(button)
                listening = null
                save()
                return true
            }
            listening = null
        }
        // A header starts a drag (the topmost panel first).
        for (p in panels.asReversed()) {
            val py = p.y - scroll
            if (mx >= p.x && mx < p.x + WIDTH && my >= py && my < py + HEADER) {
                dragging = p
                dragDx = (mx - p.x).toInt()
                dragDy = (my - py).toInt()
                panels.remove(p)
                panels.add(p)
                return true
            }
        }
        val (_, s, _) = rowAt(mx, my) ?: return super.mouseClicked(event, doubleClick)
        val back = button == InputConstants.MOUSE_BUTTON_RIGHT
        when (s) {
            is BooleanSetting -> s.value = !s.value
            is SelectorSetting<*> -> s.index = s.index + if (back) -1 else 1
            is NumberSetting<*> -> {
                if (back) s.reset()
                else {
                    val p = panels.first { s in it.settings }
                    sliding = s
                    slideX = p.x + PAD
                }
            }
            is KeybindSetting -> listening = s
            else -> {}
        }
        save()
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        dragging?.let {
            it.x = (event.x() - dragDx).toInt().coerceIn(0, width - WIDTH)
            it.y = (event.y() - dragDy).toInt() + scroll
            return true
        }
        sliding?.let {
            setFrom(it, event.x().toInt(), slideX, WIDTH - 2 * PAD)
            return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (dragging != null) {
            dragging = null
            rememberLayout()
            return true
        }
        if (sliding != null) {
            sliding = null
            save()
            return true
        }
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mx: Double, my: Double, scrollX: Double, scrollY: Double): Boolean {
        // Over a slider the wheel steps it; anywhere else it moves every panel up or down.
        val row = rowAt(mx, my)
        val s = row?.second
        if (s is NumberSetting<*>) {
            s.set(s.value.toDouble() + Math.signum(scrollY) * s.step)
            save()
            return true
        }
        scroll = (scroll - Math.signum(scrollY).toInt() * 16).coerceAtLeast(-200)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val k = listening
        if (k != null) {
            k.value = if (event.key() == InputConstants.KEY_ESCAPE) InputConstants.UNKNOWN else InputConstants.getKey(event)
            listening = null
            save()
            return true
        }
        return super.keyPressed(event)
    }

    override fun isPauseScreen(): Boolean = false

    override fun removed() {
        save()
        rememberLayout()
    }

    private fun rememberLayout() {
        LAYOUT[TITLE] = panels.associate { it.title to (it.x to it.y) }
    }

    /** Saves every owner shown: a change in any panel lands in its own file. */
    private fun save() = sections.map { it.owner }.distinct().forEach { it.save() }

    private fun fit(s: String, w: Int): String {
        if (font.width(s) <= w) return s
        var t = s
        while (t.isNotEmpty() && font.width("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }

    private companion object {
        const val TITLE = "ADS"

        // Odin's Click GUI: 160 wide, 21 px rows, 6 px padding, gray26 body, gray38 surface, its default blue.
        const val WIDTH = 160
        const val ROW = 21
        const val SLIDER_ROW = 30
        const val HEADER = 22
        const val PAD = 6
        const val BACKGROUND = 0xF01A1A1A.toInt()
        const val SURFACE = 0xFF262626.toInt()
        const val HOVER = 0x18FFFFFF
        const val ACCENT = 0xFF3296DC.toInt()
        const val TEXT = 0xFFFFFFFF.toInt()
        const val MUTED = 0xFFAAAAAA.toInt()

        /** Panel positions for the rest of the game, so the screen opens as it was left. */
        val LAYOUT = HashMap<String, Map<String, Pair<Int, Int>>>()

        fun pretty(name: String) = name.lowercase(Locale.ROOT).split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
}
