package com.adm.clickgui.settings.impl

import androidx.compose.runtime.Composable
import com.adm.clickgui.GuiTheme
import com.adm.clickgui.settings.RenderableSetting
import com.adm.clickgui.ui.Button
import com.adm.clickgui.ui.tooltip
import com.adm.utils.ui.compose.Box
import com.adm.utils.ui.compose.offset
import com.adm.utils.ui.compose.size

class ActionSetting(
    name: String,
    desc: String,
    override val default: () -> Unit = {}
) : RenderableSetting<() -> Unit>(name, desc) {

    override var value: () -> Unit = default

    var action: () -> Unit by this::value

    @Composable
    override fun Content() {
        Box {
            Button(name, GuiTheme.ROW_WIDTH - INSET * 2, GuiTheme.SETTING_HEIGHT - 2) { action() }.offset({ INSET }, { 1 })
        }.size(GuiTheme.ROW_WIDTH, GuiTheme.SETTING_HEIGHT).tooltip(description)
    }

    private companion object {
        const val INSET = 3
    }
}