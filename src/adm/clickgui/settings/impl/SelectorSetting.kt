package com.adm.clickgui.settings.impl

import androidx.compose.runtime.*
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.adm.clickgui.settings.SettingState
import com.adm.clickgui.GuiTheme
import com.adm.clickgui.settings.RenderableSetting
import com.adm.clickgui.settings.Saving
import com.adm.clickgui.ui.*
import com.adm.utils.Colors
import com.adm.utils.capitalizeFirst
import com.adm.utils.render.roundedOutline
import com.adm.utils.render.roundedRect
import com.adm.utils.ui.compose.*

// One label array per enum class, by ordinal: names are fixed, so each is worked out once.
private val enumLabels = object : ClassValue<Array<String?>>() {
    override fun computeValue(type: Class<*>): Array<String?> = arrayOfNulls(type.enumConstants?.size ?: 0)
}

private fun spell(constant: Enum<*>): String {
    val shown = constant.toString()
    if (shown != constant.name) return shown
    return constant.name.split('_').joinToString(" ") { it.lowercase().capitalizeFirst() }
}

val Enum<*>.label: String
    get() {
        val type = javaClass
        val known = enumLabels.get(if (type.isEnum) type else type.superclass)
        if (ordinal >= known.size) return spell(this)
        return known[ordinal] ?: spell(this).also { known[ordinal] = it }
    }

class SelectorSetting<E : Enum<E>>(
    name: String,
    override val default: E,
    desc: String,
    val options: List<E> = default.declaringJavaClass.enumConstants.asList()
) : RenderableSetting<E>(name, desc), Saving {

    override var value: E by SettingState(default)

    fun cycle() {
        val next = options.indexOf(value) + 1
        value = options[if (next >= options.size) 0 else next]
    }

    @Composable
    override fun Content() {
        var open by remember { mutableStateOf(false) }
        OnDismiss { open = false }

        Column {
            SettingRow { Pill(value.label, onClick = { open = !open }, onRightClick = ::cycle) }
            AnimatedVisibility(open) {
                Column {
                    Gap(1)
                    Column {
                        val last = options.lastIndex
                        for (i in options.indices) {
                            val option = options[i]
                            Choice(option, i == last) {
                                value = option
                                open = false
                            }
                        }
                    }
                        .width(LIST_WIDTH).offset({ LIST_INSET })
                        .drawBehind { graphics -> graphics.roundedRect(x, y, right, bottom, GuiTheme.surface.rgba, GuiTheme.RADIUS) }
                    Gap(LIST_INSET)
                }.width(GuiTheme.ROW_WIDTH)
            }
        }
    }

    @Composable
    private fun Choice(option: E, last: Boolean, onPick: () -> Unit): UiNode {
        val text = option.label
        return Box().size(LIST_WIDTH, OPTION_HEIGHT).clickable(onClick = onPick).drawBehind { graphics ->
            if (!last) graphics.fill(x + SEPARATOR_INSET, bottom, right - SEPARATOR_INSET, bottom + 1, Colors.MINECRAFT_DARK_GRAY.rgba)
            if (Pointer.isOver(x, y, width, height)) graphics.roundedOutline(x, y, right, bottom + 1, GuiTheme.accent.rgba, 1.5f, GuiTheme.RADIUS)
            graphics.textCentered(text, x, y, right, bottom)
        }
    }

    override fun write(gson: Gson): JsonElement = JsonPrimitive(value.name)

    override fun read(element: JsonElement, gson: Gson) {
        val saved = element.asString ?: return
        value = options.firstOrNull { it.name == saved }
            ?: options.firstOrNull { it.label.equals(saved, ignoreCase = true) }
            ?: return
    }

    private companion object {
        const val OPTION_HEIGHT = 16
        const val LIST_INSET = 4
        const val LIST_WIDTH = GuiTheme.ROW_WIDTH - LIST_INSET * 2
        const val SEPARATOR_INSET = 10
    }
}
