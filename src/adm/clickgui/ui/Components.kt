package com.adm.clickgui.ui

import androidx.compose.runtime.*
import com.adm.AdmMod.mc
import com.adm.clickgui.ClickGUI
import com.adm.clickgui.GuiTheme
import com.adm.clickgui.fullHue
import com.adm.clickgui.hoverTint
import com.adm.clickgui.settings.RenderableSetting
import com.adm.utils.Colors
import com.adm.utils.render.*
import com.adm.utils.ui.compose.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import kotlin.math.roundToInt

@Composable
fun RenderableSetting<*>.SettingRow(
    height: Int = GuiTheme.SETTING_HEIGHT,
    trailing: @Composable (hovered: () -> Boolean) -> Unit = {},
): UiNode {
    val pointer = remember { InteractionSource() }
    val label = remember(name) { WrappedLabel(name) }
    val minHeight = height
    var room = GuiTheme.INNER_WIDTH
    val row = Box {
        val controls = Row(GuiTheme.PADDING) { trailing { pointer.hovered } }
        controls.offset({ GuiTheme.ROW_WIDTH - GuiTheme.PADDING - controls.width }, { ((controls.parent?.height ?: 0) - controls.height) / 2 })
    }
    row.width(GuiTheme.ROW_WIDTH).height {
        val controls = children.firstOrNull()
        val used = controls?.width ?: 0
        room = GuiTheme.INNER_WIDTH - if (used > 0) used + LABEL_GAP else 0
        label.height(room, minHeight)
    }.hoverable(pointer).tooltip(description)
    return row.drawBehind { graphics -> label.draw(graphics, x + GuiTheme.PADDING, y, height, room) }
}

class WrappedLabel(private val text: String) {
    private var wrappedFor = -1
    private var lines: List<String> = listOf(text)

    fun lines(room: Int): List<String> {
        if (room != wrappedFor) {
            wrappedFor = room
            lines = wrapLabel(text, room)
        }
        return lines
    }

    fun height(room: Int, min: Int): Int = maxOf(min, lines(room).size * LINE_STEP + 3)

    fun draw(graphics: GuiGraphicsExtractor, left: Int, top: Int, height: Int, room: Int, color: Int = Colors.WHITE.rgba) {
        val shown = lines(room)
        var lineY = top + (height - shown.size * LINE_STEP + 1) / 2
        for (line in shown) {
            graphics.text(mc.font, line, left, lineY, color, false)
            lineY += LINE_STEP
        }
    }
}

fun wrapLabel(text: String, room: Int): List<String> {
    val font = mc.font
    if (room <= 0 || font.width(text) <= room) return listOf(text)
    val lines = ArrayList<String>(2)
    var line = ""
    for (word in text.split(' ')) {
        val joined = if (line.isEmpty()) word else "$line $word"
        if (line.isEmpty() || font.width(joined) <= room) line = joined
        else {
            lines += line
            line = word
        }
    }
    if (line.isNotEmpty()) lines += line
    return lines
}

const val LABEL_GAP = 4
private const val LINE_STEP = 9

@Composable
fun Gap(height: Int): UiNode = Box().height(height)

fun GuiGraphicsExtractor.textCentered(value: String, left: Int, top: Int, right: Int, bottom: Int, color: Int = Colors.WHITE.rgba) {
    val textX = left + (right - left - mc.font.width(value)) / 2
    text(mc.font, value, textX, top + (bottom - top - 8) / 2, color, false)
}

@Composable
fun UiNode.tooltip(text: String): UiNode {
    if (text.isEmpty()) return this
    val pointer = remember { InteractionSource() }
    val dwell = animateProgress(600) { pointer.hovered }
    val bubble = remember(text) { Bubble(mc.font.split(Component.literal(text), BUBBLE_WRAP)) }
    return hoverable(pointer).drawBehind {
        if (!pointer.hovered || dwell.value < 1f) return@drawBehind
        bubble.left = (right + 10).coerceIn(0, (ClickGUI.virtualWidth - bubble.width).coerceAtLeast(0))
        bubble.top = y.coerceIn(0, (ClickGUI.virtualHeight - bubble.height).coerceAtLeast(0))
        Overlay.defer(bubble)
    }
}

private class Bubble(private val lines: List<FormattedCharSequence>) : (GuiGraphicsExtractor) -> Unit {
    val width = (lines.maxOfOrNull { mc.font.width(it) } ?: 0) + BUBBLE_PAD * 2
    val height = lines.size * mc.font.lineHeight + BUBBLE_PAD * 2
    var left = 0
    var top = 0

    override fun invoke(graphics: GuiGraphicsExtractor) {
        graphics.roundedRectOutlined(left, top, left + width, top + height, GuiTheme.surface.rgba, GuiTheme.accent.fullHue(), 1.5f, GuiTheme.RADIUS)
        val step = mc.font.lineHeight
        for (i in lines.indices) graphics.text(mc.font, lines[i], left + BUBBLE_PAD, top + BUBBLE_PAD + i * step, Colors.WHITE.rgba, false)
    }
}

fun UiNode.outlined(corners: Corners, width: Float = 1f, fill: () -> Int = { GuiTheme.surface.rgba }) =
    drawBehind { graphics -> graphics.roundedRectOutlined(x, y, right, bottom, fill(), GuiTheme.accent.rgba, width, corners) }

fun UiNode.outlined(radius: Float = GuiTheme.RADIUS, width: Float = 1f, fill: () -> Int = { GuiTheme.surface.rgba }) =
    outlined(Corners(radius), width, fill)

@Composable
fun Switch(checked: Boolean, onClick: (() -> Unit)? = null): UiNode {
    val pointer = remember { InteractionSource() }
    val hover = animateProgress { pointer.hovered }
    val on = animateProgress(checked, 200)
    val track = Box {
        Box().size(SWITCH_WIDTH, SWITCH_HEIGHT).outlined(SWITCH_CORNERS, 1.5f) { GuiTheme.surface.hoverTint(hover.value) }
        Canvas { graphics ->
            val shown = on.value
            if (shown > 0f) {
                val edge = x + (SWITCH_WIDTH * shown).roundToInt()
                graphics.roundedRectClipped(x, y, right, bottom, x, y, edge, bottom, GuiTheme.accent.hoverTint(hover.value), SWITCH_CORNERS)
            }
        }.size(SWITCH_WIDTH, SWITCH_HEIGHT)
        Canvas { graphics -> graphics.circle(x + SWITCH_HEIGHT / 2, y + SWITCH_HEIGHT / 2, KNOB_RADIUS, Colors.WHITE.rgba) }
            .size(SWITCH_HEIGHT)
            .offset(x = { ((SWITCH_WIDTH - SWITCH_HEIGHT).toFloat() * on.value).roundToInt() })
    }
    return track.clickable(onClick = onClick).hoverable(pointer).scale { 1f - PRESS_SHRINK * hover.value }
}

@Composable
fun Button(text: String, width: Int, height: Int, onClick: () -> Unit): UiNode {
    val pointer = remember { InteractionSource() }
    val hover = animateProgress { pointer.hovered }
    return Box().size(width, height)
        .hoverable(pointer)
        .clickable(onClick = onClick)
        .scale { 1f - PRESS_SHRINK * hover.value }
        .outlined { GuiTheme.surface.hoverTint(hover.value) }
        .drawBehind { graphics -> graphics.textCentered(text, x, y, right, bottom) }
}

@Composable
fun Pill(
    text: () -> String,
    color: () -> Int = { Colors.WHITE.rgba },
    onClick: (() -> Unit)? = null,
    onRightClick: (() -> Unit)? = null
): UiNode {
    val pointer = remember { InteractionSource() }
    val hover = animateProgress { pointer.hovered }
    val label = Canvas(size = { mc.font.width(text()) + GuiTheme.PADDING * 2 to PILL_HEIGHT }) { graphics ->
        graphics.text(mc.font, text(), x + GuiTheme.PADDING, y + (height - 8) / 2, color(), false)
    }
    return label.clickable(onRightClick = onRightClick, onClick = onClick)
        .hoverable(pointer)
        .outlined { GuiTheme.surface.hoverTint(hover.value, 1.2f) }
}

@Composable
fun Pill(text: String, onClick: (() -> Unit)? = null, onRightClick: (() -> Unit)? = null): UiNode {
    val latest = rememberUpdatedState(text)
    return Pill({ latest.value }, onClick = onClick, onRightClick = onRightClick)
}

@Composable
fun Icon(
    texture: Identifier,
    size: Int,
    hovered: (() -> Boolean)? = null,
    rotation: () -> Float = { 0f },
    onClick: (() -> Unit)? = null
): UiNode {
    val pointer = remember { InteractionSource() }
    val hover = animateProgress { if (hovered != null) hovered() else pointer.hovered }
    val half = size / 2f
    return Canvas { graphics ->
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x + half, y + half)
        pose.scale(1f + ICON_GROW * hover.value)
        pose.rotate(rotation())
        pose.translate(-half, -half)
        graphics.roundedTexture(0, 0, size, size, texture)
        pose.popMatrix()
    }.clickable(onClick = onClick).size(size).hoverable(pointer)
}

@Composable
fun TextBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    maxLength: Int = 32,
    centered: Boolean = false,
    radius: Float = GuiTheme.RADIUS,
    filter: (String) -> String = { it },
    focusRequester: FocusRequester? = null
): UiNode {
    var typing by remember { mutableStateOf(false) }
    val field = TextInput(value, onValueChange, maxLength, centered, filter, focusRequester, onFocusChanged = { typing = it })
    return field.outlined(radius).drawBehind { graphics ->
        if (placeholder.isEmpty() || value.isNotEmpty() || typing) return@drawBehind
        val textX = if (centered) x + width / 2 - mc.font.width(placeholder) / 2 else x + 4
        graphics.text(mc.font, placeholder, textX, y + (height - 8) / 2, Colors.MINECRAFT_GRAY.rgba, false)
    }
}

const val SWITCH_HEIGHT = 13
private const val SWITCH_WIDTH = 24
private const val PILL_HEIGHT = 14
private const val KNOB_RADIUS = SWITCH_HEIGHT / 2f - 2.5f
private const val PRESS_SHRINK = 0.06f
private const val ICON_GROW = 0.15f
private const val BUBBLE_WRAP = 200
private const val BUBBLE_PAD = 8
private val SWITCH_CORNERS = Corners(SWITCH_HEIGHT / 2f)
