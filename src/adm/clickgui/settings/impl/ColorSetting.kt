package com.adm.clickgui.settings.impl

import androidx.compose.runtime.*
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.mojang.blaze3d.platform.InputConstants
import com.adm.clickgui.settings.SettingState
import com.adm.clickgui.GuiTheme
import com.adm.clickgui.fullHue
import com.adm.clickgui.settings.RenderableSetting
import com.adm.clickgui.settings.Saving
import com.adm.clickgui.ui.*
import com.adm.features.impl.render.ClickGUIModule
import com.adm.utils.Color
import com.adm.utils.Color.Companion.darker
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.Colors
import com.adm.utils.render.*
import com.adm.utils.ui.compose.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt

class ColorSetting(
    name: String,
    override val default: Color,
    private val allowAlpha: Boolean = false,
    desc: String,
    private val startExpanded: Boolean = false
) : RenderableSetting<Color>(name, desc), Saving {

    override var value: Color by SettingState(default.copy())

    private val digits = if (allowAlpha) 8 else 6

    fun applyHex(hex: String) {
        val typed = hex.filter { it.isHex() }
        if (typed.length != digits || typed == value.hex(allowAlpha)) return
        value = Color(typed.padEnd(8, 'F'))
    }

    @Composable
    override fun Content() {
        var open by remember { mutableStateOf(startExpanded) }
        val color = value

        Column {
            SettingRow {
                val rim = remember(color) { color.withAlpha(1f).darker().rgba }
                Canvas { graphics -> graphics.roundedRectOutlined(x, y, right, bottom, color.rgba, rim, 1.5f, GuiTheme.RADIUS) }
                    .clickable(onClick = { open = !open })
                    .size(SWATCH_WIDTH, SWATCH_HEIGHT)
            }
            AnimatedVisibility(open) { Picker(color) }
        }
    }

    @Composable
    private fun Picker(color: Color) {
        Column {
            Gap(GAP)
            Row(spacing = GAP) {
                ShadeSquare(color).size(PICKER_WIDTH - (BAR_WIDTH + GAP) * (if (allowAlpha) 2 else 1), PICKER_HEIGHT)
                HueBar(color)
                if (allowAlpha) AlphaBar(color)
            }.offset({ GuiTheme.PADDING })
            Gap(GAP)
            Box {
                TextBox(
                    value.hex(allowAlpha), ::applyHex,
                    maxLength = digits, centered = true, filter = { it.filter(Char::isHex).uppercase() },
                ).size(HEX_WIDTH, FIELD_HEIGHT)
                Row(spacing = GAP) { repeat(FAVORITE_SLOTS) { Favorite(it) } }.offset({ PICKER_WIDTH - FAVORITES_WIDTH })
            }.offset({ GuiTheme.PADDING })
            Gap(2)
        }.width(GuiTheme.ROW_WIDTH)
    }

    @Composable
    private fun Favorite(slot: Int) {
        Canvas { graphics ->
            val fill = ClickGUIModule.favoriteColors[slot]?.rgba ?: emptySlot
            graphics.roundedRectOutlined(x, y, right, bottom, fill, GuiTheme.surface.rgba, 1f, FAVORITE_RADIUS)
        }.size(FIELD_HEIGHT).clickable(onRightClick = { ClickGUIModule.favoriteColors[slot] = value.copy() }) {
            ClickGUIModule.favoriteColors[slot]?.let { value = it.copy() }
        }
    }

    @Composable
    private fun ShadeSquare(color: Color): UiNode {
        val sat = animateFloatAsState(color.saturation, MARKER_DURATION)
        val bright = animateFloatAsState(color.brightness, MARKER_DURATION)
        val pure = remember(color) { color.fullHue() }
        return Canvas { graphics ->
            graphics.roundedGradient(x, y, right, bottom, Colors.WHITE.rgba, pure, GradientDirection.LEFT_TO_RIGHT, BAR_CORNERS)
            graphics.roundedGradient(x, y, right, bottom, Colors.TRANSPARENT.rgba, Colors.BLACK.rgba, GradientDirection.TOP_TO_BOTTOM, BAR_CORNERS)
            val dotX = (x + sat.value * width).roundToInt()
            val dotY = (y + (1f - bright.value) * height).roundToInt()
            graphics.circle(dotX, dotY, MARKER_RADIUS, Colors.WHITE.rgba)
        }.dragArea { fx, fy -> value = Color(value.hue, fx, 1f - fy, value.alphaFloat) }
    }

    @Composable
    private fun HueBar(color: Color) {
        val hue = animateFloatAsState(color.hue, MARKER_DURATION)
        Canvas { graphics ->
            graphics.roundedTexture(x, y, right, bottom, HUE_GRADIENT, corners = BAR_CORNERS)
            graphics.barMarker(this, hue.value)
        }.size(BAR_WIDTH, PICKER_HEIGHT).dragArea { _, fy -> value = Color(fy, value.saturation, value.brightness, value.alphaFloat) }
    }

    @Composable
    private fun AlphaBar(color: Color) {
        val alpha = animateFloatAsState(color.alphaFloat, MARKER_DURATION)
        val solid = remember(color) { color.withAlpha(1f).rgba }
        Canvas { graphics ->
            graphics.roundedGradient(x, y, right, bottom, Colors.TRANSPARENT.rgba, solid, GradientDirection.TOP_TO_BOTTOM, BAR_CORNERS)
            graphics.barMarker(this, alpha.value)
        }.size(BAR_WIDTH, PICKER_HEIGHT).dragArea { _, fy -> value = value.withAlpha(fy) }
    }

    override fun write(gson: Gson): JsonElement = gson.toJsonTree(value, Color::class.java)

    override fun read(element: JsonElement, gson: Gson) {
        value = gson.fromJson(element, Color::class.java) ?: default.copy()
    }

    private companion object {
        val HUE_GRADIENT: Identifier = Identifier.fromNamespaceAndPath("engineerclient", "textures/huegradient.png")
        val emptySlot: Int by lazy { GuiTheme.background.withAlpha(0.4f).rgba }

        const val MARKER_DURATION = 100
        const val GAP = 3
        const val PICKER_WIDTH = GuiTheme.INNER_WIDTH
        const val PICKER_HEIGHT = 112
        const val BAR_WIDTH = 10
        const val MARKER_RADIUS = 4f
        val BAR_CORNERS = Corners(2f)

        const val FIELD_HEIGHT = 16
        const val HEX_WIDTH = 60
        const val FAVORITE_SLOTS = 4
        const val FAVORITES_WIDTH = FAVORITE_SLOTS * FIELD_HEIGHT + (FAVORITE_SLOTS - 1) * GAP
        const val FAVORITE_RADIUS = 3f

        const val SWATCH_WIDTH = 22
        const val SWATCH_HEIGHT = 12
    }
}

private fun UiNode.dragArea(onMove: (fx: Float, fy: Float) -> Unit): UiNode {
    val follow: UiNode.(MouseButtonEvent) -> Unit = { event ->
        val fx = ((event.x() - x) / width).toFloat().coerceIn(0f, 1f)
        val fy = ((event.y() - y) / height).toFloat().coerceIn(0f, 1f)
        onMove(fx, fy)
    }
    return pointerInput(onDrag = follow) { event ->
        val left = event.button() == InputConstants.MOUSE_BUTTON_LEFT
        if (left) follow(event)
        left
    }
}

private fun GuiGraphicsExtractor.barMarker(bar: UiNode, progress: Float) {
    val markerY = (bar.y + progress * bar.height).roundToInt()
    roundedRect(bar.x - 1, markerY - 2, bar.right + 1, markerY + 2, Colors.WHITE.rgba, 2f)
}

fun Char.isHex() = isDigit() || this in 'a'..'f' || this in 'A'..'F'
