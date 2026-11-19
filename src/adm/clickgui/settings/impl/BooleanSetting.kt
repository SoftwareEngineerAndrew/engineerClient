package com.adm.clickgui.settings.impl

import androidx.compose.runtime.Composable
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.adm.clickgui.settings.SettingState
import com.adm.events.core.SubscribeWhile
import com.adm.clickgui.settings.RenderableSetting
import com.adm.clickgui.settings.Saving
import com.adm.clickgui.ui.SettingRow
import com.adm.clickgui.ui.Switch

class BooleanSetting(
    name: String,
    override val default: Boolean = false,
    desc: String,
) : RenderableSetting<Boolean>(name, desc), Saving {

    private var stored: Boolean by SettingState(default)
    override var value: Boolean
        get() = stored
        set(v) {
            if (stored == v) return
            stored = v
            SubscribeWhile.changed()
        }
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