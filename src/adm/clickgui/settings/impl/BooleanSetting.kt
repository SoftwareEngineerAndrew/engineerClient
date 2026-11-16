package com.adm.clickgui.settings.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.adm.clickgui.settings.RenderableSetting
import com.adm.clickgui.settings.Saving
import com.adm.clickgui.ui.SettingRow
import com.adm.clickgui.ui.Switch

class BooleanSetting(
    name: String,
    override val default: Boolean = false,
    desc: String,
) : RenderableSetting<Boolean>(name, desc), Saving {

    override var value: Boolean by mutableStateOf(default)
    var enabled: Boolean by this::value

    @Composable
    override fun Content() {
        SettingRow {
            Switch(enabled, onClick = { enabled = !enabled })
        }
    }

    override fun write(gson: Gson): JsonElement = JsonPrimitive(enabled)

    override fun read(element: JsonElement, gson: Gson) {
        enabled = element.asBoolean
    }
}