package com.adm.clickgui.settings.impl

import androidx.compose.runtime.*
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.mojang.blaze3d.platform.InputConstants
import com.adm.clickgui.settings.SettingState
import com.adm.clickgui.GuiTheme
import com.adm.clickgui.settings.RenderableSetting
import com.adm.clickgui.settings.Saving
import com.adm.clickgui.ui.SettingRow
import com.adm.clickgui.ui.animateProgress
import com.adm.utils.Colors
import com.adm.utils.render.Corners
import com.adm.utils.render.circle
import com.adm.utils.render.roundedRect
import com.adm.utils.render.roundedRectClipped
import com.adm.utils.ui.compose.*
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Suppress("UNCHECKED_CAST")
class NumberSetting<E>(
    name: String,
    override val default: E = 1.0 as E,
    range: ClosedFloatingPointRange<Double>,
    increment: Number = 1,
    desc: String,
    private val unit: String = ""
) : RenderableSetting<E>(name, desc), Saving where E : Number, E : Comparable<E> {

    constructor(
        name: String,
        default: E,
        range: IntRange,
        increment: Number = 1,
        desc: String,
        unit: String = ""
    ) : this(name, default, range.first.toDouble()..range.last.toDouble(), increment, desc, unit)

    private val kind = when (default) {
        is Int -> INT
        is Long -> LONG
        is Float -> FLOAT
        else -> DOUBLE
    }
    private val step = increment.toDouble()
    private val low = range.start
    private val high = range.endInclusive

    private var stored by SettingState(default)

    var display: String = format(default)
        private set

    override var value: E
        get() = stored
        set(value) {
            stored = typed((round(value.toDouble() / step) * step).coerceIn(low, high))
            display = format(stored)
        }

    init {
        value = default
    }

    var percent: Float
        get() = ((value.toDouble() - low) / (high - low)).toFloat()
        set(percent) {
            value = typed(low + percent.coerceIn(0f, 1f) * (high - low))
        }

    fun nudge(steps: Int) {
        value = typed((value.toDouble() + steps * step).coerceIn(low, high))
    }

    private fun typed(number: Double): E = when (kind) {
        INT -> number.roundToInt()
        LONG -> number.roundToLong()
        FLOAT -> number.toFloat()
        else -> number
    } as E

    private fun format(value: E): String {
        val number = value.toDouble()
        if (number % 1.0 == 0.0) return "${number.toInt()}$unit"
        return "${(number * 100).roundToInt() / 100.0}$unit"
    }

    @Composable
    override fun Content() {
        Column {
            SettingRow(height = 15) { Text({ display }) }.clickable {}
            Slider(percent, { percent = it }).size(GuiTheme.INNER_WIDTH, 11).offset({ GuiTheme.PADDING })
        }.onKey { event ->
            when (event.key) {
                InputConstants.KEY_RIGHT, InputConstants.KEY_EQUALS -> nudge(1)
                InputConstants.KEY_LEFT, InputConstants.KEY_MINUS -> nudge(-1)
                else -> return@onKey false
            }
            true
        }
    }

    override fun write(gson: Gson): JsonElement = JsonPrimitive(value)

    override fun read(element: JsonElement, gson: Gson) {
        element.asNumber?.let { value = typed(it.toDouble()) }
    }

    private companion object {
        const val INT = 0
        const val LONG = 1
        const val FLOAT = 2
        const val DOUBLE = 3
    }
}

@Composable
private fun Slider(value: Float, onValueChange: (Float) -> Unit): UiNode {
    val pointer = remember { InteractionSource() }
    var held by remember { mutableStateOf(false) }
    val fill = remember { Animatable(value) }
    SideEffect { if (held) fill.snapTo(value) else fill.animateTo(value, 200) }
    val grow = animateProgress { held || pointer.hovered }

    val seek: UiNode.(MouseButtonEvent) -> Unit = { event -> onValueChange(((event.x() - x) / width).toFloat().coerceIn(0f, 1f)) }

    return Canvas { graphics ->
        val top = y + (height - TRACK_HEIGHT) / 2
        val bottom = top + TRACK_HEIGHT
        graphics.roundedRect(x, top, right, bottom, GuiTheme.surface.rgba, TRACK_CORNERS)
        val filled = (fill.value * width).roundToInt()
        if (filled > 0) graphics.roundedRectClipped(x, top, right, bottom, x, top, x + filled, bottom, GuiTheme.accent.rgba, TRACK_CORNERS)
        graphics.circle(x + filled, top + TRACK_HEIGHT / 2, 4f + 1.5f * grow.value, Colors.WHITE.rgba)
    }
        .hoverable(pointer)
        .pointerInput(
            onDrag = {
                held = true
                seek(it)
            },
            onRelease = { held = false },
        ) { event ->
            val left = event.button() == InputConstants.MOUSE_BUTTON_LEFT
            if (left) seek(event)
            left
        }
        .onFocusChanged { if (!it) held = false }
}

private const val TRACK_HEIGHT = 6
private val TRACK_CORNERS = Corners(3f)
