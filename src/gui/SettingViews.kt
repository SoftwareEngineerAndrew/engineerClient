package com.engineerclient.gui

import com.engineerclient.EngineerClient.mc
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.HudManager
import com.odtheking.odin.clickgui.settings.RenderableSetting
import com.odtheking.odin.clickgui.settings.Setting
import com.odtheking.odin.clickgui.settings.impl.*
import com.odtheking.odin.features.impl.render.ClickGUIModule
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.darker
import com.odtheking.odin.utils.Color.Companion.hsbMax
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.*
import net.minecraft.client.input.KeyEvent
import net.minecraft.resources.Identifier
import kotlin.math.PI
import kotlin.math.roundToInt

/** How one setting is drawn and used; [height] may grow while something of it is open. */
abstract class SettingView(base: RenderableSetting<*>) {
    open val setting: RenderableSetting<*> = base
    // Subclasses override setting, which is still null while this constructor runs.
    val shown = Anim(200f, base.isVisible)
    abstract fun height(): Int
    abstract fun draw(ui: Ui, x: Int, y: Int)

    open fun key(event: KeyEvent, hovered: Boolean): Boolean = false

    protected fun label(ui: Ui, x: Int, y: Int, h: Int) {
        ui.text(setting.name, x + Theme.PAD, y + (h - 8) / 2)
        ui.tooltip(x, y, x + Theme.WIDTH, y + h, setting.description)
    }

    companion object {
        fun of(s: RenderableSetting<*>): SettingView = when (s) {
            is BooleanSetting -> BooleanView(s)
            is NumberSetting<*> -> NumberView(s)
            is SelectorSetting<*> -> SelectorView(s)
            is ColorSetting -> ColorView(s)
            is KeybindSetting -> KeybindView(s)
            is StringSetting -> StringView(s)
            is ActionSetting -> ActionView(s)
            is DropdownSetting -> DropdownView(s)
            is HUDSetting -> HudView(s)
            else -> PlainView(s)
        }

        fun privateField(owner: Any, name: String): Any? = runCatching {
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        }.getOrNull()
    }
}

fun Ui.switch(x: Int, y: Int, on: Boolean, anim: Anim, hoverAnim: Anim, onClick: () -> Unit) {
    val w = 24
    val h = 13
    val hover = hoverAnim.update(hovered(x, y, x + w, y + h))
    val p = anim.update(on)
    val corners = Corners(h / 2f)
    g.roundedRectOutlined(x, y, x + w, y + h, Theme.surface.hoverTint(hover), Theme.accent.rgba, 1.2f, corners)
    if (p > 0f) g.roundedRectClipped(x, y, x + w, y + h, x, y, x + (w * p).roundToInt(), y + h, Theme.accent.hoverTint(hover), corners)
    g.circle(x + h / 2 + ((w - h) * p).roundToInt(), y + h / 2, h / 2f - 2.5f, Theme.WHITE)
    click(x - 2, y - 2, x + w + 2, y + h + 2) { onClick() }
}

fun Ui.pill(text: String, right: Int, y: Int, h: Int, color: Int = Theme.WHITE, hoverAnim: Anim): Rect {
    val w = font.width(text) + Theme.PAD * 2
    val x0 = right - w
    val hover = hoverAnim.update(hovered(x0, y, right, y + h))
    g.roundedRectOutlined(x0, y, right, y + h, Theme.surface.hoverTint(hover, 1.25f), Theme.accent.rgba, 1f, Corners(Theme.RADIUS))
    text(text, x0 + Theme.PAD, y + (h - 8) / 2, color)
    return Rect(x0, y, right, y + h)
}

fun Ui.button(text: String, x0: Int, y0: Int, x1: Int, y1: Int, hoverAnim: Anim, onClick: () -> Unit) {
    val hover = hoverAnim.update(hovered(x0, y0, x1, y1))
    g.roundedRectOutlined(x0, y0, x1, y1, Theme.surface.hoverTint(hover), Theme.accent.rgba, 1f, Corners(Theme.RADIUS))
    textCentered(text, x0, y0, x1, y1)
    click(x0, y0, x1, y1) { onClick() }
}

fun Ui.icon(texture: Identifier, x: Int, y: Int, size: Int, hover: Float, rotation: Float = 0f) {
    val pose = g.pose()
    pose.pushMatrix()
    pose.translate(x + size / 2f, y + size / 2f)
    pose.scale(1f + 0.15f * hover)
    pose.rotate(rotation)
    pose.translate(-size / 2f, -size / 2f)
    g.roundedTexture(0, 0, size, size, texture)
    pose.popMatrix()
}

private val RIGHT = Theme.WIDTH - Theme.PAD

class BooleanView(override val setting: BooleanSetting) : SettingView(setting) {
    private val anim = Anim(200f, setting.enabled)
    private val hover = Anim(150f)
    override fun height() = Theme.ROW
    override fun draw(ui: Ui, x: Int, y: Int) {
        label(ui, x, y, Theme.ROW)
        ui.switch(x + RIGHT - 24, y + 4, setting.enabled, anim, hover) { setting.enabled = !setting.enabled }
        ui.click(x, y, x + Theme.WIDTH, y + Theme.ROW) { setting.enabled = !setting.enabled }
    }
}

@Suppress("UNCHECKED_CAST")
class NumberView(override val setting: NumberSetting<*>) : SettingView(setting) {
    private val fill = Follow(setting.percent)
    private val knob = Anim(150f)
    private var dragging = false
    private val field = TextField(16, { it.isDigit() || it == '.' || it == '-' })

    init {
        field.onDone = {
            field.text.toDoubleOrNull()?.let { (setting as Setting<Any?>).value = it }
        }
    }

    override fun height() = 31

    override fun draw(ui: Ui, x: Int, y: Int) {
        label(ui, x, y, 18)
        if (AdmClickGui.focus === field) {
            field.draw(ui, x + RIGHT - 52, y + 3, x + RIGHT, y + 16, radius = 3f)
        } else {
            val w = ui.font.width(setting.display)
            ui.text(setting.display, x + RIGHT - w, y + 5)
            ui.click(x + RIGHT - w - 3, y + 2, x + RIGHT + 2, y + 16) {
                field.set(setting.value.toString().removeSuffix(".0"))
                AdmClickGui.focus(field)
            }
        }

        val x0 = x + Theme.PAD
        val x1 = x + RIGHT
        val ty = y + 21
        val hovered = ui.hovered(x0 - 2, y + 17, x1 + 2, y + 29)
        val p = if (dragging) fill.update(setting.percent, 1000f) else fill.update(setting.percent)
        val k = knob.update(dragging || hovered)
        val track = Corners(3f)
        ui.g.roundedRect(x0, ty, x1, ty + 6, Theme.surface.rgba, track)
        val filled = ((x1 - x0) * p).roundToInt()
        if (filled > 0) ui.g.roundedRectClipped(x0, ty, x1, ty + 6, x0, ty, x0 + filled, ty + 6, Theme.accent.rgba, track)
        ui.g.circle(x0 + filled, ty + 3, 4f + 1.5f * k, Theme.WHITE)

        val seek = { mx: Double -> setting.percent = ((mx - x0) / (x1 - x0)).toFloat().coerceIn(0f, 1f) }
        ui.hit(x0 - 4, y + 17, x1 + 4, y + 30) { button, mx, _ ->
            if (button != InputConstants.MOUSE_BUTTON_LEFT) return@hit false
            dragging = true
            seek(mx)
            AdmClickGui.drag({ dx, _ -> seek(dx) }) { dragging = false }
            true
        }
    }

    override fun key(event: KeyEvent, hovered: Boolean): Boolean {
        if (!hovered) return false
        when (event.key()) {
            InputConstants.KEY_RIGHT, InputConstants.KEY_EQUALS -> setting.nudge(1)
            InputConstants.KEY_LEFT, InputConstants.KEY_MINUS -> setting.nudge(-1)
            else -> return false
        }
        return true
    }
}

val Enum<*>.optionLabel: String
    get() = toString().takeIf { it != name } ?: name.split('_').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

@Suppress("UNCHECKED_CAST")
class SelectorView(override val setting: SelectorSetting<*>) : SettingView(setting) {
    private var open = false
    private val openAnim = Anim(220f)
    private val hover = Anim(150f)
    private var progress = 0f
    private val listHeight get() = setting.options.size * OPTION + 5

    override fun height() = Theme.ROW + (listHeight * progress).roundToInt()

    override fun draw(ui: Ui, x: Int, y: Int) {
        progress = openAnim.update(open)
        label(ui, x, y, Theme.ROW)
        val pill = ui.pill(setting.value.optionLabel, x + RIGHT, y + 3, Theme.ROW - 6, hoverAnim = hover)
        ui.click(pill.x0, pill.y0, pill.x1, pill.y1, right = { setting.cycle() }) { open = !open }
        if (progress <= 0f) return
        val top = y + Theme.ROW
        val x0 = x + 4
        val x1 = x + Theme.WIDTH - 4
        ui.clipped(x, top, x + Theme.WIDTH, top + (listHeight * progress).roundToInt()) {
            ui.g.roundedRect(x0, top, x1, top + setting.options.size * OPTION, Theme.surface.rgba, Theme.RADIUS)
            setting.options.forEachIndexed { i, option ->
                val oy = top + i * OPTION
                if (i < setting.options.lastIndex) ui.g.fill(x0 + 10, oy + OPTION, x1 - 10, oy + OPTION + 1, Colors.MINECRAFT_DARK_GRAY.rgba)
                if (ui.hovered(x0, oy, x1, oy + OPTION)) ui.g.roundedOutline(x0, oy, x1, oy + OPTION + 1, Theme.accent.rgba, 1.5f, Theme.RADIUS)
                ui.textCentered(option.optionLabel, x0, oy, x1, oy + OPTION, if (option == setting.value) Theme.accent.rgba else Theme.WHITE)
                ui.click(x0, oy, x1, oy + OPTION) {
                    (setting as Setting<Any?>).value = option
                    open = false
                }
            }
        }
    }

    private companion object {
        const val OPTION = 16
    }
}

class ColorView(override val setting: ColorSetting) : SettingView(setting) {
    private val allowAlpha = privateField(setting, "allowAlpha") as? Boolean ?: false
    private var open = privateField(setting, "startExpanded") as? Boolean ?: false
    private val openAnim = Anim(250f, open)
    private var progress = 0f
    private val hex = TextField(if (allowAlpha) 8 else 6, { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { setting.applyHex(it) }

    override fun height() = Theme.ROW + (PICKER_TOTAL * progress).roundToInt()

    override fun draw(ui: Ui, x: Int, y: Int) {
        progress = openAnim.update(open)
        val color = setting.value
        label(ui, x, y, Theme.ROW)
        val sx1 = x + RIGHT
        val sx0 = sx1 - 22
        ui.g.roundedRectOutlined(sx0, y + 4, sx1, y + 17, color.rgba, color.withAlpha(1f).darker().rgba, 1.5f, Theme.RADIUS)
        ui.click(sx0, y + 4, sx1, y + 17) { open = !open }
        ui.click(x, y, x + Theme.WIDTH, y + Theme.ROW) { open = !open }
        if (AdmClickGui.focus !== hex) hex.set(color.hex(allowAlpha))
        if (progress <= 0f) return

        val top = y + Theme.ROW
        ui.clipped(x, top, x + Theme.WIDTH, top + (PICKER_TOTAL * progress).roundToInt()) {
            val px0 = x + Theme.PAD
            val bars = if (allowAlpha) 2 else 1
            val svx1 = x + RIGHT - (BAR + GAP) * bars
            val py0 = top + GAP
            val py1 = py0 + PICKER_H

            ui.g.roundedGradient(px0, py0, svx1, py1, Theme.WHITE, color.hsbMax().rgba, GradientDirection.LEFT_TO_RIGHT, Corners(2f))
            ui.g.roundedGradient(px0, py0, svx1, py1, Colors.TRANSPARENT.rgba, Colors.BLACK.rgba, GradientDirection.TOP_TO_BOTTOM, Corners(2f))
            ui.g.circle(px0 + (color.saturation * (svx1 - px0)).roundToInt(), py0 + ((1f - color.brightness) * PICKER_H).roundToInt(), 4f, Theme.WHITE)
            dragArea(ui, px0, py0, svx1, py1) { fx, fy -> setting.value = Color(setting.value.hue, fx, 1f - fy, setting.value.alphaFloat) }

            val hx0 = svx1 + GAP
            ui.g.roundedTexture(hx0, py0, hx0 + BAR, py1, HUE, corners = Corners(2f))
            marker(ui, hx0, py0, color.hue)
            dragArea(ui, hx0, py0, hx0 + BAR, py1) { _, fy -> setting.value = Color(fy, setting.value.saturation, setting.value.brightness, setting.value.alphaFloat) }

            if (allowAlpha) {
                val ax0 = hx0 + BAR + GAP
                ui.g.roundedGradient(ax0, py0, ax0 + BAR, py1, Colors.TRANSPARENT.rgba, color.withAlpha(1f).rgba, GradientDirection.TOP_TO_BOTTOM, Corners(2f))
                marker(ui, ax0, py0, color.alphaFloat)
                dragArea(ui, ax0, py0, ax0 + BAR, py1) { _, fy -> setting.value = setting.value.withAlpha(fy) }
            }

            val fy0 = py1 + GAP
            hex.draw(ui, px0, fy0, px0 + 60, fy0 + FIELD, centered = true)
            for (slot in 0 until 4) {
                val fx0 = x + RIGHT - (4 - slot) * FIELD - (3 - slot) * GAP
                val saved = ClickGUIModule.favoriteColors[slot]
                ui.g.roundedRectOutlined(fx0, fy0, fx0 + FIELD, fy0 + FIELD, saved?.rgba ?: Theme.background.withAlpha(0.4f).rgba, Theme.surface.rgba, 1f, Corners(3f))
                ui.tooltip(fx0, fy0, fx0 + FIELD, fy0 + FIELD, "Left click to use, right click to save the current colour here.")
                ui.click(fx0, fy0, fx0 + FIELD, fy0 + FIELD, right = { ClickGUIModule.favoriteColors[slot] = setting.value.copy() }) {
                    ClickGUIModule.favoriteColors[slot]?.let { setting.value = it.copy() }
                }
            }
        }
    }

    private fun marker(ui: Ui, bx: Int, by: Int, p: Float) {
        val my = by + (p * PICKER_H).roundToInt()
        ui.g.roundedRect(bx - 1, my - 2, bx + BAR + 1, my + 2, Theme.WHITE, 2f)
    }

    private fun dragArea(ui: Ui, x0: Int, y0: Int, x1: Int, y1: Int, onMove: (Float, Float) -> Unit) {
        val move = { mx: Double, my: Double ->
            onMove(((mx - x0) / (x1 - x0)).toFloat().coerceIn(0f, 1f), ((my - y0) / (y1 - y0)).toFloat().coerceIn(0f, 1f))
        }
        ui.hit(x0, y0, x1, y1) { button, mx, my ->
            if (button != InputConstants.MOUSE_BUTTON_LEFT) return@hit false
            move(mx, my)
            AdmClickGui.drag(move)
            true
        }
    }

    private companion object {
        val HUE: Identifier = Identifier.fromNamespaceAndPath("odin", "textures/huegradient.png")
        const val GAP = 3
        const val BAR = 10
        const val PICKER_H = 100
        const val FIELD = 16
        const val PICKER_TOTAL = GAP + PICKER_H + GAP + FIELD + 4
    }
}

class KeybindView(override val setting: KeybindSetting) : SettingView(setting) {
    private val hover = Anim(150f)
    val listening get() = AdmClickGui.focus === this

    override fun height() = Theme.ROW

    override fun draw(ui: Ui, x: Int, y: Int) {
        label(ui, x, y, Theme.ROW)
        val name = if (listening) "..." else setting.value.displayName.string
        val pill = ui.pill(name, x + RIGHT, y + 3, Theme.ROW - 6, if (listening) Colors.MINECRAFT_YELLOW.rgba else Theme.WHITE, hover)
        ui.hit(pill.x0, pill.y0, pill.x1, pill.y1, owner = this) { button, _, _ ->
            when {
                listening -> {
                    setting.value = InputConstants.Type.MOUSE.getOrCreate(button)
                    AdmClickGui.unfocus()
                }
                button == InputConstants.MOUSE_BUTTON_LEFT -> AdmClickGui.focus(this)
                else -> return@hit false
            }
            true
        }
    }

    fun bind(event: KeyEvent) {
        when (event.key()) {
            InputConstants.KEY_ESCAPE, InputConstants.KEY_BACKSPACE -> setting.value = InputConstants.UNKNOWN
            InputConstants.KEY_RETURN -> Unit
            else -> setting.value = InputConstants.getKey(event)
        }
        AdmClickGui.unfocus()
    }
}

class StringView(override val setting: StringSetting) : SettingView(setting) {
    private val placeholder = privateField(setting, "placeholder") as? String ?: ""
    private val field = TextField(setting.length) { setting.value = it }

    override fun height() = 17 + 20 + 3

    override fun draw(ui: Ui, x: Int, y: Int) {
        label(ui, x, y, 17)
        if (AdmClickGui.focus !== field) field.set(setting.value)
        field.draw(ui, x + Theme.PAD, y + 17, x + RIGHT, y + 37, placeholder)
    }
}

class ActionView(override val setting: ActionSetting) : SettingView(setting) {
    private val hover = Anim(150f)
    override fun height() = Theme.ROW
    override fun draw(ui: Ui, x: Int, y: Int) {
        ui.button(setting.name, x + 3, y + 2, x + Theme.WIDTH - 3, y + Theme.ROW - 2, hover) { setting.action() }
        ui.tooltip(x, y, x + Theme.WIDTH, y + Theme.ROW, setting.description)
    }
}

class DropdownView(override val setting: DropdownSetting) : SettingView(setting) {
    private val turn = Anim(200f, setting.value)
    private val hover = Anim(150f)
    override fun height() = Theme.ROW
    override fun draw(ui: Ui, x: Int, y: Int) {
        label(ui, x, y, Theme.ROW)
        val h = hover.update(ui.hovered(x, y, x + Theme.WIDTH, y + Theme.ROW))
        ui.icon(CHEVRON, x + RIGHT - 15, y + 3, 15, h, turn.update(setting.value) * (PI / 2).toFloat())
        ui.click(x, y, x + Theme.WIDTH, y + Theme.ROW) { setting.value = !setting.value }
    }

    private companion object {
        val CHEVRON: Identifier = Identifier.fromNamespaceAndPath("odin", "textures/chevron.png")
    }
}

class HudView(override val setting: HUDSetting) : SettingView(setting) {
    private val toggleable = privateField(setting, "toggleable") as? Boolean ?: false
    private val anim = Anim(200f, setting.hud.enabled)
    private val switchHover = Anim(150f)
    private val iconHover = Anim(150f)

    override fun height() = Theme.ROW

    override fun draw(ui: Ui, x: Int, y: Int) {
        label(ui, x, y, Theme.ROW)
        val ix = x + RIGHT - 15
        ui.icon(MOVE, ix, y + 3, 15, iconHover.update(ui.hovered(ix, y + 3, ix + 15, y + 18)))
        ui.tooltip(ix, y + 3, ix + 15, y + 18, "Open the HUD editor.")
        ui.click(ix, y + 3, ix + 15, y + 18) { mc.setScreenAndShow(HudManager) }
        if (toggleable) ui.switch(ix - 6 - 24, y + 4, setting.hud.enabled, anim, switchHover) { setting.hud.enabled = !setting.hud.enabled }
    }

    private companion object {
        val MOVE: Identifier = Identifier.fromNamespaceAndPath("odin", "textures/movementicon.png")
    }
}

class PlainView(setting: RenderableSetting<*>) : SettingView(setting) {
    override fun height() = Theme.ROW
    override fun draw(ui: Ui, x: Int, y: Int) {
        ui.text(setting.name, x + Theme.PAD, y + (Theme.ROW - 8) / 2, Theme.muted)
        ui.tooltip(x, y, x + Theme.WIDTH, y + Theme.ROW, setting.description)
    }
}
