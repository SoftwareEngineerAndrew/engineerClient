package com.adm.clickgui.settings.impl

import androidx.compose.runtime.Composable
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.adm.clickgui.settings.SettingState
import com.adm.clickgui.GuiTheme
import com.adm.clickgui.settings.RenderableSetting
import com.adm.clickgui.settings.Saving
import com.adm.clickgui.ui.SettingRow
import com.adm.clickgui.ui.TextBox
import com.adm.utils.ui.compose.Column
import com.adm.utils.ui.compose.offset
import com.adm.utils.ui.compose.size

class StringSetting(
    name: String,
    override val default: String = "",
    val length: Int = 32,
    desc: String,
    private val placeholder: String
) : RenderableSetting<String>(name, desc), Saving {

    private var text by SettingState(default)

    override var value: String
        get() = text
        set(value) {
            if (value.length <= length) text = value
        }

    @Composable
    override fun Content() {
        Column {
            SettingRow(height = LABEL_HEIGHT)
            TextBox(value, { value = it }, placeholder, length).size(GuiTheme.INNER_WIDTH, BOX_HEIGHT).offset({ GuiTheme.PADDING })
        }
    }

    override fun write(gson: Gson): JsonElement = JsonPrimitive(value)

    override fun read(element: JsonElement, gson: Gson) {
        element.asString?.let { value = it }
    }

    private companion object {
        const val LABEL_HEIGHT = 14
        const val BOX_HEIGHT = 20
    }
}